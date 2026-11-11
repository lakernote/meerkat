package io.github.lakernote.meerkat.diagnostics.internal;

import io.github.lakernote.meerkat.trace.internal.ActiveTraces;
import io.github.lakernote.meerkat.trace.internal.ThreadResourceSnapshot;

import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.MonitorInfo;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * On-demand views of what the JVM is doing right now: running entry requests, threads with CPU usage, deadlocks and
 * a plain-text thread dump. Nothing is sampled in the background and nothing is written to disk.
 */
public final class LiveDiagnostics {
    static final int MAX_REQUESTS = 200;
    static final int STACK_DEPTH = 64;
    public static final Duration MAX_SAMPLE = Duration.ofSeconds(5);
    private final ThreadMXBean threads;
    private final RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
    /** Thread sampling holds a request for up to {@link #MAX_SAMPLE}; one at a time keeps refresh storms cheap. */
    private final ReentrantLock samplingLock = new ReentrantLock();
    /** A full dump with lock information pauses the JVM at a safepoint; allow one at a time and at most one per interval. */
    static final Duration DUMP_INTERVAL = Duration.ofSeconds(5);
    private final ReentrantLock dumpLock = new ReentrantLock();
    private long lastDumpNanos;
    private boolean dumped;

    public LiveDiagnostics() {
        this(ManagementFactory.getThreadMXBean());
    }

    LiveDiagnostics(ThreadMXBean threads) {
        this.threads = threads;
    }

    /** Running HTTP, scheduled-job and Kafka-consumer entries, longest-running first, with their thread stacks. */
    public ActiveRequests activeRequests() {
        List<ActiveTraces.ActiveTrace> running = ActiveTraces.snapshot();
        List<ActiveTraces.ActiveTrace> shown = running.subList(0, Math.min(MAX_REQUESTS, running.size()));
        Map<Long, ThreadInfo> infos = threadInfos(shown.stream()
                .filter(trace -> !trace.detached()).mapToLong(ActiveTraces.ActiveTrace::threadId).distinct().toArray());
        Instant now = Instant.now();
        List<ActiveRequest> items = new ArrayList<>(shown.size());
        for (ActiveTraces.ActiveTrace trace : shown) {
            ThreadInfo info = trace.detached() ? null : infos.get(trace.threadId());
            long cpuMs = -1;
            long allocated = -1;
            if (info != null && trace.resourcesAtStart() != null) {
                ThreadResourceSnapshot current = ThreadResourceSnapshot.capture(trace.threadId());
                cpuMs = current.cpuTimeMsSince(trace.resourcesAtStart());
                allocated = current.allocatedBytesSince(trace.resourcesAtStart());
            }
            ActiveTraces.Step step = trace.currentStep();
            items.add(new ActiveRequest(trace.traceId(), trace.source(), trace.name(), trace.tag(),
                    now.minusMillis(trace.elapsedMs()), trace.elapsedMs(), trace.threadId(), trace.threadName(),
                    trace.detached(), cpuMs, allocated, trace.spanCount(),
                    step == null ? null : new CurrentStep(step.kind(), step.name(), step.tag(), step.elapsedMs()),
                    info == null ? null : info.getThreadState().name(),
                    info == null ? null : lockName(info.getLockInfo()),
                    info == null ? null : info.getLockOwnerName(),
                    info == null ? List.of() : stack(info, STACK_DEPTH)));
        }
        return new ActiveRequests(now, running.size(), items);
    }

