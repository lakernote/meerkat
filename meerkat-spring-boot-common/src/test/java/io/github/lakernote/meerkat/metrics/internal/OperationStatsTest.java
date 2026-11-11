package io.github.lakernote.meerkat.metrics.internal;

import io.github.lakernote.meerkat.metrics.internal.OperationStats.Category;
import io.github.lakernote.meerkat.metrics.internal.OperationStats.Period;
import io.github.lakernote.meerkat.metrics.internal.OperationStats.Point;
import io.github.lakernote.meerkat.metrics.internal.OperationStats.Trend;
import io.github.lakernote.meerkat.metrics.internal.OperationStats.Row;
import io.github.lakernote.meerkat.metrics.internal.OperationStats.Window;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class OperationStatsTest {
    private static final long MINUTE = 60_000L;
    private final AtomicLong clock = new AtomicLong(1_700_000_000_000L);
    private final OperationStats stats = new OperationStats(100, clock::get);

    @Test
    void aggregatesCountErrorsMeanMaxAndTotalPerOperation() {
        stats.record(Category.HTTP, "/orders · GET", ms(10), false);
        stats.record(Category.HTTP, "/orders · GET", ms(30), true);
        stats.record(Category.HTTP, "/users · GET", ms(5), false);

        var http = category(Window.TODAY, Category.HTTP);
        assertThat(http.count()).isEqualTo(3);
        assertThat(http.errors()).isEqualTo(1);
        assertThat(http.totalMs()).isEqualTo(45.0);
        Row orders = http.rows().get(0);
        assertThat(orders.name()).isEqualTo("/orders · GET");
        assertThat(orders.count()).isEqualTo(2);
        assertThat(orders.errors()).isEqualTo(1);
        assertThat(orders.meanMs()).isEqualTo(20.0);
        assertThat(orders.maxMs()).isEqualTo(30.0);
        assertThat(orders.totalMs()).isEqualTo(40.0);
        assertThat(http.rows()).extracting(Row::name).containsExactly("/orders · GET", "/users · GET");
        assertThat(category(Window.TODAY, Category.SQL).rows()).isEmpty();
    }

    @Test
    void averagesEntryCpuAndAllocationOverCallsThatReportedThem() {
        stats.record(Category.HTTP, "/report · GET", ms(40), false, ms(30), 4 * 1024 * 1024);
        stats.record(Category.HTTP, "/report · GET", ms(60), false, ms(10), 2 * 1024 * 1024);
        // An async request finished on another thread reports no resources and must not dilute the averages.
        stats.record(Category.HTTP, "/report · GET", ms(500), false, -1, -1);
        stats.record(Category.SQL, "select ?", ms(5), false);

        Row report = category(Window.HOUR, Category.HTTP).rows().get(0);
        assertThat(report.count()).isEqualTo(3);
        assertThat(report.meanCpuMs()).isEqualTo(20.0);
        assertThat(report.meanAllocatedBytes()).isEqualTo(3L * 1024 * 1024);
        Row sql = category(Window.HOUR, Category.SQL).rows().get(0);
        assertThat(sql.meanCpuMs()).isNull();
        assertThat(sql.meanAllocatedBytes()).isNull();
    }

    @Test
    void estimatesP95WithinHistogramResolutionAndNeverAboveMax() {
        for (int i = 1; i <= 100; i++) stats.record(Category.SQL, "select ?", ms(i), false);
        stats.record(Category.REDIS, "GET", 3_000, false);

        Row sql = category(Window.TODAY, Category.SQL).rows().get(0);
        assertThat(sql.p95Ms()).isCloseTo(95.0, within(95.0 * 0.2));
        assertThat(sql.p95Ms()).isLessThanOrEqualTo(sql.maxMs());
        Row redis = category(Window.TODAY, Category.REDIS).rows().get(0);
        assertThat(redis.p95Ms()).isLessThanOrEqualTo(redis.maxMs()).isEqualTo(0.003);
    }

    @Test
    void p95OfClusteredLatenciesStaysBetweenMeanAndMax() {
        // All calls land near the bottom of one √2-wide bucket; the bucket midpoint would fall below the mean.
        for (int i = 0; i < 20; i++) stats.record(Category.SQL, "select ?", ms(18) + i * 50_000L, false);
        stats.record(Category.HTTP, "/cpu · GET", 200_860_000L, false);
        stats.record(Category.HTTP, "/cpu · GET", 200_965_000L, false);

        Row sql = category(Window.TODAY, Category.SQL).rows().get(0);
        assertThat(sql.p95Ms()).isBetween(sql.meanMs(), sql.maxMs());
        Row cpu = category(Window.TODAY, Category.HTTP).rows().get(0);
        assertThat(cpu.p95Ms()).isBetween(cpu.meanMs(), cpu.maxMs());
    }

    @Test
    void bucketsCoverSubMillisecondToTensOfSeconds() {
        assertThat(OperationStats.bucket(0)).isZero();
        assertThat(OperationStats.bucket(9_999)).isZero();
        assertThat(OperationStats.bucket(10_000)).isEqualTo(1);
        assertThat(OperationStats.bucket(ms(1))).isBetween(13, 14);
        assertThat(OperationStats.bucket(TimeUnit.HOURS.toNanos(1))).isEqualTo(45);
        assertThat(OperationStats.bucket(ms(1))).isLessThan(OperationStats.bucket(ms(2)));
    }

    @Test
    void hourWindowDropsSlicesOlderThanSixtyMinutesButTodayKeepsThem() {
        stats.record(Category.REMOTE, "GET api /a", ms(100), false);
        clock.addAndGet(30 * MINUTE);
        stats.record(Category.REMOTE, "GET api /a", ms(200), true);

        assertThat(category(Window.HOUR, Category.REMOTE).count()).isEqualTo(2);

        clock.addAndGet(35 * MINUTE);
        Row lastHour = category(Window.HOUR, Category.REMOTE).rows().get(0);
        assertThat(lastHour.count()).isEqualTo(1);
        assertThat(lastHour.maxMs()).isEqualTo(200.0);
        assertThat(category(Window.TODAY, Category.REMOTE).count()).isEqualTo(2);

        // 2023-11-14T22:13Z + 125 minutes is the next UTC day, which starts empty.
        clock.addAndGet(60 * MINUTE);
        assertThat(category(Window.HOUR, Category.REMOTE).rows()).isEmpty();
        assertThat(category(Window.TODAY, Category.REMOTE).rows()).isEmpty();
        stats.record(Category.REMOTE, "GET api /a", ms(1), false);
        assertThat(category(Window.HOUR, Category.REMOTE).count()).isEqualTo(1);
        assertThat(category(Window.TODAY, Category.REMOTE).count()).isEqualTo(1);
    }

    @Test
    void reportsWindowStartClampedToStartup() {
        long started = clock.get();
        assertThat(stats.snapshot(Window.HOUR).since()).isEqualTo(Instant.ofEpochMilli(started));
        clock.addAndGet(3 * 60 * MINUTE);
        var hour = stats.snapshot(Window.HOUR);
        assertThat(hour.since()).isAfter(Instant.ofEpochMilli(clock.get() - 61 * MINUTE))
                .isBeforeOrEqualTo(Instant.ofEpochMilli(clock.get() - 50 * MINUTE));
        // Without an archive the day windows cover only the slices kept in memory, a little over an hour.
        long oldestSlice = Math.floorDiv(clock.get(), 600_000L) * 600_000L - 60 * MINUTE;
        long midnight = java.time.LocalDate.ofInstant(Instant.ofEpochMilli(clock.get()), java.time.ZoneOffset.UTC)
                .atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();
        assertThat(stats.snapshot(Window.TODAY).since()).isEqualTo(Instant.ofEpochMilli(Math.max(oldestSlice, midnight)));
        assertThat(stats.snapshot(Window.TODAY).persistent()).isFalse();
        assertThat(hour.generatedAt()).isEqualTo(Instant.ofEpochMilli(clock.get()));
    }

    @Test
    void namesIdleForAnHourGiveTheirSlotBack() {
        OperationStats limited = new OperationStats(2, clock::get);
        limited.record(Category.REMOTE, "GET a", ms(1), false);
        limited.record(Category.REMOTE, "GET b", ms(1), false);
        clock.addAndGet(60 * MINUTE);
        limited.record(Category.REMOTE, "GET b", ms(1), false);
        limited.evictIdle();
        assertThat(limited.snapshot(Window.HOUR).keys()).isEqualTo(2);

        clock.addAndGet(20 * MINUTE);
        limited.evictIdle();
        assertThat(limited.snapshot(Window.HOUR).keys()).isEqualTo(1);
        limited.record(Category.REMOTE, "GET c", ms(1), false);

        assertThat(category(limited, Window.HOUR, Category.REMOTE).rows()).extracting(Row::name)
                .containsExactlyInAnyOrder("GET b", "GET c");
        assertThat(limited.snapshot(Window.HOUR).keys()).isEqualTo(2);
    }

    @Test
    void mergesOperationsBeyondTheKeyLimitIntoOneOverflowRowPerCategory() {
        OperationStats limited = new OperationStats(2, clock::get);
        limited.record(Category.HTTP, "a", ms(1), false);
        limited.record(Category.SQL, "b", ms(1), false);
        limited.record(Category.HTTP, "c", ms(1), false);
        limited.record(Category.HTTP, "d", ms(1), false);
        limited.record(Category.SQL, "e", ms(1), false);
        limited.record(Category.HTTP, "a", ms(1), false);

        var snapshot = limited.snapshot(Window.TODAY);
        assertThat(snapshot.keys()).isEqualTo(2);
        assertThat(snapshot.maxKeys()).isEqualTo(2);
        assertThat(snapshot.categories().get(0).rows())
                .extracting(Row::name, Row::count)
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple("a", 2L),
                        org.assertj.core.groups.Tuple.tuple(OperationStats.OVERFLOW_NAME, 2L));
        assertThat(category(limited, Window.TODAY, Category.SQL).rows()).extracting(Row::name)
                .containsExactlyInAnyOrder("b", OperationStats.OVERFLOW_NAME);
    }

    @Test
    void normalizesNamesAndIgnoresMissingCategory() {
        stats.record(null, "ignored", 1, false);
        stats.record(Category.JOB, null, 1, false);
        stats.record(Category.JOB, "  ", 1, false);
        stats.record(Category.JOB, "line\r\nbreak", -5, false);
        stats.record(Category.JOB, "x".repeat(2_000), 1, false);

        var job = category(Window.TODAY, Category.JOB);
        assertThat(job.rows()).extracting(Row::name)
                .contains("-", "line  break")
                .allSatisfy(name -> assertThat(name).hasSizeLessThanOrEqualTo(OperationStats.MAX_NAME_LENGTH));
        assertThat(job.rows()).filteredOn(row -> row.name().equals("-")).singleElement()
                .extracting(Row::count).isEqualTo(2L);
        assertThat(stats.snapshot(Window.TODAY).categories()).extracting(c -> c.count()).containsOnly(0L, 4L);
    }

    @Test
    void countsExactlyUnderConcurrentWriters() throws InterruptedException {
        int threads = 8;
        int perThread = 20_000;
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> workers = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            int worker = t;
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException interrupted) {
                    return;
                }
                for (int i = 0; i < perThread; i++) {
                    stats.record(Category.REDIS, "GET", (i % 100) * 1_000L, i % 10 == 0);
                    if (worker == 0 && i % 5_000 == 0) clock.addAndGet(10 * MINUTE);
                }
            });
            workers.add(thread);
            thread.start();
        }
        start.countDown();
        for (Thread worker : workers) worker.join();

        Row all = category(Window.TODAY, Category.REDIS).rows().get(0);
        assertThat(all.count()).isEqualTo((long) threads * perThread);
        assertThat(all.errors()).isEqualTo((long) threads * perThread / 10);
        assertThat(all.maxMs()).isEqualTo(0.099);
    }

    @Test
    void clearRemovesAllOperationsAndFreesKeys() {
        stats.record(Category.HTTP, "a", ms(1), false);
        stats.clear();
        assertThat(stats.snapshot(Window.TODAY).keys()).isZero();
        assertThat(category(Window.TODAY, Category.HTTP).rows()).isEmpty();
    }

    @Test
    void parsesWindowNames() {
        assertThat(Window.parse(null)).isEqualTo(Window.HOUR);
        assertThat(Window.parse(" 1h ")).isEqualTo(Window.HOUR);
        assertThat(Window.parse("TODAY")).isEqualTo(Window.TODAY);
        assertThat(Window.parse("7d")).isEqualTo(Window.WEEK);
        assertThat(Window.parse("30d")).isEqualTo(Window.MONTH);
        assertThatThrownBy(() -> Window.parse("all")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Window.parse("week")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void hourTrendHasOneBucketPerMinuteWithoutP95() {
        long start = Math.floorDiv(clock.get(), MINUTE) * MINUTE;
        stats.record(Category.HTTP, "/a", ms(10), false);
        stats.record(Category.HTTP, "/a", ms(30), true);
        stats.record(Category.HTTP, "/b", ms(50), false);
        clock.addAndGet(5 * MINUTE);
        stats.record(Category.HTTP, "/a", ms(20), false);

        Trend trend = stats.trend(Category.HTTP, "/a", Period.HOUR);
        assertThat(trend.points()).hasSize(60);
        assertThat(trend.stepMillis()).isEqualTo(MINUTE);
        assertThat(trend.points().get(59).timestamp()).isEqualTo(start + 5 * MINUTE);
        Point first = trend.points().get(54);
        assertThat(first.timestamp()).isEqualTo(start);
        assertThat(first).extracting(Point::count, Point::errors, Point::meanMs, Point::p95Ms, Point::maxMs)
                .containsExactly(2L, 1L, 20.0, null, 30.0);
        assertThat(trend.points().get(55)).extracting(Point::count, Point::meanMs).containsExactly(0L, null);
        assertThat(trend.points().get(59).count()).isEqualTo(1);

        Map<Category, Trend> all = stats.trends(Period.HOUR);
        assertThat(all).containsOnlyKeys(Category.values());
        assertThat(all.get(Category.HTTP).points().get(54).count()).isEqualTo(3);
        assertThat(all.get(Category.SQL).points()).allSatisfy(point -> assertThat(point.count()).isZero());
        assertThat(stats.trend(Category.HTTP, "missing", Period.HOUR).points())
                .allSatisfy(point -> assertThat(point.count()).isZero());

        clock.addAndGet(61 * MINUTE);
        assertThat(stats.trend(Category.HTTP, "/a", Period.HOUR).points().stream().mapToLong(Point::count).sum())
                .isZero();
    }

    @Test
    void dayTrendUsesTenMinuteSlicesWithP95FromMemory() {
        stats.record(Category.SQL, "select", ms(10), false);
        stats.record(Category.SQL, "select", ms(100), false);
        clock.addAndGet(30 * MINUTE);
        stats.record(Category.SQL, "select", ms(5), true);

        Trend day = stats.trend(Category.SQL, "select", Period.DAY);
        assertThat(day.points()).hasSize(144);
        assertThat(day.stepMillis()).isEqualTo(10 * MINUTE);
        assertThat(day.persistent()).isFalse();
        List<Point> busy = day.points().stream().filter(point -> point.count() > 0).toList();
        assertThat(busy).extracting(Point::count, Point::errors).containsExactly(
                org.assertj.core.groups.Tuple.tuple(2L, 0L), org.assertj.core.groups.Tuple.tuple(1L, 1L));
        assertThat(busy.get(0).p95Ms()).isNotNull().isLessThanOrEqualTo(100.0);
        assertThat(busy.get(1).timestamp() - busy.get(0).timestamp()).isEqualTo(30 * MINUTE);

        Trend week = stats.trend(Category.SQL, "select", Period.WEEK);
        assertThat(week.points()).hasSize(168);
        assertThat(week.points().stream().mapToLong(Point::count).sum()).isEqualTo(3);
        assertThat(stats.trend(Category.SQL, "select", Period.MONTH).points()).hasSize(720);
    }

    @Test
    void snapshotReportsWhetherCallsAreCollected() {
        assertThat(stats.snapshot(Window.HOUR).collecting()).isTrue();
        stats.setCollecting(false);
        assertThat(stats.snapshot(Window.HOUR).collecting()).isFalse();
    }

    @Test
    void parsesPeriodNames() {
        assertThat(Period.parse(null)).isEqualTo(Period.HOUR);
        assertThat(Period.parse("24h")).isEqualTo(Period.DAY);
        assertThat(Period.parse(" 7D ")).isEqualTo(Period.WEEK);
        assertThat(Period.parse("30d")).isEqualTo(Period.MONTH);
        assertThatThrownBy(() -> Period.parse("today")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> stats.trend(null, "x", Period.HOUR)).isInstanceOf(IllegalArgumentException.class);
    }

    private OperationStats.CategorySnapshot category(Window window, Category category) {
        return category(stats, window, category);
    }

    private static OperationStats.CategorySnapshot category(OperationStats stats, Window window, Category category) {
        return stats.snapshot(window).categories().stream().filter(c -> c.category() == category).findFirst()
                .orElseThrow();
    }

    private static long ms(long millis) {
        return TimeUnit.MILLISECONDS.toNanos(millis);
    }
}
