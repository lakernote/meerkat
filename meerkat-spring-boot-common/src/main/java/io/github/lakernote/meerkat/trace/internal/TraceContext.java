package io.github.lakernote.meerkat.trace.internal;

import io.github.lakernote.meerkat.trace.SpanKind;
import io.github.lakernote.meerkat.trace.TraceIdContext;

import java.util.List;
import java.util.function.Consumer;

/**
 * 管理 Trace 树和当前执行位置。
 * <p>ThreadLocal 只保存本线程当前的不可变执行帧；异步任务通过 Snapshot 显式继承树和父 Span。</p>
 */
public final class TraceContext {
    public static final String CODE_ERROR_REASON = "CODE_ERROR";
    private static final ThreadLocal<ExecutionFrame> CURRENT_FRAME = new ThreadLocal<>();

    private TraceContext() {
    }

    /** 当前线程是否存在尚未结束的 Trace。 */
    public static boolean active() {
        return currentTrace() != null;
    }

    public static boolean startRoot(String name, SpanKind kind) {
        return startRoot(name, kind, "");
    }

    /**
     * Starts a root trace on this thread. Returns {@code false} without changes when a trace is already active, so
     * an entry nested inside another entry never replaces the outer request's tree.
     */
    public static boolean startRoot(String name, SpanKind kind, String tag) {
        if (currentFrame() != null) return false;
        TraceTree trace = new TraceTree(name, kind, tag);
        CURRENT_FRAME.set(new ExecutionFrame(trace, trace.rootSpan(), 1));
        return true;
    }

    /** Captures the current Trace ID and parent Span for propagation across an async boundary. */
    public static Snapshot capture() {
        return new Snapshot(currentFrame(), TraceIdContext.currentTraceId());
    }

    /** Makes a captured context current and restores the previous thread context when closed. */
    public static Scope makeCurrent(Snapshot snapshot) {
        ExecutionFrame previous = CURRENT_FRAME.get();
        String previousTraceId = TraceIdContext.currentTraceId();
        ExecutionFrame captured = snapshot == null ? null : snapshot.frame;
        if (captured != null && captured.trace.completed()) captured = null;
        if (captured == null) CURRENT_FRAME.remove();
        else CURRENT_FRAME.set(captured);
        // 完成的树不能再接收 Span，但 Trace ID 仍可用于异步任务日志和下游调用关联。
        String traceId = snapshot == null ? null : snapshot.traceId;
        MdcContext.putOrRemove(TraceIdContext.MDC_KEY,
                TraceIds.isValidTraceId(traceId) ? traceId : null);
        return new Scope(previous, previousTraceId);
    }

    public static final class Snapshot {
        private final ExecutionFrame frame;
        private final String traceId;

        private Snapshot(ExecutionFrame frame, String traceId) {
            this.frame = frame;
            this.traceId = TraceIds.isValidTraceId(traceId) ? traceId : null;
        }
    }

    public static final class Scope implements AutoCloseable {
        private final ExecutionFrame previous;
        private final String previousTraceId;
        private final long ownerThreadId = Thread.currentThread().getId();
        private boolean closed;

        private Scope(ExecutionFrame previous, String previousTraceId) {
            this.previous = previous;
            this.previousTraceId = previousTraceId;
        }

        @Override
        public void close() {
            if (closed) return;
            if (ownerThreadId != Thread.currentThread().getId()) {
                throw new IllegalStateException("Trace context scope must close on its owner thread");
            }
            if (previous == null) {
                CURRENT_FRAME.remove();
                CaptureThreadState.forgetStatement();
            } else {
                CURRENT_FRAME.set(previous);
            }
            MdcContext.putOrRemove(TraceIdContext.MDC_KEY, previousTraceId);
            closed = true;
        }
    }

    /** Returns a handle to the active tree so an Observation can update or finish it on another thread. */
    public static RootTraceHandle currentRootTraceHandle() {
        TraceTree trace = currentTrace();
        return trace == null ? null : new RootTraceHandle(trace);
    }

    /** Detaches an active tree from this thread while preserving its handle for asynchronous finalization. */
    public static void detach(RootTraceHandle handle) {
        ExecutionFrame current = CURRENT_FRAME.get();
        if (handle == null) return;
        handle.trace().markDetached();
        if (current != null && current.trace == handle.trace()) CURRENT_FRAME.remove();
    }

    /** 由调用方显式标记当前 Trace 为慢调用，不代表自动依赖节点阈值。 */
    public static void markSlow(String reason) {
        ExecutionFrame current = currentFrame();
        if (current != null) {
            current.trace.markSlow(reason);
            current.trace.markSpanSlow(current.span);
        }
    }

    /** 将当前 Trace 标记为错误，并保留首次获得的异常证据。 */
    public static void markError(String reason, Throwable failure) {
        markError(reason, BoundedThrowableFormatter.capture(failure));
    }

    /** 复用操作结束时已截取的异常摘要，避免再次格式化异常堆栈。 */
    public static void markError(String reason, BoundedThrowableFormatter.FailureEvidence failure) {
        ExecutionFrame current = currentFrame();
        if (current == null) return;
        current.trace.markError(reason, failure);
        current.trace.markSpanError(current.span, failure);
    }

