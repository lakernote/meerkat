package io.github.lakernote.meerkat.integration.jdbc.internal;

import io.github.lakernote.meerkat.trace.internal.CaptureTarget;
import io.github.lakernote.meerkat.trace.internal.CaptureThreadState;
import net.ttddyy.dsproxy.ConnectionInfo;
import net.ttddyy.dsproxy.proxy.JdbcProxyFactory;
import net.ttddyy.dsproxy.proxy.ProxyConfig;
import net.ttddyy.dsproxy.proxy.ProxyJdbcObject;
import net.ttddyy.dsproxy.proxy.jdk.DataSourceInvocationHandler;
import org.aopalliance.intercept.MethodInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.ProxyMethodInvocation;
import org.springframework.aop.RawTargetAccess;
import org.springframework.aop.framework.ProxyFactory;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Keep the outer data source Spring-aware and use datasource-proxy's cached JDK proxies for JDBC calls. */
public final class SpringAwareJdbcProxyFactory implements JdbcProxyFactory {
    private static final Logger log = LoggerFactory.getLogger(SpringAwareJdbcProxyFactory.class);
    private static final Set<MethodSignature> JDBC_PROXY_METHODS = jdbcProxyMethods();
    private final JdbcProxyFactory delegate = JdbcProxyFactory.DEFAULT;

    /** Prevents Spring AOP from replacing ProxyJdbcObject.getTarget() with the class proxy itself. */
    public interface TypePreservingProxyJdbcObject extends ProxyJdbcObject, RawTargetAccess {
        @Override
        Object getTarget();

        @Override
        ProxyConfig getProxyConfig();
    }

    @Override
    public DataSource createDataSource(DataSource dataSource, ProxyConfig proxyConfig) {
        Class<?> targetClass = dataSource.getClass();
        try {
            if (!supportsTypePreservingProxy(targetClass)) {
                log.warn("JDBC tracing is disabled for data source '{}' because type {} cannot be safely class-proxied",
                        proxyConfig.getDataSourceName(), targetClass.getName());
                return dataSource;
            }
            return createDataSourceProxy(dataSource, new DataSourceInvocationHandler(dataSource, proxyConfig));
        } catch (RuntimeException | LinkageError failure) {
            log.warn("JDBC tracing is disabled for data source '{}' because a type-preserving proxy for {} "
                            + "could not be created", proxyConfig.getDataSourceName(), targetClass.getName(), failure);
            return dataSource;
        }
    }

    @Override
    public Connection createConnection(Connection connection, ConnectionInfo connectionInfo, ProxyConfig proxyConfig) {
        return delegate.createConnection(connection, connectionInfo, proxyConfig);
    }

    @Override
    public Statement createStatement(Statement statement, ConnectionInfo connectionInfo, Connection proxyConnection,
                                     ProxyConfig proxyConfig) {
        return delegate.createStatement(statement, connectionInfo, proxyConnection, proxyConfig);
    }

    @Override
    public PreparedStatement createPreparedStatement(PreparedStatement statement, String query,
                                                    ConnectionInfo connectionInfo, Connection proxyConnection,
                                                    ProxyConfig proxyConfig, boolean generateKey) {
        return delegate.createPreparedStatement(statement, query, connectionInfo, proxyConnection,
                proxyConfig, generateKey);
    }

    @Override
    public CallableStatement createCallableStatement(CallableStatement statement, String query,
                                                     ConnectionInfo connectionInfo, Connection proxyConnection,
                                                     ProxyConfig proxyConfig) {
        return delegate.createCallableStatement(statement, query, connectionInfo, proxyConnection, proxyConfig);
    }

    @Override
    public ResultSet createResultSet(ResultSet resultSet, ConnectionInfo connectionInfo, ProxyConfig proxyConfig) {
        // 结果集代理只为流量抓取开启；未抓取的语句原样返回驱动结果集。
        if (!CaptureThreadState.hasPendingStatement()) return resultSet;
        try {
            CaptureTarget target = CaptureThreadState.statementTarget(resultSet.getStatement());
            return target == null ? resultSet : CapturingResultSet.wrap(resultSet, target);
        } catch (java.sql.SQLException | RuntimeException unavailable) {
            return resultSet;
        }
    }

    @Override
    public ResultSet createGeneratedKeys(ResultSet resultSet, ConnectionInfo connectionInfo, ProxyConfig proxyConfig) {
        return delegate.createGeneratedKeys(resultSet, connectionInfo, proxyConfig);
    }

    private DataSource createDataSourceProxy(DataSource target, InvocationHandler handler) {
        MethodInterceptor interceptor = invocation -> {
            if (!isJdbcProxyMethod(invocation.getMethod())) return invocation.proceed();
            Object proxy = invocation instanceof ProxyMethodInvocation proxyInvocation
                    ? proxyInvocation.getProxy() : invocation.getThis();
            return handler.invoke(proxy, invocation.getMethod(), invocation.getArguments());
        };
        ProxyFactory proxyFactory = new ProxyFactory();
        proxyFactory.setTarget(target);
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addInterface(TypePreservingProxyJdbcObject.class);
        proxyFactory.addAdvice(interceptor);
        return (DataSource) proxyFactory.getProxy(target.getClass().getClassLoader());
    }

    private static boolean isJdbcProxyMethod(Method method) {
        return JDBC_PROXY_METHODS.contains(MethodSignature.from(method));
    }

    private static Set<MethodSignature> jdbcProxyMethods() {
        Set<MethodSignature> methods = new HashSet<>();
        for (Class<?> contract : List.of(DataSource.class, ProxyJdbcObject.class, Object.class)) {
            for (Method method : contract.getMethods()) methods.add(MethodSignature.from(method));
        }
        return Set.copyOf(methods);
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

    private record MethodSignature(String name, List<Class<?>> parameterTypes) {
        private static MethodSignature from(Method method) {
            return new MethodSignature(method.getName(), List.of(method.getParameterTypes()));
        }
    }
}