    /**
     * Samples every platform thread's CPU time over {@code sample} (clamped to 0–5s) and returns all threads with
     * their stacks, highest CPU first. Returns {@code null} when another sample is already running.
     */
    public ThreadReport threads(Duration sample) throws InterruptedException {
        long sampleMillis = Math.max(0, Math.min(MAX_SAMPLE.toMillis(), sample == null ? 0 : sample.toMillis()));
        if (!samplingLock.tryLock()) return null;
        try {
            boolean cpuSupported = cpuTimeEnabled();
            long[] ids = threads.getAllThreadIds();
            Map<Long, Long> before = cpuSupported ? cpuTimes(ids) : Map.of();
            long startedAt = System.nanoTime();
            if (sampleMillis > 0 && cpuSupported) Thread.sleep(sampleMillis);
            long elapsedNanos = Math.max(1, System.nanoTime() - startedAt);
            ThreadInfo[] infos = threadInfos(ids, STACK_DEPTH);
            Map<Long, Long> after = cpuSupported ? cpuTimes(ids) : Map.of();
            Set<Long> deadlocked = deadlockedIds();
            List<ThreadView> views = new ArrayList<>();
            Map<String, Integer> states = new LinkedHashMap<>();
            for (Thread.State state : Thread.State.values()) states.put(state.name(), 0);
            for (ThreadInfo info : infos) {
                if (info == null) continue;
                long id = info.getThreadId();
                Long cpuAfter = after.get(id);
                Long cpuBefore = before.get(id);
                double percent = cpuAfter == null || cpuBefore == null || sampleMillis == 0 ? -1
                        : Math.round(Math.max(0, cpuAfter - cpuBefore) * 1000.0 / elapsedNanos) / 10.0;
                states.merge(info.getThreadState().name(), 1, Integer::sum);
                views.add(new ThreadView(id, info.getThreadName(), info.getThreadState().name(), info.isDaemon(),
                        percent, cpuAfter == null ? -1 : cpuAfter / 1_000_000, lockName(info.getLockInfo()),
                        info.getLockOwnerName(), info.getLockOwnerId(), deadlocked.contains(id), stack(info, STACK_DEPTH)));
            }
            views.sort(Comparator.comparingDouble(ThreadView::cpuPercent).reversed()
                    .thenComparing(ThreadView::name, String.CASE_INSENSITIVE_ORDER));
            return new ThreadReport(Instant.now(), cpuSupported ? sampleMillis : 0, cpuSupported, views.size(),
                    threads.getDaemonThreadCount(), threads.getPeakThreadCount(), states,
                    views.stream().filter(ThreadView::deadlocked).map(ThreadView::id).toList(), views);
        } finally {
            samplingLock.unlock();
        }
    }

    /**
     * A jstack-style dump of every platform thread with full stacks and lock information. Returns {@code null} when
     * another dump is running or the previous one finished less than {@link #DUMP_INTERVAL} ago.
     */
    public String threadDump() {
        if (!dumpLock.tryLock()) return null;
        try {
            long now = System.nanoTime();
            if (dumped && now - lastDumpNanos < DUMP_INTERVAL.toNanos()) return null;
            String dump = buildThreadDump();
            lastDumpNanos = System.nanoTime();
            dumped = true;
            return dump;
        } finally {
            dumpLock.unlock();
        }
    }

    private String buildThreadDump() {
        ThreadInfo[] infos = threadInfos(threads.getAllThreadIds(), Integer.MAX_VALUE);
        Set<Long> deadlocked = deadlockedIds();
        StringBuilder out = new StringBuilder(64 * 1024);
        out.append("Full thread dump ").append(System.getProperty("java.vm.name", "")).append(' ')
                .append(System.getProperty("java.vm.version", "")).append('\n')
                .append(Instant.now()).append("  pid=").append(runtime.getPid())
                .append("  threads=").append(infos.length).append("\n\n");
        boolean cpuSupported = cpuTimeEnabled();
        for (ThreadInfo info : infos) {
            if (info == null) continue;
            out.append('"').append(info.getThreadName()).append("\" #").append(info.getThreadId());
            if (info.isDaemon()) out.append(" daemon");
            out.append(" prio=").append(info.getPriority());
            if (cpuSupported) {
                long cpu = threads.getThreadCpuTime(info.getThreadId());
                if (cpu >= 0) out.append(" cpu=").append(cpu / 1_000_000).append("ms");
            }
            out.append('\n').append("   java.lang.Thread.State: ").append(info.getThreadState()).append('\n');
            for (String line : stack(info, Integer.MAX_VALUE)) out.append("\t").append(line).append('\n');
            LockInfo[] synchronizers = info.getLockedSynchronizers();
            if (synchronizers.length > 0) {
                out.append("\n   Locked ownable synchronizers:\n");
                for (LockInfo lock : synchronizers) out.append("\t- <").append(lockName(lock)).append(">\n");
            }
            out.append('\n');
        }
        if (!deadlocked.isEmpty()) {
            out.append("Found ").append(deadlocked.size()).append(" deadlocked threads:\n");
            for (ThreadInfo info : infos) {
                if (info == null || !deadlocked.contains(info.getThreadId())) continue;
                out.append('"').append(info.getThreadName()).append("\" waiting for <")
                        .append(lockName(info.getLockInfo())).append("> held by \"")
                        .append(info.getLockOwnerName()).append("\"\n");
            }
        }
        return out.toString();
    }

