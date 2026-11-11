package io.github.lakernote.meerkat.trace.internal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Registry of entry traces that are still running, read on demand by the live diagnostics page. Registration is a
 * single concurrent-map insert per entry; the map is capped so a leaked root can never grow it without bound, and
 * roots running longer than {@link #MAX_AGE_NANOS} are taken for leaked (never completed) and dropped, so they neither
 * retain their call trees forever nor fill the cap.
 */
public final class ActiveTraces {
    static final int MAX_TRACKED = 10_000;
    static final long MAX_AGE_NANOS = TimeUnit.HOURS.toNanos(1);
    private static final long SWEEP_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(1);
    private static final long FULL_SWEEP_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(1);
    /** Running roots and when they registered. */
    private static final ConcurrentHashMap<TraceTree, Long> ACTIVE = new ConcurrentHashMap<>();
    private static final AtomicLong lastSweepNanos = new AtomicLong(System.nanoTime());

    private ActiveTraces() {
    }

    static boolean register(TraceTree trace) {
        return register(trace, System.nanoTime());
    }

    static boolean register(TraceTree trace, long nowNanos) {
        boolean full = ACTIVE.size() >= MAX_TRACKED;
        long last = lastSweepNanos.get();
        if (nowNanos - last >= (full ? FULL_SWEEP_INTERVAL_NANOS : SWEEP_INTERVAL_NANOS)
                && lastSweepNanos.compareAndSet(last, nowNanos)) {
            ACTIVE.values().removeIf(registeredAt -> nowNanos - registeredAt > MAX_AGE_NANOS);
        }
        if (ACTIVE.size() >= MAX_TRACKED) return false;
        return ACTIVE.putIfAbsent(trace, nowNanos) == null;
    }

    static void unregister(TraceTree trace) {
        ACTIVE.remove(trace);
    }

    /** Returns the running entries, longest-running first. */
    public static List<ActiveTrace> snapshot() {
        List<ActiveTrace> running = new ArrayList<>();
        for (TraceTree trace : ACTIVE.keySet()) {
            ActiveTrace view = trace.activeView();
            if (view != null) running.add(view);
        }
        running.sort(Comparator.comparingLong(ActiveTrace::elapsedMs).reversed());
        return running;
    }

    /**
     * A running entry. {@code detached} is true once the entry thread has returned while the work continues
     * asynchronously, so the entry thread's stack no longer belongs to this request.
     */
    public record ActiveTrace(String traceId, String source, String name, String tag, long elapsedMs,
                              long threadId, String threadName, boolean detached,
                              ThreadResourceSnapshot resourcesAtStart, int spanCount, Step currentStep) {
    }

    /** The deepest span that has started but not finished, such as a SQL statement or remote call in progress. */
    public record Step(String kind, String name, String tag, long elapsedMs) {
    }
}