    /**
     * 创建由外部生命周期结束的子节点，例如事务完成回调。
     * 调用返回的 DetachedSpan.close() 结束节点；没有活动 Trace 时返回空操作对象。
     */
    public static DetachedSpan startDetachedSpan(String name, SpanKind kind, String tag) {
        ExecutionFrame current = currentFrame();
        if (current == null) return DetachedSpan.NOOP;
        return DetachedSpan.of(current.trace, addChild(current, name, kind, tag));
    }

    /** Starts a synchronous child span that remains current until its scope closes. */
    public static ScopedSpan startScopedSpan(String name, SpanKind kind, String tag) {
        ExecutionFrame current = currentFrame();
        if (current == null) return ScopedSpan.NOOP;
        Span span = addChild(current, name, kind, tag);
        // A span dropped by depth or node limits still gets its own frame so close() restores the right position.
        ExecutionFrame installed = span == null
                ? new ExecutionFrame(current.trace, current.span, current.depth)
                : new ExecutionFrame(current.trace, span, current.depth + 1);
        CURRENT_FRAME.set(installed);
        return new ScopedSpan(current.trace, Thread.currentThread().getId(), span, current, installed);
    }

    /** Where to record request data on the current span, or {@code null} when the current trace is not captured. */
    public static CaptureTarget currentCaptureTarget() {
        ExecutionFrame current = CURRENT_FRAME.get();
        return current == null ? null : CaptureTarget.of(current.trace, current.span);
    }

    /** Starts an exact, user-declared span and retains successful traces that contain it. */
    public static ScopedSpan startExplicitScopedSpan(String name, SpanKind kind, String tag) {
        TraceTree trace = currentTrace();
        if (trace == null) return ScopedSpan.NOOP;
        trace.requireExplicitTree();
        return startScopedSpan(name, kind, tag);
    }

    public static void stopRoot(long slowThresholdMs) {
        stopRoot(slowThresholdMs, null, completion -> { });
    }

    public static void stopRoot(long slowThresholdMs, Throwable throwable, Consumer<TraceCompletion> completion) {
        TraceTree trace = currentTrace();
        if (trace != null) completeRoot(trace, slowThresholdMs, throwable, completion);
    }

    public static void clear() {
        CURRENT_FRAME.remove();
    }

    /** 返回当前节点的 Span ID；没有活动 Trace 时返回空字符串。 */
    public static String currentSpanId() {
        ExecutionFrame current = currentFrame();
        return current == null ? "" : current.span.spanId();
    }

    static void completeRoot(TraceTree trace, long slowThresholdMs, Throwable throwable,
                             Consumer<TraceCompletion> completion) {
        try {
            TraceCompletion result;
            TraceTree.CapturedSnapshot captured;
            synchronized (trace) {
                if (trace.completed()) return;
                long costMs = trace.completeRoot(slowThresholdMs, throwable);
                boolean captureTree = trace.explicitTreeRequired() || trace.slow() || trace.error();
                result = new TraceCompletion(captureTree ? trace.renderTree() : "",
                        captureTree ? trace.snapshotNodes() : List.of(), costMs, trace.slow(), trace.error(),
                        trace.diagnosticReasons(), trace.errorType(), trace.errorMessage(), trace.errorStackTrace());
                captured = trace.takeCapture();
            }
            String traceId = TraceIdContext.currentTraceId();
            completion.accept(result);
            if (captured != null) deliverCapture(captured, result);
            TraceDiagnosticLog.summarize(result, slowThresholdMs, throwable, traceId);
        } finally {
            ExecutionFrame current = CURRENT_FRAME.get();
            if (current != null && current.trace == trace) CURRENT_FRAME.remove();
            CaptureThreadState.forgetStatement();
        }
    }

    private static void deliverCapture(TraceTree.CapturedSnapshot captured, TraceCompletion result) {
        try {
            captured.sink().completed(result, captured.nodes());
        } catch (RuntimeException | LinkageError ignored) {
            // 抓取结果保存失败不能影响请求完成。
        }
    }

    /** Restores {@code previous} only if {@code installed} is still the current frame of this thread. */
    static void restoreFrame(ExecutionFrame installed, ExecutionFrame previous) {
        if (CURRENT_FRAME.get() == installed) CURRENT_FRAME.set(previous);
    }

    private static TraceTree currentTrace() {
        ExecutionFrame context = currentFrame();
        return context == null ? null : context.trace;
    }

    private static ExecutionFrame currentFrame() {
        ExecutionFrame context = CURRENT_FRAME.get();
        if (context != null && context.trace.completed()) {
            // 只清理由创建线程拥有的 ID；异步任务的作用域会在结束时自行恢复其 MDC。
            String ownedTraceId = context.trace.ownedTraceIdForCurrentThread();
            if (!ownedTraceId.isBlank() && ownedTraceId.equals(TraceIdContext.currentTraceId())) {
                TraceIds.clear();
            }
            CURRENT_FRAME.remove();
            return null;
        }
        return context;
    }

    private static Span addChild(ExecutionFrame parent, String name, SpanKind kind, String tag) {
        return parent.trace.addChild(parent.span, parent.depth, name, kind, tag);
    }

    /** Immutable position in a trace tree. Frames are compared by identity when a scoped span closes. */
    record ExecutionFrame(TraceTree trace, Span span, int depth) {
    }
}
