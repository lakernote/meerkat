package io.github.lakernote.meerkat.trace.internal;

import io.github.lakernote.meerkat.trace.SpanKind;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class TraceTree {
    static final int DEFAULT_MAX_DEPTH = 8;
    static final int DEFAULT_MAX_NODES = 512;
    /** 单棵调用树的文本上限；与节点数上限分开，防止超长名称或标签在渲染时额外占用大量内存。 */
    static final int MAX_RENDER_CHARS = 8_000;
    private static final String TRUNCATED_REASON = "TRACE_TRUNCATED";
    private static final String OUTPUT_TRUNCATED_MARKER = System.lineSeparator()
            + "[Trace] TRACE_OUTPUT_TRUNCATED maxChars=" + MAX_RENDER_CHARS;
    private final Span root;
    private long nextSpanId = 1;
    private final int maxDepth;
    private final int maxNodes;
    private final long ownerThreadId = Thread.currentThread().getId();
    private final String rootThreadName = Thread.currentThread().getName();
    /** 正常 Trace 完成后会丢弃；只有慢、错或截断时才分配事件原因集合。 */
    private Set<String> diagnosticReasons;
    private boolean slow;
    private boolean error;
    private boolean explicitTreeRequired;
    private String errorType = "";
    private String errorMessage = "";
    private String errorStackTrace = "";
    private int recordedNodes = 1;
    private long omittedSpans;
    private boolean completed;
    private String ownedTraceId = "";
    private boolean tracked;
    private String liveTraceId = "";
    private String liveSource = "";
    private ThreadResourceSnapshot resourcesAtStart;
    private boolean detached;
    /** Set while traffic capture records this trace; cleared when the root completes. */
    private volatile CaptureSink captureSink;
    private volatile CaptureLimits captureLimits;

    TraceTree(String rootName, SpanKind rootKind, String rootTag) {
        this(rootName, rootKind, rootTag, DEFAULT_MAX_DEPTH, DEFAULT_MAX_NODES);
    }

    TraceTree(String rootName, SpanKind rootKind, String rootTag, int maxDepth, int maxNodes) {
        this.root = new Span("1", rootName, rootKind, rootTag, null);
        this.maxDepth = Math.max(1, maxDepth);
        this.maxNodes = Math.max(1, maxNodes);
    }

    synchronized Span rootSpan() {
        return root;
    }

    synchronized Span addChild(Span parent, int parentDepth, String name, SpanKind kind, String tag) {
        if (completed) return null;
        int childDepth = parentDepth + 1;
        if (parent == null || childDepth > maxDepth) {
            recordOmittedSpan();
            return null;
        }
        if (recordedNodes >= maxNodes) {
            recordOmittedSpan();
            return null;
        }
        Span child = new Span(nextSpanId(), name, kind, tag, parent);
        parent.addChild(child);
        recordedNodes++;
        return child;
    }

    /** Starts recording request data on this trace's spans until the root completes. */
    synchronized void beginCapture(CaptureSink sink, CaptureLimits limits) {
        if (completed || sink == null || limits == null) return;
        captureLimits = limits;
        captureSink = sink;
    }

    CaptureLimits captureLimits() {
        return captureLimits;
    }

    /** Lock-free so integrations can check it on every call without contention. */
    boolean capturing() {
        return captureSink != null;
    }

    synchronized void captureSpan(Span span, String key, Object value) {
        if (completed || captureSink == null || span == null) return;
        span.capture(key, value);
    }

    /**
     * Returns the capture sink and a snapshot including captured data, then stops capturing. Called once under the
     * tree lock as the root completes; {@code null} when the trace was not captured.
     */
    synchronized CapturedSnapshot takeCapture() {
        CaptureSink sink = captureSink;
        if (sink == null) return null;
        captureSink = null;
        CaptureBudget budget = new CaptureBudget(captureLimits.maxValueChars(), captureLimits.maxTraceChars());
        return new CapturedSnapshot(sink, List.of(root.snapshotNode(root.startNanos(), budget)));
    }

    record CapturedSnapshot(CaptureSink sink, List<TraceNode> nodes) { }

    synchronized void requireExplicitTree() {
        if (completed) return;
        explicitTreeRequired = true;
    }

    synchronized boolean explicitTreeRequired() {
        return explicitTreeRequired;
    }

    synchronized void updateRootIdentity(String name, String tag) {
        if (completed) return;
        root.updateIdentity(name, tag);
    }

    private String nextSpanId() {
        return Long.toHexString(++nextSpanId);
    }

    synchronized void stopSpan(Span span) {
        if (completed) return;
        if (span == null || span == root) return;
        span.stop();
    }

    private long stopRoot() {
        root.stopOpenDescendants();
        root.stop();
        return root.costMs();
    }

    synchronized long completeRoot(long slowThresholdMs, Throwable throwable) {
        if (completed) return root.costMs();
        untrack();
        long costMs = stopRoot();
        if (slowThresholdMs > 0 && costMs >= slowThresholdMs && !slow) {
            markSlow("SLOW_ROOT");
        }
        if (throwable != null && !error) markError("ROOT_ERROR", throwable);
        completed = true;
        return costMs;
    }

    synchronized void discard() {
        if (completed) return;
        captureSink = null;
        untrack();
        stopRoot();
        completed = true;
    }

    synchronized void markSlow(String reason) {
        if (completed) return;
        slow = true;
        root.markSlow();
        addReason(reason);
    }

    synchronized void markError(String reason, Throwable failure) {
        markError(reason, BoundedThrowableFormatter.capture(failure));
    }

    synchronized void markError(String reason, BoundedThrowableFormatter.FailureEvidence failure) {
        if (completed) return;
        error = true;
        root.markError(failure);
        addReason(reason);
        if (failure == null || !failure.present()) return;
        // A transaction callback may only know that rollback happened. Prefer a later real exception with its stack.
        if (!errorType.isBlank() && (!errorStackTrace.isBlank() || failure.stackTrace().isBlank())) return;
        applyFailure(failure);
    }

    synchronized void markSpanError(Span span, BoundedThrowableFormatter.FailureEvidence failure) {
        if (completed || span == null) return;
        span.markError(failure);
    }

    synchronized void markSpanSlow(Span span) {
        if (completed || span == null) return;
        span.markSlow();
    }

    private void applyFailure(BoundedThrowableFormatter.FailureEvidence failure) {
        errorType = failure.type();
        errorMessage = failure.message();
        errorStackTrace = failure.stackTrace();
    }

    synchronized boolean slow() {
        return slow;
    }

    synchronized boolean error() {
        return error;
    }

    synchronized List<String> diagnosticReasons() {
        return diagnosticReasons == null ? List.of() : List.copyOf(diagnosticReasons);
    }

    synchronized String errorType() {
        return errorType;
    }

    synchronized String errorMessage() {
        return errorMessage;
    }

    synchronized String errorStackTrace() {
        return errorStackTrace;
    }

    synchronized boolean completed() {
        return completed;
    }

    synchronized void rememberOwnedTraceId(String traceId) {
        if (!completed && ownerThreadId == Thread.currentThread().getId()) {
            ownedTraceId = traceId == null ? "" : traceId;
        }
    }

    synchronized String ownedTraceIdForCurrentThread() {
        return ownerThreadId == Thread.currentThread().getId() ? ownedTraceId : "";
    }

    /** Lists this running root on the live diagnostics page until it completes or is discarded. */
    synchronized void track(String traceId, String source, ThreadResourceSnapshot resources) {
        if (completed || tracked) return;
        liveTraceId = traceId == null ? "" : traceId;
        liveSource = source == null ? "" : source;
        resourcesAtStart = resources;
        tracked = ActiveTraces.register(this);
    }

    synchronized void markDetached() {
        detached = true;
    }

    private void untrack() {
        if (!tracked) return;
        tracked = false;
        ActiveTraces.unregister(this);
    }

    /** Returns a point-in-time view of this running root, or {@code null} once it has completed. */
    synchronized ActiveTraces.ActiveTrace activeView() {
        if (completed) return null;
        Span step = root.deepestOpenDescendant();
        long now = System.nanoTime();
        return new ActiveTraces.ActiveTrace(liveTraceId, liveSource, root.name(), root.tag(),
                Math.max(0, (now - root.startNanos()) / 1_000_000), ownerThreadId, rootThreadName, detached,
                resourcesAtStart, recordedNodes,
                step == null ? null : new ActiveTraces.Step(step.kind().displayName(), step.name(), step.tag(),
                        Math.max(0, (now - step.startNanos()) / 1_000_000)));
    }

    private void addReason(String reason) {
        if (reason == null || reason.isBlank()) return;
        if (diagnosticReasons == null) diagnosticReasons = new LinkedHashSet<>();
        diagnosticReasons.add(reason);
    }

    private void recordOmittedSpan() {
        if (omittedSpans < Long.MAX_VALUE) omittedSpans++;
        addReason(TRUNCATED_REASON);
    }

    synchronized String renderTree() {
        RenderBuffer builder = new RenderBuffer(MAX_RENDER_CHARS, OUTPUT_TRUNCATED_MARKER);
        root.appendTree(builder, "", true);
        if (omittedSpans > 0 && !builder.truncated()) {
            builder.append("[Trace] ").append(TRUNCATED_REASON)
                    .append(" omitted=").append(omittedSpans)
                    .append(" maxNodes=").append(maxNodes)
                    .append(System.lineSeparator());
        }
        return builder.value();
    }

    synchronized List<TraceNode> snapshotNodes() {
        return List.of(root.snapshotNode(root.startNanos()));
    }

    /** Bounded append target shared with {@link Span}; once truncated, all later tree traversal stops. */
    static final class RenderBuffer {
        private final int maxChars;
        private final String marker;
        private final StringBuilder value;
        private boolean truncated;

        private RenderBuffer(int maxChars, String marker) {
            this.maxChars = Math.max(0, maxChars);
            this.marker = marker == null ? "" : marker;
            this.value = new StringBuilder(Math.min(this.maxChars, 512));
        }

        RenderBuffer append(String text) {
            if (truncated || text == null || text.isEmpty()) return this;
            int remaining = maxChars - value.length();
            if (text.length() <= remaining) {
                value.append(text);
                return this;
            }
            truncateWithMarker(text);
            return this;
        }

        RenderBuffer append(char character) {
            if (truncated) return this;
            if (value.length() < maxChars) {
                value.append(character);
            } else {
                truncateWithMarker("");
            }
            return this;
        }

        RenderBuffer append(long number) {
            return append(Long.toString(number));
        }

        boolean truncated() {
            return truncated;
        }

        String value() {
            return value.toString();
        }

        private void truncateWithMarker(String overflow) {
            int markerLength = Math.min(marker.length(), maxChars);
            int contentLimit = maxChars - markerLength;
            if (value.length() > contentLimit) value.setLength(contentLimit);
            int copyLength = Math.min(overflow.length(), contentLimit - value.length());
            if (copyLength > 0) value.append(overflow, 0, copyLength);
            if (markerLength > 0) {
                value.append(marker, marker.length() - markerLength, marker.length());
            }
            truncated = true;
        }
    }
}
