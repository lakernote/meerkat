package io.github.lakernote.meerkat.integration.observation.internal;

import io.github.lakernote.meerkat.config.MeerkatProperties;
import io.github.lakernote.meerkat.integration.jdbc.internal.JdbcTraceSafety;
import io.github.lakernote.meerkat.integration.observation.ObservationTraceIdResolver;
import io.github.lakernote.meerkat.metrics.internal.OperationStats;
import io.github.lakernote.meerkat.trace.TraceIdContext;
import io.github.lakernote.meerkat.trace.internal.BoundedThrowableFormatter;
import io.github.lakernote.meerkat.trace.internal.BoundedThrowableFormatter.FailureEvidence;
import io.github.lakernote.meerkat.trace.internal.CaptureTarget;
import io.github.lakernote.meerkat.trace.internal.CaptureThreadState;
import io.github.lakernote.meerkat.integration.redis.internal.LettuceCommandCapture;
import io.github.lakernote.meerkat.trace.internal.RootTraceHandle;
import io.github.lakernote.meerkat.trace.internal.SpanHandle;
import io.github.lakernote.meerkat.trace.internal.ThreadResourceSnapshot;
import io.github.lakernote.meerkat.trace.internal.TraceCompletion;
import io.github.lakernote.meerkat.trace.internal.TraceContext;
import io.github.lakernote.meerkat.trace.internal.TraceIds;
import io.github.lakernote.meerkat.trace.internal.store.TraceRecord;
import io.github.lakernote.meerkat.trace.internal.store.TraceStore;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationView;
import io.micrometer.observation.transport.ReceiverContext;
import io.micrometer.observation.transport.SenderContext;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.github.lakernote.meerkat.integration.observation.internal.ObservationAttributes.*;

/**
 * 将 Spring/Micrometer 发布的框架 Observation 接入统一 Trace。
 * <p>只把 HTTP、Job、Kafka 消费作为入口；Redis、数据库等作为入口下的依赖节点。</p>
 */
