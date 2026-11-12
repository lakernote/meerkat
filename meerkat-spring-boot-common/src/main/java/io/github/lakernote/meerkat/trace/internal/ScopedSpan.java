package io.github.lakernote.meerkat.trace.internal;

import java.util.concurrent.atomic.AtomicBoolean;

/** A synchronous child span that stays current on its thread until closed. */
public final class ScopedSpan implements SpanHandle {
    static final ScopedSpan NOOP = new ScopedSpan(null, -1, null, null, null);
    private final TraceTree trace;
    private final long threadId;
    private final Span span;
    private final TraceContext.ExecutionFrame previous;
    private final TraceContext.ExecutionFrame installed;
    private final AtomicBoolean stopped = new AtomicBoolean();

    ScopedSpan(TraceTree trace, long threadId, Span span,
               TraceContext.ExecutionFrame previous, TraceContext.ExecutionFrame installed) {
        this.trace = trace;
        this.threadId = threadId;
        this.span = span;
        this.previous = previous;
        this.installed = installed;
    }

    @Override
    public String spanId() {
        return span == null ? "" : span.spanId();
    }

    @Override
    public String parentSpanId() {
        return span == null ? "" : span.parentSpanId();
    }

    @Override
    public void markError(BoundedThrowableFormatter.FailureEvidence failure) {
        if (trace != null && span != null) trace.markSpanError(span, failure);
    }

    /** Marks both this span and its whole trace as failed, keeping the first captured evidence. */
    public void markTraceError(String reason, Throwable failure) {
        markTraceError(reason, BoundedThrowableFormatter.capture(failure));
    }

    /** Same as {@link #markTraceError(String, Throwable)}; safe to call from any thread. */
    public void markTraceError(String reason, BoundedThrowableFormatter.FailureEvidence evidence) {
        if (trace == null) return;
        trace.markError(reason, evidence);
        if (span != null) trace.markSpanError(span, evidence);
    }

    @Override
    public void markSlow() {
        if (trace != null && span != null) trace.markSpanSlow(span);
    }

    @Override
    public CaptureTarget captureTarget() {
        return CaptureTarget.of(trace, span);
    }

    @Override
    public void close() {
        if (!stopped.compareAndSet(false, true) || trace == null) return;
        if (span != null) trace.stopSpan(span);
        if (threadId == Thread.currentThread().getId()) TraceContext.restoreFrame(installed, previous);
        else installed.closedTo = previous;
    }
}
