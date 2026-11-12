package io.github.lakernote.meerkat.integration.redis.internal;

import io.github.lakernote.meerkat.metrics.internal.OperationStats;
import io.github.lakernote.meerkat.trace.SpanKind;
import io.github.lakernote.meerkat.trace.internal.BoundedThrowableFormatter;
import io.github.lakernote.meerkat.trace.internal.TraceContext;
import io.github.lakernote.meerkat.trace.internal.DetachedSpan;
import io.github.lakernote.meerkat.trace.internal.CaptureTarget;
import io.github.lakernote.meerkat.capture.internal.CaptureText;
import org.aopalliance.intercept.MethodInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.data.redis.connection.RedisClusterCommandsProvider;
import org.springframework.data.redis.connection.RedisCommandsProvider;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.util.ClassUtils;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicBoolean;

/** Instruments Redisson commands that are issued through Spring Data Redis connections. */
public final class RedissonTraceConnectionFactoryPostProcessor implements BeanPostProcessor {
    private static final Logger log = LoggerFactory.getLogger(RedissonTraceConnectionFactoryPostProcessor.class);
    private static final Set<MethodSignature> CONNECTION_FACTORY_METHODS = connectionFactoryMethods();
    private static final Set<MethodSignature> COMMAND_ACCESSOR_METHODS = commandAccessorMethods();
    private final Supplier<OperationStats> operationStatsProvider;
    private final AtomicBoolean connectionProxyFailureLogged = new AtomicBoolean();
    private final AtomicBoolean commandProxyFailureLogged = new AtomicBoolean();
    private volatile boolean providersResolved;
    private volatile OperationStats operationStats;

    /** The supplier is resolved on the first command and may return null when metrics are disabled. */
    public RedissonTraceConnectionFactoryPostProcessor(Supplier<OperationStats> operationStatsProvider) {
        this.operationStatsProvider = operationStatsProvider;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (!(bean instanceof RedisConnectionFactory factory)
                || !ClassUtils.getUserClass(bean).getName().startsWith("org.redisson.spring.data.connection.RedissonConnectionFactory")) {
            return bean;
        }
        Class<?> runtimeClass = bean.getClass();
        Class<?> userClass = ClassUtils.getUserClass(bean);
        if (AopUtils.isAopProxy(bean)) {
            log.warn("Redisson tracing is disabled for connection factory '{}' because type {} is already proxied",
                    beanName, userClass.getName());
            return bean;
        }
        try {
            if (!supportsTypePreservingProxy(runtimeClass)) {
                log.warn("Redisson tracing is disabled for connection factory '{}' because type {} cannot be "
                        + "safely class-proxied", beanName, userClass.getName());
                return bean;
            }
            ProxyFactory proxyFactory = new ProxyFactory();
            proxyFactory.setTarget(factory);
            proxyFactory.setProxyTargetClass(true);
            proxyFactory.addAdvice((MethodInterceptor) invocation -> {
                Object result = invocation.proceed();
                if (!(result instanceof RedisConnection connection)
                        || !CONNECTION_FACTORY_METHODS.contains(MethodSignature.from(invocation.getMethod()))) {
                    return result;
                }
                try {
                    return wrap(connection);
                } catch (RuntimeException | LinkageError failure) {
                    if (connectionProxyFailureLogged.compareAndSet(false, true)) {
                        log.warn("Redisson tracing is disabled for a connection returned by factory '{}' because "
                                + "its type {} could not be proxied", beanName, connection.getClass().getName(), failure);
                    }
                    return connection;
                }
            });
            return proxyFactory.getProxy(runtimeClass.getClassLoader());
        } catch (RuntimeException | LinkageError failure) {
            log.warn("Redisson tracing is disabled for connection factory '{}' because a type-preserving proxy for "
                    + "{} could not be created", beanName, userClass.getName(), failure);
            return bean;
        }
    }