public final class ObservationTraceHandler implements ObservationHandler<Observation.Context> {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ObservationTraceHandler.class);
    private static final Object STATE_KEY = ObservationTraceHandler.class.getName() + ".state";
    private final TraceStore traceStore;
    private final MeerkatProperties properties;
    private final List<ObservationTraceIdResolver> traceIdResolvers;
    private final OperationStats operationStats;
    private final AtomicBoolean callbackFailureLogged = new AtomicBoolean();

    public ObservationTraceHandler(TraceStore traceStore,
                                   MeerkatProperties properties,
                                   List<ObservationTraceIdResolver> traceIdResolvers) {
        this(traceStore, properties, traceIdResolvers, null);
    }

    /** {@code operationStats} may be null when call statistics are disabled. */
    public ObservationTraceHandler(TraceStore traceStore,
                                   MeerkatProperties properties,
                                   List<ObservationTraceIdResolver> traceIdResolvers,
                                   OperationStats operationStats) {
        this.traceStore = traceStore;
        this.properties = properties;
        this.traceIdResolvers = List.copyOf(traceIdResolvers);
        this.operationStats = operationStats;
    }

    @Override
    public boolean supportsContext(Observation.Context context) {
        // Micrometer 可能在 Convention 设置名称前询问是否支持；等到 onStart 再读取完整上下文。
        return true;
    }

    @Override
    public void onStart(Observation.Context context) {
        boolean rootStarted = false;
        boolean installedTraceId = false;
        SpanHandle span = null;
        try {
            ObservationType type = ObservationType.of(context.getName());
            if (type == null) return;
            if (type == ObservationType.HTTP) {
                // 诊断页轮询自身 API 和静态资源会持续制造 HTTP Trace；仅跳过本组件记录，不改动宿主 Actuator/Micrometer 指标。
                if (isDashboardRequest(context)) return;
                // Trace 上下文绑定在线程上，无法跟随 WebFlux 请求切换线程，因此不记录响应式 HTTP 入口。
                if (isReactiveServerRequest(context)) return;
            }
            // 只有入口 Observation 创建根 Trace。客户端调用、SQL、Redis 和事务是依赖节点；脱离入口时不生成孤立 Trace。
            boolean traceActive = TraceContext.active();
            ObservationState parentState = parentTraceState(context);
            boolean root = type.entryPoint && !traceActive;
            String currentTraceId = TraceIdContext.currentTraceId();
            String propagatedTraceId = resolveTraceId(context);
            boolean traceIdRequired = root || traceActive || parentState != null;
            boolean currentValid = TraceIds.isValidTraceId(currentTraceId);
            boolean propagatedValid = TraceIds.isValidTraceId(propagatedTraceId);
            // 没有上游 ID 的入口沿用 Micrometer Tracing 的 trace id，两套日志和外部 Tracing 平台按同一 ID 关联。
            String tracingTraceId = root && !propagatedValid ? TracingTraceIds.of(context) : null;
            if (tracingTraceId != null && currentValid && type == ObservationType.HTTP
                    && HttpEntryRequest.replaceGeneratedTraceId(context, properties.getTrace().getHeaderName(), tracingTraceId)) {
                TraceIds.putTraceId(tracingTraceId);
            }
            installedTraceId = !currentValid && (traceIdRequired || propagatedValid);
            // Kafka 的 Observation 在 RecordInterceptor 之前启动；此处先从消息头恢复同一 Trace ID。
            String traceId = propagatedValid
                    ? propagatedTraceId
                    : parentState != null && TraceIds.isValidTraceId(parentState.traceId)
                    ? parentState.traceId
                    : !traceIdRequired ? ""
                    : !currentValid && tracingTraceId != null ? TraceIds.putOrCreateTraceId(tracingTraceId)
                    : TraceIds.getOrCreateTraceId();
            if (propagatedValid && !currentValid) {
                TraceIds.putTraceId(propagatedTraceId);
            }
            if (type == ObservationType.REMOTE || type == ObservationType.KAFKA_PRODUCER) {
                propagate(context, traceId);
            }
            String name = operationName(context, type);
            String detail = detail(context, type);
            long threadId = Thread.currentThread().getId();
            RootTraceHandle traceHandle = null;
            if (root) {
                // 入口建立请求级 Trace；依赖调用只挂到当前 Trace，不会单独伪装成请求。
                rootStarted = TraceContext.startRoot(name, type.spanKind, detail);
                traceHandle = TraceContext.currentRootTraceHandle();
                if (installedTraceId && traceHandle != null) traceHandle.rememberOwnedTraceId(traceId);
            } else if (traceActive) {
                // JDBC 查询在事务方法中同步执行；用作用域 span 表达事务包住 SQL 的真实调用层级。
                span = type == ObservationType.DATABASE || type == ObservationType.TRANSACTION
                        ? TraceContext.startScopedSpan(name, type.spanKind, detail)
                        : TraceContext.startDetachedSpan(name, type.spanKind, detail);
                traceHandle = TraceContext.currentRootTraceHandle();
            } else if (parentState != null) {
                // Lettuce 等异步客户端可能在线程切换后才启动 Observation；沿用父 Observation 保留请求关联。
                traceHandle = parentState.traceHandle;
                span = traceHandle.startDetachedSpan(name, type.spanKind, detail);
            }
            // CPU 时间和内存分配量在入口线程的请求开始/结束处采样；不归因到单个 Span 或子线程。
            ThreadResourceSnapshot resourcesAtStart = root ? ThreadResourceSnapshot.capture(threadId) : null;
            context.put(STATE_KEY, new ObservationState(type, name, detail, traceId, root, installedTraceId,
                    threadId, root ? safe(Thread.currentThread().getName(), 128) : "",
                    resourcesAtStart, span, traceHandle));
            if (rootStarted && traceHandle != null) trackActive(context, type, traceId, traceHandle, resourcesAtStart);
        } catch (RuntimeException failure) {
            // 监控回调异常不能影响应用已有的 Trace 和业务流程。
            if (rootStarted) {
                RootTraceHandle started = TraceContext.currentRootTraceHandle();
                if (started != null) started.discard();
                TraceContext.clear();
            }
            if (span != null) span.close();
            if (installedTraceId) TraceIds.clear();
            logCallbackFailure("start", failure);
        }
    }

    /** Lists the entry on the live diagnostics page; requests for the dashboard itself are left out. */
    private static void trackActive(Observation.Context context, ObservationType type, String traceId,
                                    RootTraceHandle handle, ThreadResourceSnapshot resourcesAtStart) {
        if (type == ObservationType.HTTP) {
            HttpEntryRequest request = HttpEntryRequest.of(context);
            if (request != null) {
                if (isDashboardPath(request.path())) return;
                handle.updateRootIdentity(request.method().isEmpty() ? request.path()
                        : request.path() + " · " + request.method(), request.path());
            }
        }
        handle.trackActive(traceId, type.name(), resourcesAtStart);
    }

    @Override
    public void onScopeOpened(Observation.Context context) {
        ObservationState state = context.get(STATE_KEY);
        if (state == null || state.type != ObservationType.REMOTE || state.span == null) return;
        try {
            CaptureTarget target = state.span.captureTarget();
            if (target == null || state.remoteCaptureThread != 0) return;
            synchronized (state) {
                if (state.remoteCaptureThread != 0) return;
                // HTTP 客户端拦截器在 Observation 作用域内执行，通过线程状态拿到本次远程调用节点。
                state.previousRemoteCapture = CaptureThreadState.openRemote(target);
                state.remoteCaptureThread = Thread.currentThread().getId();
            }
        } catch (RuntimeException failure) {
            logCallbackFailure("scope", failure);
        }
    }

    @Override
    public void onScopeClosed(Observation.Context context) {
        ObservationState state = context.get(STATE_KEY);
        if (state != null && state.remoteCaptureThread == Thread.currentThread().getId()) {
            // A scope may also open on another thread (reactive clients); only the installing thread restores.
            synchronized (state) {
                CaptureThreadState.restoreRemote(state.previousRemoteCapture);
                state.previousRemoteCapture = null;
                state.remoteCaptureThread = 0;
            }
        }
        if (state == null || !state.startedOnCurrentThread()) return;
        if (state.root) {
            // Async server observations leave the request thread before their final stop callback. Detach the tree
            // while that thread is still available for reuse; the Observation state retains it for finalization.
            TraceContext.detach(state.traceHandle);
        }
        clearInstalledTraceId(state);
    }

    @Override
    public void onError(Observation.Context context) {
        ObservationState state = context.get(STATE_KEY);
        if (state == null || context.getError() == null) return;
        try {
            markFailure(state, context.getError());
        } catch (RuntimeException failure) {
            logCallbackFailure("error", failure);
        }
    }

    @Override
    public void onStop(Observation.Context context) {
        ObservationState state = context.get(STATE_KEY);
        if (state == null) return;
        boolean ownsRoot = state.root && state.traceHandle != null;
        try {
            String detail = stopDetail(context, state);
            // Spring MVC 的 URI 路由标签可能到 onStop 才补齐；此时再判断，避免诊断页轮询占满 Trace 保留区。
            if (state.type == ObservationType.HTTP && isDashboardPath(detail)) {
                if (ownsRoot) state.traceHandle.discard();
                return;
            }
            long durationNanos = Math.max(0, System.nanoTime() - state.startedAtNanos);
            long durationMs = TimeUnit.NANOSECONDS.toMillis(durationNanos);
            ResourceUsage resources = state.root
                    ? ResourceUsage.between(state.resourcesAtStart, state.resourcesAtStop(), properties)
                    : ResourceUsage.UNKNOWN;
            Throwable error = context.getError();
            if (error != null) {
                // Some instrumentation only exposes the error at stop; preserve the same child/root semantics.
                if (state.failure == null) markFailure(state, error);
                else if (state.span != null) state.span.markError(state.failure);
            }
            boolean redisFailed = markRedisCommandError(context, state);
            if (state.type == ObservationType.REDIS && state.span != null) captureRedisCommand(context, state.span);
            int status = httpStatus(context);
            if (error == null && status >= 500) {
                markTrace(state, "HTTP_" + status, new FailureEvidence("HTTP_" + status, "HTTP status " + status, ""));
            }
            long threshold = state.root ? state.type.slowThresholdMs(properties) : 0;
            boolean slow = threshold > 0 && durationMs >= threshold;
            // 通过所属 Trace 句柄更新标记，不读取结束线程当前的 ThreadLocal Trace。
            if (slow) markSlow(state);
            if (state.span != null) state.span.close();
            recordStats(context, state, detail, durationNanos,
                    error != null || state.failure != null || status >= 500 || redisFailed, resources);
            if (!ownsRoot) return;
            String displayName = displayName(context, state.type, state.operationName, detail);
            state.traceHandle.updateRootIdentity(displayName, detail);
            RootOutcome outcome = new RootOutcome(displayName, detail, status, durationMs, slow,
                    error != null || status >= 500 || redisFailed, resources);
            if (outcome.resources.exceeded()) state.traceHandle.requireTree();
            state.traceHandle.stop(threshold, error,
                    completion -> storeCompletedTrace(state, completion, outcome));
        } catch (RuntimeException failure) {
            if (ownsRoot) state.traceHandle.discard();
            logCallbackFailure("stop", failure);
        } finally {
            if (state.startedOnCurrentThread()) clearInstalledTraceId(state);
            context.remove(STATE_KEY);
        }
    }

    private void recordStats(Observation.Context context, ObservationState state, String detail, long durationNanos,
                             boolean failed, ResourceUsage resources) {
        if (operationStats == null) return;
        OperationStats.Category category = statsCategory(state.type);
        if (category == null) return;
        operationStats.record(category, statsName(context, state.type, state.operationName, detail),
                durationNanos, failed, resources.cpuNanos, resources.allocatedBytes);
    }

    private void storeCompletedTrace(ObservationState state, TraceCompletion completion, RootOutcome outcome) {
        boolean slow = outcome.slow || completion.slow();
        boolean failed = outcome.failed || completion.error();
        // 内存近期列表和持久存储只保留慢、错或资源超阈值的入口 Trace；资源超阈值时即使耗时不高也保留。
        if (!slow && !failed && !outcome.resources.exceeded()) return;
        TraceRecord trace = TraceRecord.builder()
                .traceId(state.traceId)
                .sourceType(state.type.name())
                .name(outcome.displayName)
                .tag(outcome.detail)
                .status(outcome.status > 0 ? outcome.status : outcome.failed ? 500 : 200)
                .durationMs(outcome.durationMs)
                .slow(slow)
                .error(failed)
                .highCpu(outcome.resources.highCpu)
                .highAllocation(outcome.resources.highAllocation)
                .treeNodes(completion.nodes())
                .threadName(state.startThreadName)
                .threadId(state.startThreadId)
                .cpuTimeMs(outcome.resources.cpuTimeMs)
                .allocatedBytes(outcome.resources.allocatedBytes)
                .errorType(completion.errorType())
                .errorMessage(completion.errorMessage())
                .errorStackTrace(completion.stackTrace())
                .build();
        try {
            traceStore.add(trace);
        } catch (RuntimeException failure) {
            logCallbackFailure("trace storage", failure);
        }
    }

    private static String stopDetail(Observation.Context context, ObservationState state) {
        // JDBC query text is fixed at observation start; avoid scanning and sanitizing it again on completion.
        if (state.type == ObservationType.DATABASE) return state.startDetail;
        String detail = detail(context, state.type);
        return detail.isBlank() ? state.startDetail : detail;
    }

    private static void captureRedisCommand(Observation.Context context, SpanHandle span) {
        CaptureTarget target = span.captureTarget();
        if (target != null && LettuceSupport.PRESENT) LettuceCommandCapture.capture(context, target);
    }

    /** Keeps Lettuce types out of this class so it loads without Lettuce on the classpath. */
    private static final class LettuceSupport {
        static final boolean PRESENT = org.springframework.util.ClassUtils.isPresent(
                "io.lettuce.core.tracing.LettuceObservationContext", ObservationTraceHandler.class.getClassLoader());
    }

    private static boolean markRedisCommandError(Observation.Context context, ObservationState state) {
        if (state.type != ObservationType.REDIS) return false;
        String commandError = highCardinality(context, "lettuce.command.error");
        if (commandError.isBlank()) return false;
        FailureEvidence failure = new FailureEvidence("RedisCommandError",
                "Redis command failed: " + safe(commandError, 512), "");
        if (state.span != null) state.span.markError(failure);
        // Redis 依赖不会回退到结束线程的 ThreadLocal Trace，避免误标同线程上无关的请求。
        if (state.traceHandle != null) state.traceHandle.markError("REDIS_COMMAND_ERROR", failure);
        return true;
    }

    private static void markFailure(ObservationState state, Throwable error) {
        FailureEvidence failure = state.type == ObservationType.DATABASE
                ? JdbcTraceSafety.captureFailure(error)
                : BoundedThrowableFormatter.capture(error);
        if (state.span != null) state.span.markError(failure);
        markTrace(state, state.type.name() + "_ERROR", failure);
        state.failure = failure;
    }

    /** Marks the owning trace, falling back to this thread's trace only when the Observation started here. */
    private static void markTrace(ObservationState state, String reason, FailureEvidence failure) {
        if (state.traceHandle != null) state.traceHandle.markError(reason, failure);
        else if (state.startedOnCurrentThread()) TraceContext.markError(reason, failure);
    }

    private static void markSlow(ObservationState state) {
        String reason = "SLOW_" + state.type.name();
        if (state.span != null) state.span.markSlow();
        if (state.traceHandle != null) state.traceHandle.markSlow(reason);
        else if (state.startedOnCurrentThread()) TraceContext.markSlow(reason);
    }

    private static void clearInstalledTraceId(ObservationState state) {
        if (state.installedTraceId && state.traceId.equals(TraceIdContext.currentTraceId())) TraceIds.clear();
    }

    private static ObservationState parentTraceState(Observation.Context context) {
        ObservationView parent = context.getParentObservation();
        while (parent != null) {
            Observation.ContextView parentContext = parent.getContextView();
            ObservationState state = parentContext.get(STATE_KEY);
            if (state != null && state.traceHandle != null) return state;
            parent = parentContext.getParentObservation();
        }
        return null;
    }

    private String resolveTraceId(Observation.Context context) {
        for (ObservationTraceIdResolver resolver : traceIdResolvers) {
            try {
                String traceId = resolver.resolve(context);
                if (TraceIds.isValidTraceId(traceId)) return traceId;
            } catch (RuntimeException ignored) {
                // 可选集成的解析异常不能影响 Observation 和业务调用。
            }
        }
        // 通用兜底：任何携带 getter 的 ReceiverContext（gRPC、消息消费等）按配置的请求头或 W3C traceparent 读取。
        if (context instanceof ReceiverContext<?> receiver) {
            try {
                String traceId = readHeader(receiver, properties.getTrace().getHeaderName());
                if (TraceIds.isValidTraceId(traceId)) return traceId;
                traceId = TraceIds.fromTraceparent(readHeader(receiver, TraceIds.TRACEPARENT_HEADER));
                if (traceId != null) return traceId;
            } catch (RuntimeException ignored) {
                // 载体读取失败时按无上游 Trace ID 处理。
            }
        }
        return null;
    }

    /** RestTemplate、RestClient、WebClient、Kafka 等发送端 Observation 自带写头回调；统一借此写入 Trace ID。 */
    private void propagate(Observation.Context context, String traceId) {
        if (!(context instanceof SenderContext<?> sender) || !TraceIds.isValidTraceId(traceId)) return;
        try {
            writeHeader(sender, properties.getTrace().getHeaderName(), traceId);
        } catch (RuntimeException ignored) {
            // 部分载体只读或尚未就绪；已有的客户端拦截器仍会兜底写入。
        }
    }

    @SuppressWarnings("unchecked")
    private static <C> void writeHeader(SenderContext<C> sender, String headerName, String traceId) {
        C carrier = sender.getCarrier();
        if (carrier == null || sender.getSetter() == null) return;
        sender.getSetter().set(carrier, headerName, traceId);
    }

    private static <C> String readHeader(ReceiverContext<C> receiver, String headerName) {
        C carrier = receiver.getCarrier();
        if (carrier == null || receiver.getGetter() == null) return null;
        return receiver.getGetter().get(carrier, headerName);
    }

    private void logCallbackFailure(String callback, RuntimeException failure) {
        if (callbackFailureLogged.compareAndSet(false, true)) {
            log.warn("Meerkat Observation {} callback failed; business execution continues ({})",
                    callback, failure.getClass().getName());
        }
    }

    private record RootOutcome(String displayName, String detail, int status, long durationMs, boolean slow,
                               boolean failed, ResourceUsage resources) {
    }

    /** Entry-thread CPU time and allocation for one request; -1 means unknown (for example, finished elsewhere). */
    private record ResourceUsage(long cpuNanos, long cpuTimeMs, long allocatedBytes, boolean highCpu,
                                 boolean highAllocation) {
        static final ResourceUsage UNKNOWN = new ResourceUsage(-1, -1, -1, false, false);

        static ResourceUsage between(ThreadResourceSnapshot start, ThreadResourceSnapshot stop,
                                     MeerkatProperties properties) {
            if (start == null || stop == null) return UNKNOWN;
            long cpuNanos = stop.cpuNanosSince(start);
            long cpuTimeMs = cpuNanos < 0 ? -1 : cpuNanos / 1_000_000;
            long allocatedBytes = stop.allocatedBytesSince(start);
            long cpuThresholdMs = properties.getTrace().getCpuThreshold().toMillis();
            long allocationThreshold = properties.getTrace().getAllocatedMemoryThreshold().toBytes();
            return new ResourceUsage(cpuNanos, cpuTimeMs, allocatedBytes,
                    cpuThresholdMs > 0 && cpuTimeMs >= cpuThresholdMs,
                    allocationThreshold > 0 && allocatedBytes >= allocationThreshold);
        }

        boolean exceeded() {
            return highCpu || highAllocation;
        }
    }
}
