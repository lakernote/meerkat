package io.github.lakernote.meerkat.trace.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Rate-limited, single-line-safe log summaries for slow and failed root traces. */
final class TraceDiagnosticLog {
    private static final Logger log = LoggerFactory.getLogger(TraceContext.class);
    static final int MAX_LOGS_PER_WINDOW = 20;
    static final long LOG_WINDOW_NANOS = TimeUnit.MINUTES.toNanos(1);
    private static final int MAX_FIELD_CHARS = 256;
    /** 错误和慢调用使用独立日志额度，避免慢调用风暴挤掉所有错误摘要。 */
    private static final Limiter ERROR_LIMITER = new Limiter(MAX_LOGS_PER_WINDOW, LOG_WINDOW_NANOS);
    private static final Limiter SLOW_LIMITER = new Limiter(MAX_LOGS_PER_WINDOW, LOG_WINDOW_NANOS);

    private TraceDiagnosticLog() {
    }

    /**
     * Logs one short header plus the call tree, for example
     * {@code Slow trace 345ms > 250ms [SLOW_JOB] traceId=...}; zero counters are omitted.
     */
    static void summarize(TraceCompletion result, long slowThresholdMs, Throwable throwable, String traceId) {
        // A trace can be marked failed (SQL, Redis, HTTP status) without the root throwing.
        if (throwable != null || result.error()) {
            long suppressed = ERROR_LIMITER.tryAcquire(System.nanoTime());
            if (suppressed < 0) return;
            StringBuilder header = new StringBuilder(128).append("Trace error ").append(result.costMs()).append("ms ");
            appendOneLine(header, throwable != null ? throwable.getClass().getName() : oneLine(result.errorType()));
            log.error("{}\n{}", finish(header, result, traceId, suppressed), result.tree());
        } else if (slowThresholdMs > 0 && result.costMs() >= slowThresholdMs) {
            long suppressed = SLOW_LIMITER.tryAcquire(System.nanoTime());
            if (suppressed < 0) return;
            StringBuilder header = new StringBuilder(128).append("Slow trace ").append(result.costMs())
                    .append("ms > ").append(slowThresholdMs).append("ms");
            log.warn("{}\n{}", finish(header, result, traceId, suppressed), result.tree());
        }
    }

    private static String finish(StringBuilder header, TraceCompletion result, String traceId, long suppressed) {
        String reasons = summarizeReasons(result.reasons());
        if (!reasons.isEmpty()) header.append(" [").append(reasons).append(']');
        header.append(" traceId=").append(oneLine(traceId));
        int unfinished = countUnfinishedSpans(result.nodes());
        if (unfinished > 0) header.append(" unfinishedSpans=").append(unfinished);
        if (suppressed > 0) header.append(" suppressedLastMinute=").append(suppressed);
        return header.toString();
    }

    private static int countUnfinishedSpans(List<TraceNode> nodes) {
        int count = 0;
        for (TraceNode node : nodes) {
            if (node.unfinishedAtTraceEnd()) count++;
            count += countUnfinishedSpans(node.children());
        }
        return count;
    }

    private static String oneLine(String value) {
        if (value == null || value.isBlank()) return "-";
        StringBuilder result = new StringBuilder(Math.min(value.length(), MAX_FIELD_CHARS));
        appendOneLine(result, value);
        return result.toString();
    }

    private static String summarizeReasons(List<String> reasons) {
        StringBuilder result = new StringBuilder(64);
        for (String reason : reasons) {
            if (reason == null || reason.isBlank()) continue;
            if (result.length() >= MAX_FIELD_CHARS) break;
            if (!result.isEmpty()) result.append(',');
            appendOneLine(result, reason);
        }
        return result.toString();
    }

    private static void appendOneLine(StringBuilder result, String value) {
        for (int index = 0; index < value.length() && result.length() < MAX_FIELD_CHARS; index++) {
            char character = value.charAt(index);
            result.append(character == '\r' || character == '\n' ? ' ' : character);
        }
    }

    /**
     * 无锁固定时间窗限流器：低 16 位保存已输出数量，高位保存单调递增的时间窗编号。
     * 达到上限后直接跳过日志格式化，避免错误风暴额外放大 CPU 和日志 I/O。
     */
    static final class Limiter {
        private static final long COUNT_MASK = 0xFFFFL;
        private final int limit;
        private final long windowNanos;
        private final AtomicLong windowAndCount = new AtomicLong();
        private final AtomicLong suppressed = new AtomicLong();

        Limiter(int limit, long windowNanos) {
            if (limit <= 0 || limit > COUNT_MASK) throw new IllegalArgumentException("invalid limit");
            if (windowNanos <= 0) throw new IllegalArgumentException("invalid window");
            this.limit = limit;
            this.windowNanos = windowNanos;
        }

        /** 返回 -1 表示本次被限流；否则返回上一时间窗内被抑制的日志数量。 */
        long tryAcquire(long nowNanos) {
            long window = Math.floorDiv(nowNanos, windowNanos);
            while (true) {
                long current = windowAndCount.get();
                long currentWindow = current >> 16;
                if (current == 0 || window > currentWindow) {
                    long next = (window << 16) | 1L;
                    if (windowAndCount.compareAndSet(current, next)) return suppressed.getAndSet(0);
                    continue;
                }
                // 调用线程跨时间窗暂停后恢复时，不能让共享窗口倒退。
                if (window < currentWindow || (current & COUNT_MASK) >= limit) {
                    suppressed.incrementAndGet();
                    return -1;
                }
                if (windowAndCount.compareAndSet(current, current + 1)) return 0;
            }
        }
    }
}
