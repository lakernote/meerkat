package io.github.lakernote.meerkat.trace.internal;

import io.github.lakernote.meerkat.trace.SpanKind;

import java.util.function.Consumer;

/** A root-tree reference retained by an Observation so the request can be finished on another thread. */
public final class RootTraceHandle {
    private final TraceTree trace;

    RootTraceHandle(TraceTree trace) {
        this.trace = trace;
    }

    TraceTree trace() {
        return trace;
    }

    public void updateRootIdentity(String name, String tag) {
        trace.updateRootIdentity(name, tag);
    }

    public void markSlow(String reason) {
        trace.markSlow(reason);
    }

    public void markError(String reason, BoundedThrowableFormatter.FailureEvidence failure) {
        trace.markError(reason, failure);
    }

    /** Ensures a retained diagnostic trace has its bounded tree even when it is not slow or failed. */
    public void requireTree() {
        trace.requireExplicitTree();
    }

    /**
     * Records request data on every span of this trace until it completes, then hands the captured snapshot to
     * {@code sink}. Retained traces are unaffected: they never include captured data.
     */
    public void beginCapture(CaptureSink sink, CaptureLimits limits) {
        trace.beginCapture(sink, limits);
    }

    /** Where to record request data on the root span, or {@code null} when not capturing. */
    public CaptureTarget captureTarget() {
        return CaptureTarget.of(trace, trace.rootSpan());
    }

    /** Shows this running entry on the live diagnostics page until it completes. */
    public void trackActive(String traceId, String source, ThreadResourceSnapshot resourcesAtStart) {
        trace.track(traceId, source, resourcesAtStart);
    }

    /** Records that the entry thread has returned while the request continues elsewhere. */
    public void markDetached() {
        trace.markDetached();
    }

    public void rememberOwnedTraceId(String traceId) {
        trace.rememberOwnedTraceId(traceId);
    }

    /** Adds a dependency span when a framework Observation resumes on another thread. */
    public DetachedSpan startDetachedSpan(String name, SpanKind kind, String tag) {
        return DetachedSpan.of(trace, trace.addChild(trace.rootSpan(), 1, name, kind, tag));
    }

    public void stop(long slowThresholdMs, Throwable failure, Consumer<TraceCompletion> completion) {
        TraceContext.completeRoot(trace, slowThresholdMs, failure, completion);
    }

    public void discard() {
        trace.discard();
        TraceContext.detach(this);
    }
}
