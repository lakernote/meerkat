package io.github.lakernote.meerkat.diagnostics.internal;

import io.github.lakernote.meerkat.trace.SpanKind;
import io.github.lakernote.meerkat.trace.internal.RootTraceHandle;
import io.github.lakernote.meerkat.trace.internal.ScopedSpan;
import io.github.lakernote.meerkat.trace.internal.ThreadResourceSnapshot;
import io.github.lakernote.meerkat.trace.internal.TraceContext;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;

class LiveDiagnosticsTest {
    private final LiveDiagnostics diagnostics = new LiveDiagnostics();

    @Test
    void showsARunningRequestWithItsCurrentStepThreadStateAndStack() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            TraceContext.startRoot("GET /reports", SpanKind.HTTP, "/reports");
            RootTraceHandle handle = TraceContext.currentRootTraceHandle();
            handle.trackActive("live-request", "HTTP", ThreadResourceSnapshot.capture(Thread.currentThread().getId()));
            try (ScopedSpan ignored = TraceContext.startScopedSpan("ReportService.build", SpanKind.SERVICE, "")) {
                started.countDown();
                awaitQuietly(release);
            }
            TraceContext.stopRoot(0, "test");
        }, "live-request-worker");
        worker.start();
        try {
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            LiveDiagnostics.ActiveRequest request = awaitRequest("live-request", "TIMED_WAITING");

            assertThat(request.source()).isEqualTo("HTTP");
            assertThat(request.name()).isEqualTo("GET /reports");
            assertThat(request.path()).isEqualTo("/reports");
            assertThat(request.threadName()).isEqualTo("live-request-worker");
            assertThat(request.async()).isFalse();
            assertThat(request.cpuTimeMs()).isGreaterThanOrEqualTo(0);
            assertThat(request.currentStep().name()).isEqualTo("ReportService.build");
            assertThat(request.stack()).anyMatch(line -> line.contains("LiveDiagnosticsTest.awaitQuietly"));
            assertThat(request.startedAt()).isBeforeOrEqualTo(java.time.Instant.now());
        } finally {
            release.countDown();
            worker.join(5000);
        }
        assertThat(diagnostics.activeRequests().items()).noneMatch(item -> item.traceId().equals("live-request"));
    }

    @Test
    void reportsDetachedRequestsWithoutAThreadStack() {
        TraceContext.startRoot("GET /async", SpanKind.HTTP);
        RootTraceHandle handle = TraceContext.currentRootTraceHandle();
        handle.trackActive("live-async", "HTTP", ThreadResourceSnapshot.capture(Thread.currentThread().getId()));
        TraceContext.detach(handle);
        try {
            LiveDiagnostics.ActiveRequest request = diagnostics.activeRequests().items().stream()
                    .filter(item -> item.traceId().equals("live-async")).findFirst().orElseThrow();
            assertThat(request.async()).isTrue();
            assertThat(request.stack()).isEmpty();
            assertThat(request.threadState()).isNull();
            assertThat(request.cpuTimeMs()).isEqualTo(-1);
        } finally {
            handle.stop(0, () -> "test", null, completion -> { });
        }
    }

    @Test
    void ranksABusyThreadByCpuAndDetectsDeadlocks() throws Exception {
        AtomicBoolean spinning = new AtomicBoolean(true);
        Thread busy = new Thread(() -> {
            long value = 0;
            while (spinning.get()) value += System.nanoTime() % 7;
            if (value == 42) System.out.print("");
        }, "live-busy-thread");
        ReentrantLock first = new ReentrantLock();
        ReentrantLock second = new ReentrantLock();
        CountDownLatch bothLocked = new CountDownLatch(2);
        Thread a = deadlockThread("live-deadlock-a", first, second, bothLocked);
        Thread b = deadlockThread("live-deadlock-b", second, first, bothLocked);
        busy.start();
        a.start();
        b.start();
        try {
            LiveDiagnostics.ThreadReport report = awaitDeadlock(Duration.ofMillis(300));

            assertThat(report.sampleMillis()).isEqualTo(300);
            assertThat(report.threadCount()).isEqualTo(report.threads().size());
            assertThat(report.states().values().stream().mapToInt(Integer::intValue).sum())
                    .isEqualTo(report.threadCount());
            LiveDiagnostics.ThreadView busyView = thread(report, "live-busy-thread");
            if (report.cpuTimeSupported()) {
                assertThat(busyView.cpuPercent()).isGreaterThan(10);
                assertThat(busyView.state()).isEqualTo("RUNNABLE");
            }
            LiveDiagnostics.ThreadView blocked = thread(report, "live-deadlock-a");
            assertThat(blocked.deadlocked()).isTrue();
            assertThat(blocked.lockOwnerName()).isEqualTo("live-deadlock-b");
            assertThat(blocked.lockName()).startsWith("java.util.concurrent.locks.ReentrantLock$NonfairSync@");
            assertThat(blocked.stack()).anyMatch(line -> line.startsWith("- parking to wait for <"));
            assertThat(report.deadlockedThreadIds()).contains(a.getId(), b.getId());

            String dump = diagnostics.threadDump();
            assertThat(dump).startsWith("Full thread dump ")
                    .contains("\"live-busy-thread\" #" + busy.getId())
                    .contains("java.lang.Thread.State: WAITING")
                    .contains("Locked ownable synchronizers:")
                    .contains("deadlocked threads:")
                    .contains("\"live-deadlock-a\" waiting for <java.util.concurrent.locks.ReentrantLock$NonfairSync@");
        } finally {
            spinning.set(false);
            a.interrupt();
            b.interrupt();
            busy.join(5000);
            a.join(5000);
            b.join(5000);
        }
    }

    @Test
    void reportsMonitorOwnershipInStacks() throws Exception {
        Object monitor = new Object();
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            synchronized (monitor) {
                holding.countDown();
                awaitQuietly(release);
            }
        }, "live-monitor-holder");
        Thread waiter = new Thread(() -> {
            synchronized (monitor) {
                monitor.hashCode();
            }
        }, "live-monitor-waiter");
        holder.start();
        assertThat(holding.await(5, TimeUnit.SECONDS)).isTrue();
        waiter.start();
        try {
            LiveDiagnostics.ThreadView view = null;
            for (int i = 0; i < 200; i++) {
                view = thread(diagnostics.threads(Duration.ZERO), "live-monitor-waiter");
                if (view != null && view.state().equals("BLOCKED")) break;
                Thread.sleep(10);
            }
            assertThat(view.state()).isEqualTo("BLOCKED");
            assertThat(view.cpuPercent()).isEqualTo(-1);
            assertThat(view.lockOwnerName()).isEqualTo("live-monitor-holder");
            assertThat(view.stack()).anyMatch(line -> line.startsWith("- waiting to lock <java.lang.Object@")
                    && line.endsWith("owned by \"live-monitor-holder\""));
            LiveDiagnostics.ThreadView holderView = thread(diagnostics.threads(Duration.ZERO), "live-monitor-holder");
            assertThat(holderView.stack()).anyMatch(line -> line.startsWith("- locked <java.lang.Object@"));
        } finally {
            release.countDown();
            holder.join(5000);
            waiter.join(5000);
        }
    }

    @Test
    void allowsOneThreadSampleAtATime() throws Exception {
        AtomicReference<LiveDiagnostics.ThreadReport> first = new AtomicReference<>();
        Thread sampler = new Thread(() -> {
            try {
                first.set(diagnostics.threads(Duration.ofMillis(800)));
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        sampler.start();
        Thread.sleep(200);
        assertThat(diagnostics.threads(Duration.ZERO)).isNull();
        sampler.join(5000);
        assertThat(first.get()).isNotNull();
        assertThat(diagnostics.threads(Duration.ZERO)).isNotNull();
    }

    @Test
    void throttlesThreadDumps() {
        assertThat(diagnostics.threadDump()).startsWith("Full thread dump ");
        assertThat(diagnostics.threadDump()).isNull();
    }

    @Test
    void clampsTheSampleDuration() throws Exception {
        assertThat(diagnostics.threads(Duration.ofMillis(-5)).sampleMillis()).isZero();
        assertThat(diagnostics.threads(null).sampleMillis()).isZero();
    }

    private LiveDiagnostics.ActiveRequest awaitRequest(String traceId, String state) throws InterruptedException {
        LiveDiagnostics.ActiveRequest found = null;
        for (int i = 0; i < 200; i++) {
            found = diagnostics.activeRequests().items().stream()
                    .filter(item -> item.traceId().equals(traceId)).findFirst().orElse(null);
            if (found != null && state.equals(found.threadState())) return found;
            Thread.sleep(10);
        }
        throw new AssertionError("request not found in state " + state + ": " + found);
    }

    private LiveDiagnostics.ThreadReport awaitDeadlock(Duration sample) throws InterruptedException {
        LiveDiagnostics.ThreadReport report = null;
        for (int i = 0; i < 20; i++) {
            report = diagnostics.threads(sample);
            if (report.deadlockedThreadIds().size() >= 2) return report;
        }
        throw new AssertionError("deadlock not detected");
    }

    private static LiveDiagnostics.ThreadView thread(LiveDiagnostics.ThreadReport report, String name) {
        return report.threads().stream().filter(view -> view.name().equals(name)).findFirst().orElse(null);
    }

    private static Thread deadlockThread(String name, ReentrantLock own, ReentrantLock other, CountDownLatch bothLocked) {
        Thread thread = new Thread(() -> {
            own.lock();
            try {
                bothLocked.countDown();
                bothLocked.await();
                other.lockInterruptibly();
                other.unlock();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                own.unlock();
            }
        }, name);
        thread.setDaemon(true);
        return thread;
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
