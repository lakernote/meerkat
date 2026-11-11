package io.github.lakernote.meerkat.trace.internal.store;

import io.github.lakernote.meerkat.trace.internal.TraceNode;

import java.time.Instant;
import java.util.List;

/**
 * One completed entry trace (HTTP request, scheduled job or Kafka message) with its call tree. {@code tag} is the
 * entry span's detail, such as the HTTP route or the Kafka topic, partition and offset.
 */
public record TraceRecord(long id, String traceId, String name, String tag, int status,
                          long durationMs, boolean slow, boolean error, List<TraceNode> treeNodes,
                          Instant finishedAt,
                          long threadId, long cpuTimeMs, long allocatedBytes, String sourceType,
                          String errorType, String errorMessage, String threadName, String errorStackTrace,
                          boolean highCpu, boolean highAllocation) {
    public TraceRecord {
        durationMs = Math.max(0, durationMs);
        sourceType = sourceType == null ? "" : sourceType;
        treeNodes = treeNodes == null ? List.of() : List.copyOf(treeNodes);
        errorType = errorType == null ? "" : errorType;
        errorMessage = errorMessage == null ? "" : errorMessage;
        threadName = threadName == null ? "" : threadName;
        errorStackTrace = errorStackTrace == null ? "" : errorStackTrace;
    }

    public static Builder builder() {
        return new Builder();
    }

    TraceRecord withIdentity(long id, Instant finishedAt) {
        return new TraceRecord(id, traceId, name, tag, status, durationMs, slow, error, treeNodes, finishedAt,
                threadId, cpuTimeMs, allocatedBytes, sourceType, errorType, errorMessage, threadName,
                errorStackTrace, highCpu, highAllocation);
    }

    /**
     * Rough heap footprint used to bound the in-memory cache: object headers plus two bytes per character, which
     * errs on the high side for Latin-1 strings.
     */
    long estimatedBytes() {
        long bytes = 256 + chars(traceId) + chars(name) + chars(tag) + chars(sourceType) + chars(errorType)
                + chars(errorMessage) + chars(threadName) + chars(errorStackTrace);
        for (TraceNode node : treeNodes) bytes += estimatedBytes(node);
        return bytes;
    }

    private static long estimatedBytes(TraceNode node) {
        long bytes = 160 + chars(node.name()) + chars(node.tag()) + chars(node.errorType()) + chars(node.threadName());
        for (TraceNode child : node.children()) bytes += estimatedBytes(child);
        return bytes;
    }

    private static long chars(String value) {
        return value == null ? 0 : 2L * value.length();
    }

    /** Names every field so call sites cannot transpose the many adjacent strings, numbers and flags. */
    public static final class Builder {
        private long id;
        private String traceId;
        private String name;
        private String tag;
        private int status;
        private long durationMs;
        private boolean slow;
        private boolean error;
        private List<TraceNode> treeNodes;
        private Instant finishedAt;
        private long threadId = -1;
        private long cpuTimeMs = -1;
        private long allocatedBytes = -1;
        private String sourceType;
        private String errorType;
        private String errorMessage;
        private String threadName;
        private String errorStackTrace;
        private boolean highCpu;
        private boolean highAllocation;

        private Builder() {
        }

        public Builder id(long id) {
            this.id = id;
            return this;
        }

        public Builder traceId(String traceId) {
            this.traceId = traceId;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder tag(String tag) {
            this.tag = tag;
            return this;
        }

        public Builder status(int status) {
            this.status = status;
            return this;
        }

        public Builder durationMs(long durationMs) {
            this.durationMs = durationMs;
            return this;
        }

        public Builder slow(boolean slow) {
            this.slow = slow;
            return this;
        }

        public Builder error(boolean error) {
            this.error = error;
            return this;
        }

        public Builder treeNodes(List<TraceNode> treeNodes) {
            this.treeNodes = treeNodes;
            return this;
        }

        public Builder finishedAt(Instant finishedAt) {
            this.finishedAt = finishedAt;
            return this;
        }

        public Builder threadId(long threadId) {
            this.threadId = threadId;
            return this;
        }

        public Builder cpuTimeMs(long cpuTimeMs) {
            this.cpuTimeMs = cpuTimeMs;
            return this;
        }

        public Builder allocatedBytes(long allocatedBytes) {
            this.allocatedBytes = allocatedBytes;
            return this;
        }

        public Builder sourceType(String sourceType) {
            this.sourceType = sourceType;
            return this;
        }

        public Builder errorType(String errorType) {
            this.errorType = errorType;
            return this;
        }

        public Builder errorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
            return this;
        }

        public Builder threadName(String threadName) {
            this.threadName = threadName;
            return this;
        }

        public Builder errorStackTrace(String errorStackTrace) {
            this.errorStackTrace = errorStackTrace;
            return this;
        }

        public Builder highCpu(boolean highCpu) {
            this.highCpu = highCpu;
            return this;
        }

        public Builder highAllocation(boolean highAllocation) {
            this.highAllocation = highAllocation;
            return this;
        }

        public TraceRecord build() {
            return new TraceRecord(id, traceId, name, tag, status, durationMs, slow, error,
                    treeNodes, finishedAt, threadId, cpuTimeMs, allocatedBytes, sourceType, errorType,
                    errorMessage, threadName, errorStackTrace, highCpu, highAllocation);
        }
    }
}
