package io.github.lakernote.meerkat.capture.internal;

import io.github.lakernote.meerkat.trace.internal.TraceNode;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** One captured request with the full call tree and the data recorded on each node. */
public record CaptureRecord(String id, String sessionId, String traceId, String method, String path, int status,
                            long durationMs, boolean error, Instant finishedAt, String errorType,
                            String errorMessage, String errorStackTrace, List<TraceNode> nodes) {
    public CaptureRecord {
        errorType = errorType == null ? "" : errorType;
        errorMessage = errorMessage == null ? "" : errorMessage;
        errorStackTrace = errorStackTrace == null ? "" : errorStackTrace;
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
    }

    /** Rough heap footprint: object overhead plus two bytes per char, like the trace store's estimate. */
    public long estimatedBytes() {
        long bytes = 256 + chars(id) + chars(sessionId) + chars(traceId) + chars(method) + chars(path)
                + chars(errorType) + chars(errorMessage) + chars(errorStackTrace);
        for (TraceNode node : nodes) bytes += estimatedBytes(node);
        return bytes;
    }

    private static long estimatedBytes(TraceNode node) {
        long bytes = 160 + chars(node.name()) + chars(node.tag()) + chars(node.errorType()) + chars(node.threadName());
        for (Map.Entry<String, String> capture : node.captures().entrySet()) {
            bytes += 64 + chars(capture.getKey()) + chars(capture.getValue());
        }
        for (TraceNode child : node.children()) bytes += estimatedBytes(child);
        return bytes;
    }

    private static long chars(String value) {
        return value == null ? 0 : 2L * value.length();
    }

    public Summary summary() {
        return new Summary(id, sessionId, traceId, method, path, status, durationMs, error, errorType, finishedAt);
    }

    public record Summary(String id, String sessionId, String traceId, String method, String path, int status,
                          long durationMs, boolean error, String errorType, Instant finishedAt) { }
}