    private Map<Long, ThreadInfo> threadInfos(long[] ids) {
        Map<Long, ThreadInfo> byId = new HashMap<>();
        if (ids.length == 0) return byId;
        for (ThreadInfo info : threadInfos(ids, STACK_DEPTH)) {
            if (info != null) byId.put(info.getThreadId(), info);
        }
        return byId;
    }

    private ThreadInfo[] threadInfos(long[] ids, int depth) {
        boolean monitors = threads.isObjectMonitorUsageSupported();
        boolean synchronizers = threads.isSynchronizerUsageSupported();
        return threads.getThreadInfo(ids, monitors, synchronizers, depth);
    }

    private Set<Long> deadlockedIds() {
        long[] ids = threads.isSynchronizerUsageSupported()
                ? threads.findDeadlockedThreads() : threads.findMonitorDeadlockedThreads();
        Set<Long> result = new HashSet<>();
        if (ids != null) for (long id : ids) result.add(id);
        return result;
    }

    private boolean cpuTimeEnabled() {
        try {
            if (!threads.isThreadCpuTimeSupported()) return false;
            if (!threads.isThreadCpuTimeEnabled()) threads.setThreadCpuTimeEnabled(true);
            return threads.isThreadCpuTimeEnabled();
        } catch (RuntimeException unsupported) {
            return false;
        }
    }

    private Map<Long, Long> cpuTimes(long[] ids) {
        Map<Long, Long> times = new HashMap<>(ids.length * 2);
        for (long id : ids) {
            long cpu = threads.getThreadCpuTime(id);
            if (cpu >= 0) times.put(id, cpu);
        }
        return times;
    }

    /** Stack frames in jstack form, including which monitors each frame holds or waits for. */
    static List<String> stack(ThreadInfo info, int depth) {
        StackTraceElement[] frames = info.getStackTrace();
        MonitorInfo[] monitors = info.getLockedMonitors();
        List<String> lines = new ArrayList<>(frames.length + monitors.length + 1);
        for (int i = 0; i < frames.length; i++) {
            lines.add("at " + frames[i]);
            if (i == 0 && info.getLockInfo() != null) {
                String lock = lockName(info.getLockInfo());
                switch (info.getThreadState()) {
                    case BLOCKED -> lines.add("- waiting to lock <" + lock + ">"
                            + (info.getLockOwnerName() == null ? "" : " owned by \"" + info.getLockOwnerName() + "\""));
                    case WAITING, TIMED_WAITING -> lines.add("- parking to wait for <" + lock + ">");
                    default -> { }
                }
            }
            for (MonitorInfo monitor : monitors) {
                if (monitor.getLockedStackDepth() == i) lines.add("- locked <" + lockName(monitor) + ">");
            }
        }
        if (frames.length == depth) lines.add("...");
        return lines;
    }

    /** {@code ClassName@identityHash}, matching {@link LockInfo#toString()}. */
    private static String lockName(LockInfo lock) {
        return lock == null ? null : lock.getClassName() + "@" + Integer.toHexString(lock.getIdentityHashCode());
    }

    public record ActiveRequests(Instant capturedAt, int total, List<ActiveRequest> items) {
    }

    /**
     * A running entry. {@code async} means the entry thread has already returned and the work continues elsewhere,
     * so no stack, thread state or CPU time is reported. {@code cpuTimeMs} and {@code allocatedBytes} are -1 when
     * unknown.
     */
    public record ActiveRequest(String traceId, String source, String name, String path, Instant startedAt,
                                long elapsedMs, long threadId, String threadName, boolean async, long cpuTimeMs,
                                long allocatedBytes, int spanCount, CurrentStep currentStep, String threadState,
                                String lockName, String lockOwnerName, List<String> stack) {
    }

    /** The innermost call still in progress, for example a SQL statement or a remote request. */
    public record CurrentStep(String kind, String name, String detail, long elapsedMs) {
    }

    /** {@code cpuPercent} is the share of one core used during the sample, or -1 when not measured. */
    public record ThreadView(long id, String name, String state, boolean daemon, double cpuPercent, long cpuTimeMs,
                             String lockName, String lockOwnerName, long lockOwnerId, boolean deadlocked,
                             List<String> stack) {
    }

    public record ThreadReport(Instant capturedAt, long sampleMillis, boolean cpuTimeSupported, int threadCount,
                               int daemonCount, int peakThreadCount, Map<String, Integer> states,
                               List<Long> deadlockedThreadIds, List<ThreadView> threads) {
    }
}