    RedisConnection wrap(RedisConnection connection) {
        Set<Class<?>> interfaces = publicInterfaces(connection.getClass());
        interfaces.add(RedisConnection.class);
        return (RedisConnection) Proxy.newProxyInstance(connection.getClass().getClassLoader(),
                interfaces.toArray(Class<?>[]::new), new ConnectionHandler(connection));
    }

    private final class ConnectionHandler implements InvocationHandler {
        private final RedisConnection delegate;

        private ConnectionHandler(RedisConnection delegate) {
            this.delegate = delegate;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) return invokeTarget(method, delegate, args);
            if (isCommandAccessor(method)) {
                return invokeCommandAccessor(proxy, method, delegate, args, delegate);
            }
            if (!isCommand(method)) return invokeTarget(method, delegate, args);
            return invokeCommand(method, delegate, args, delegate);
        }
    }

    private final class CommandHandler implements InvocationHandler {
        private final Object delegate;
        private final RedisConnection connection;

        private CommandHandler(Object delegate, RedisConnection connection) {
            this.delegate = delegate;
            this.connection = connection;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) return invokeTarget(method, delegate, args);
            if (isCommandAccessor(method)) {
                return invokeCommandAccessor(proxy, method, delegate, args, connection);
            }
            if (!isCommand(method)) return invokeTarget(method, delegate, args);
            return invokeCommand(method, delegate, args, connection);
        }
    }

    private Object invokeCommandAccessor(Object proxy, Method method, Object target, Object[] args,
                                         RedisConnection connection) throws Throwable {
        Object commands = invokeTarget(method, target, args);
        if (commands == null || !method.getReturnType().isInterface()) return commands;
        if (commands == target && method.getReturnType().isInstance(proxy)) return proxy;
        try {
            Set<Class<?>> interfaces = publicInterfaces(commands.getClass());
            interfaces.add(method.getReturnType());
            return Proxy.newProxyInstance(commands.getClass().getClassLoader(), interfaces.toArray(Class<?>[]::new),
                    new CommandHandler(commands, connection));
        } catch (RuntimeException | LinkageError failure) {
            if (commandProxyFailureLogged.compareAndSet(false, true)) {
                log.warn("Redisson tracing is disabled for command provider {} returned by {} because it could not "
                        + "be proxied", commands.getClass().getName(), method.getName(), failure);
            }
            return commands;
        }
    }

    private Object invokeCommand(Method method, Object target, Object[] args, RedisConnection connection)
            throws Throwable {
        // Redis is always a dependency operation. Only an inbound HTTP, job, or Kafka
        // consumer observation may create a trace root.
        String name = method.getName();
        DetachedSpan span = TraceContext.startDetachedSpan("Redis " + name, SpanKind.REDIS, name);
        Throwable failure = null;
        boolean recordMetric = !isPipelined(connection);
        long startedAt = System.nanoTime();
        CaptureTarget capture = span.captureTarget();
        try {
            Object result = invokeTarget(method, target, args);
            if (capture != null) captureCommand(capture, name, args, result, null);
            return result;
        } catch (Throwable error) {
            failure = error;
            if (capture != null) captureCommand(capture, name, args, null, error);
            throw error;
        } finally {
            finish(span, failure);
            if (recordMetric) recordMetric(method, startedAt, failure);
        }
    }

    private static void captureCommand(CaptureTarget capture, String name, Object[] args, Object result,
                                       Throwable failure) {
        try {
            int maxChars = capture.limits().maxValueChars();
            StringBuilder command = new StringBuilder(name.toUpperCase(java.util.Locale.ROOT));
            if (args != null) {
                for (int index = 0; index < args.length; index++) {
                    if (command.length() > maxChars) {
                        command.append(" …(+").append(args.length - index).append(" args)");
                        break;
                    }
                    command.append(' ').append(CaptureText.of(args[index], maxChars - command.length() + 16));
                }
            }
            capture.capture("command", command.toString());
            if (failure != null) capture.capture("error", failure.toString());
            else capture.capture("result", CaptureText.of(result, maxChars));
        } catch (RuntimeException ignored) {
            // 抓取失败不能影响 Redis 调用。
        }
    }

    private static boolean isPipelined(RedisConnection connection) {
        try {
            return connection.isPipelined();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private void recordMetric(Method method, long startedAt, Throwable failure) {
        try {
            resolveProviders();
            long durationNanos = Math.max(0, System.nanoTime() - startedAt);
            String command = method.getName().toUpperCase(java.util.Locale.ROOT);
            OperationStats stats = operationStats;
            if (stats != null) stats.record(OperationStats.Category.REDIS, command, durationNanos, failure != null);
        } catch (RuntimeException ignored) {
            // Metrics must not affect the application's Redis result.
        }
    }

    private void resolveProviders() {
        if (!providersResolved) {
            synchronized (this) {
                if (!providersResolved) {
                    operationStats = operationStatsProvider.get();
                    providersResolved = true;
                }
            }
        }
    }

    private void finish(DetachedSpan span, Throwable failure) {
        BoundedThrowableFormatter.FailureEvidence evidence = null;
        try {
            if (failure != null) {
                evidence = BoundedThrowableFormatter.capture(failure);
                span.markError(evidence);
            }
        } catch (Throwable ignored) {
            // Instrumentation must never change the application's Redis result.
        } finally {
            span.close();
        }
        if (evidence != null) TraceContext.markError("REDIS_ERROR", evidence);
    }

    private static boolean isCommand(Method method) {
        return switch (method.getName()) {
            case "close", "isClosed", "getNativeConnection", "getSubscription", "openPipeline", "closePipeline",
                    "getSentinelConnection", "isQueueing", "open", "isPipelined", "multi", "exec", "discard",
                    "watch", "unwatch" -> false;
            default -> true;
        };
    }

    private static boolean isCommandAccessor(Method method) {
        return method.getParameterCount() == 0 && method.getReturnType().isInterface()
                && COMMAND_ACCESSOR_METHODS.contains(MethodSignature.from(method));
    }

    private static Object invokeTarget(Method method, Object target, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException error) { throw error.getCause(); }
    }

    private static Set<Class<?>> publicInterfaces(Class<?> type) {
        Set<Class<?>> interfaces = new LinkedHashSet<>();
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            ClassUtils.getAllInterfacesForClassAsSet(current).stream()
                    .filter(candidate -> java.lang.reflect.Modifier.isPublic(candidate.getModifiers()))
                    .forEach(interfaces::add);
        }
        return interfaces;
    }

    private static boolean supportsTypePreservingProxy(Class<?> targetClass) {
        if (Modifier.isFinal(targetClass.getModifiers())) return false;
        for (Method method : targetClass.getMethods()) {
            int modifiers = method.getModifiers();
            if (method.getDeclaringClass() != Object.class && !Modifier.isStatic(modifiers)
                    && Modifier.isFinal(modifiers)) return false;
        }
        return true;
    }

    private static Set<MethodSignature> connectionFactoryMethods() {
        Set<MethodSignature> methods = new HashSet<>();
        for (Method method : RedisConnectionFactory.class.getMethods()) {
            if (RedisConnection.class.isAssignableFrom(method.getReturnType())) {
                methods.add(MethodSignature.from(method));
            }
        }
        return Set.copyOf(methods);
    }

    private static Set<MethodSignature> commandAccessorMethods() {
        Set<MethodSignature> methods = new HashSet<>();
        for (Class<?> providerType : List.of(RedisCommandsProvider.class, RedisClusterCommandsProvider.class)) {
            for (Method method : providerType.getMethods()) {
                methods.add(MethodSignature.from(method));
            }
        }
        return Set.copyOf(methods);
    }

    private record MethodSignature(String name, List<Class<?>> parameterTypes) {
        private static MethodSignature from(Method method) {
            return new MethodSignature(method.getName(), List.of(method.getParameterTypes()));
        }
    }
}
