package io.github.lakernote.meerkat.metrics.internal;

import io.github.lakernote.meerkat.metrics.internal.OperationStats.Category;
import io.github.lakernote.meerkat.metrics.internal.OperationStats.Period;
import io.github.lakernote.meerkat.metrics.internal.OperationStats.Point;
import io.github.lakernote.meerkat.metrics.internal.OperationStats.Row;
import io.github.lakernote.meerkat.metrics.internal.OperationStats.Window;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

class MetricsArchiveTest {
    private static final long MINUTE = 60_000L;
    private static final long DAY = 24 * 60 * MINUTE;
    private static final long MB = 1024 * 1024;
    private static final LocalDate TODAY = LocalDate.parse("2026-11-05");
    @TempDir
    Path directory;
    private final AtomicLong clock = new AtomicLong(Instant.parse("2026-11-05T10:05:00Z").toEpochMilli());

    @Test
    void flushesCompletedSlicesOnceAndCountsEachCallOnce() throws Exception {
        OperationStats stats = stats(100);
        stats.record(Category.HTTP, "/orders · GET", ms(10), false);
        stats.flush(false);
        assertThat(operations("20261105")).doesNotExist();

        clock.addAndGet(7 * MINUTE);
        stats.record(Category.HTTP, "/orders · GET", ms(30), true);
        stats.flush(false);
        stats.flush(false);

        List<String> lines = Files.readAllLines(operations("20261105"), StandardCharsets.UTF_8);
        assertThat(lines).containsExactly("# meerkat operations v1 bucket=600", lines.get(1));
        assertThat(lines.get(1)).startsWith(millis("2026-11-05T10:00:00Z") + "\tHTTP\t0\t10000000\t10000000\t")
                .endsWith("\t/orders · GET");
        assertThat(row(stats, Window.TODAY, Category.HTTP))
                .extracting(Row::count, Row::errors, Row::maxMs).containsExactly(2L, 1L, 30.0);
        assertThat(row(stats, Window.HOUR, Category.HTTP).count()).isEqualTo(2);
        assertThat(stats.snapshot(Window.TODAY).persistent()).isTrue();
        assertThat(stats.snapshot(Window.TODAY).since()).isEqualTo(Instant.parse("2026-11-05T00:00:00Z"));
    }

    @Test
    void evictedIdleNamesKeepTheirSavedHistory() {
        OperationStats stats = stats(1);
        stats.record(Category.HTTP, "/a · GET", ms(10), false);
        clock.addAndGet(20 * MINUTE);
        stats.flush(false);
        clock.addAndGet(110 * MINUTE);
        stats.evictIdle();
        assertThat(stats.snapshot(Window.TODAY).keys()).isZero();
        stats.record(Category.HTTP, "/b · GET", ms(10), false);

        assertThat(row(stats, Window.HOUR, Category.HTTP).name()).isEqualTo("/b · GET");
        assertThat(category(stats, Window.TODAY, Category.HTTP).count()).isEqualTo(2);
    }

    @Test
    void restartsKeepTheDayAndSumRepeatedSlices() {
        OperationStats first = stats(100);
        first.record(Category.SQL, "SELECT * FROM t WHERE id IN (?…)", ms(5), false);
        first.close();

        OperationStats second = stats(100);
        assertThat(row(second, Window.TODAY, Category.SQL).count()).isEqualTo(1);
        second.record(Category.SQL, "SELECT * FROM t WHERE id IN (?…)", ms(7), false);
        assertThat(row(second, Window.TODAY, Category.SQL).count()).isEqualTo(2);
        second.close();

        OperationStats third = stats(100);
        assertThat(row(third, Window.TODAY, Category.SQL))
                .extracting(Row::count, Row::maxMs, Row::totalMs).containsExactly(2L, 7.0, 12.0);
        assertThat(row(third, Window.HOUR, Category.SQL).count()).isEqualTo(2);
        OperationStats.Trend day = third.trend(Category.SQL, "SELECT * FROM t WHERE id IN (?…)", Period.DAY);
        assertThat(day.persistent()).isTrue();
        assertThat(day.points().get(143)).extracting(Point::timestamp, Point::count, Point::maxMs)
                .containsExactly(millis("2026-11-05T10:00:00Z"), 2L, 7.0);
        assertThat(day.points().get(143).p95Ms()).isNotNull();
    }

    @Test
    void weekAndMonthWindowsReachEarlierDays() {
        OperationStats first = stats(100);
        first.record(Category.REDIS, "GET", ms(1), false);
        first.close();

        clock.addAndGet(3 * DAY);
        OperationStats later = stats(100);
        later.record(Category.REDIS, "GET", ms(3), false);
        assertThat(row(later, Window.TODAY, Category.REDIS).count()).isEqualTo(1);
        assertThat(row(later, Window.WEEK, Category.REDIS).count()).isEqualTo(2);
        assertThat(later.snapshot(Window.WEEK).since()).isEqualTo(Instant.parse("2026-11-02T00:00:00Z"));
        List<Point> week = later.trends(Period.WEEK).get(Category.REDIS).points();
        assertThat(week).filteredOn(point -> point.count() > 0).extracting(Point::timestamp)
                .containsExactly(millis("2026-11-05T10:00:00Z"), millis("2026-11-08T10:00:00Z"));
        later.close();

        clock.addAndGet(10 * DAY);
        OperationStats muchLater = stats(100);
        assertThat(muchLater.snapshot(Window.WEEK).categories()).allSatisfy(c -> assertThat(c.rows()).isEmpty());
        assertThat(row(muchLater, Window.MONTH, Category.REDIS).count()).isEqualTo(2);
    }

    @Test
    void longWindowsFoldNamesBeyondTheKeyLimitIntoOverflow() {
        OperationStats first = stats(2);
        first.record(Category.HTTP, "a", ms(30), false);
        first.record(Category.HTTP, "b", ms(20), false);
        first.close();
        clock.addAndGet(DAY);
        OperationStats second = stats(2);
        second.record(Category.HTTP, "c", ms(10), false);
        second.record(Category.HTTP, "d", ms(5), false);

        assertThat(category(second, Window.WEEK, Category.HTTP).rows())
                .extracting(Row::name, Row::count)
                .containsExactlyInAnyOrder(tuple("a", 1L), tuple(OperationStats.OVERFLOW_NAME, 3L));
    }

    @Test
    void deletesDaysPastRetentionAndOldestDaysBeyondTheSizeBudget() throws Exception {
        for (String name : List.of("operations-20261001.tsv", "operations-20261010.tsv", "resources-20261010.tsv",
                "operations-20261104.tsv", "resources-20261104.tsv", "notes.tsv")) {
            Files.writeString(directory.resolve(name), "x".repeat(300));
        }
        MetricsArchive archive = new MetricsArchive(directory, 1_000, 31, ZoneOffset.UTC);

        assertThat(archive.appendOperations(millis("2026-11-05T10:00:00Z"), List.of(row("a")), TODAY)).isTrue();

        assertThat(directory.resolve("operations-20261001.tsv")).doesNotExist();
        assertThat(directory.resolve("operations-20261010.tsv")).doesNotExist();
        assertThat(directory.resolve("resources-20261010.tsv")).doesNotExist();
        assertThat(directory.resolve("operations-20261104.tsv")).exists();
        assertThat(directory.resolve("resources-20261104.tsv")).exists();
        assertThat(operations("20261105")).exists();
        assertThat(directory.resolve("notes.tsv")).exists();
        assertThat(archive.status()).extracting(MetricsArchive.StorageStatus::persistent,
                MetricsArchive.StorageStatus::maxStorageBytes, MetricsArchive.StorageStatus::retentionDays)
                .containsExactly(true, 1_000L, 31);
        assertThat(archive.status().storageBytes()).isBetween(600L, 1_000L);

        MetricsArchive shortRetention = new MetricsArchive(directory, MB, 1, ZoneOffset.UTC);
        shortRetention.appendResources(millis("2026-11-05T10:00:00Z"), Map.of("a", 1.0), TODAY);
        assertThat(directory.resolve("operations-20261104.tsv")).doesNotExist();
    }

    @Test
    void escapesSeparatorsAndSkipsTornLines() throws Exception {
        MetricsArchive archive = archive();
        archive.appendOperations(millis("2026-11-05T10:00:00Z"), List.of(row("tab\there\\back")), TODAY);
        Files.writeString(operations("20261105"), "garbage\n1\tHTTP\tx\t1\t1\t0\t0\t0\t0:1\tbad\n"
                + "1\tNOPE\t0\t1\t1\t0\t0\t0\t0:1\tbad\n1\tHTTP\t0\t1\t1\t0\t0\t0\t99:1\tbad\n"
                + "1\tHTTP\t0\t1\t1\t0\t0\t0\t0:1\tbad\textra\n1\tHTTP\t0\t1\t1\t0\t0\t0\t0:1", StandardOpenOption.APPEND);

        Map<String, long[]> entries = archive.rankOperations(TODAY, TODAY).get(Category.HTTP);
        assertThat(entries).containsOnlyKeys("tab\there\\back");
        assertThat(entries.get("tab\there\\back")[OperationStats.COUNT]).isEqualTo(1);
        assertThat(entries.get("tab\there\\back")[OperationStats.CPU]).isEqualTo(3);
        assertThat(entries.get("tab\there\\back")[OperationStats.ALLOCATED]).isEqualTo(1024);
        assertThat(entries.get("tab\there\\back")[OperationStats.MEASURED]).isEqualTo(1);
        assertThat(MetricsArchive.unescape(MetricsArchive.escape("a\\b\tc\nd\re"))).isEqualTo("a\\b\tc\nd\re");
    }

    @Test
    void compactsDaysOlderThanTwoDaysOnceAndKeepsRecentDaysFine() throws Exception {
        MetricsArchive archive = archive();
        for (String day : List.of("2026-11-02", "2026-11-04")) {
            for (String time : List.of("T10:00:00Z", "T10:10:00Z", "T11:00:00Z")) {
                archive.appendOperations(millis(day + time), List.of(row("a")), TODAY);
            }
            for (int minute = 0; minute < 3; minute++) {
                archive.appendResources(millis(day + "T10:00:00Z") + minute * MINUTE,
                        Map.of("heap", 100.0 * (minute + 1), "cpu", 0.5, "jvm.gc.memory.allocated", minute + 1.0,
                                "executor.queued:jobs@max", 10.0 * (minute + 1),
                                "jvm.memory.max:heap", 1000.0 + minute), TODAY);
            }
        }

        archive.compact(TODAY);

        assertThat(Files.readAllLines(operations("20261102"))).hasSize(3)
                .first().isEqualTo("# meerkat operations v1 bucket=3600");
        assertThat(Files.readAllLines(operations("20261102")).get(1))
                .startsWith(millis("2026-11-02T10:00:00Z") + "\tHTTP\t0\t10\t5\t6\t2048\t2\t0:2\t");
        List<String> resources = Files.readAllLines(directory.resolve("resources-20261102.tsv"));
        assertThat(resources).hasSize(2).first().isEqualTo("# meerkat resources v2 bucket=600");
        assertThat(resources.get(1)).startsWith(millis("2026-11-02T10:00:00Z") + "\t")
                .contains("heap=200", "cpu=0.5", "jvm.gc.memory.allocated=6", "executor.queued:jobs@max=30",
                        "jvm.memory.max:heap=1002");
        assertThat(Files.readAllLines(directory.resolve("resources-20261104.tsv"))).hasSize(4);
        assertThat(Files.readAllLines(operations("20261104"))).hasSize(4);
        String compacted = Files.readString(operations("20261102"));
        archive.compact(TODAY);
        assertThat(Files.readString(operations("20261102"))).isEqualTo(compacted);

        Map<String, long[]> ranked = archive.rankOperations(LocalDate.parse("2026-11-01"), TODAY).get(Category.HTTP);
        assertThat(ranked.get("a")[OperationStats.COUNT]).isEqualTo(6);
    }

    @Test
    void entryResourcesSurviveRestart() {
        OperationStats first = stats(100);
        first.record(Category.JOB, "nightly", ms(50), false, ms(40), 8 * 1024);
        first.record(Category.JOB, "nightly", ms(70), false, ms(20), 4 * 1024);
        first.close();

        OperationStats.Row row = row(stats(100), Window.TODAY, Category.JOB);
        assertThat(row.count()).isEqualTo(2);
        assertThat(row.meanCpuMs()).isEqualTo(30.0);
        assertThat(row.meanAllocatedBytes()).isEqualTo(6L * 1024);
    }

    @Test
    void restartKeepsTheLastHourWithoutCountingAnyCallTwice() {
        OperationStats before = stats(100);
        before.record(Category.HTTP, "/a", ms(10), true);
        before.record(Category.HTTP, "/a", ms(10), false);
        before.record(Category.HTTP, "/a", ms(10), false);
        clock.set(millis("2026-11-05T10:12:00Z"));
        before.record(Category.HTTP, "/a", ms(40), false);
        before.record(Category.HTTP, "/a", ms(40), false);
        before.close();

        clock.set(millis("2026-11-05T10:14:30Z"));
        OperationStats after = stats(100);
        after.record(Category.HTTP, "/a", ms(20), false);

        assertThat(row(after, Window.HOUR, Category.HTTP)).extracting(Row::count, Row::errors, Row::maxMs)
                .containsExactly(6L, 1L, 40.0);
        assertThat(after.snapshot(Window.HOUR).since()).isEqualTo(Instant.parse("2026-11-05T10:00:00Z"));
        // Saved 10-minute slices are spread over their minutes before the restart: 3 calls over 10:00-10:09 and
        // 2 calls over 10:10-10:13; the call after the restart keeps its own minute.
        List<Point> minutes = after.trend(Category.HTTP, "/a", Period.HOUR).points();
        assertThat(minutes).filteredOn(point -> point.count() > 0)
                .extracting(point -> Instant.ofEpochMilli(point.timestamp()).toString(), Point::count, Point::maxMs)
                .containsExactly(tuple("2026-11-05T10:00:00Z", 1L, 10.0), tuple("2026-11-05T10:01:00Z", 1L, 10.0),
                        tuple("2026-11-05T10:02:00Z", 1L, 10.0), tuple("2026-11-05T10:10:00Z", 1L, 40.0),
                        tuple("2026-11-05T10:11:00Z", 1L, 40.0), tuple("2026-11-05T10:14:00Z", 1L, 20.0));
        assertThat(after.trend(Category.HTTP, "/a", Period.HOUR).since())
                .isEqualTo(Instant.parse("2026-11-05T10:00:00Z"));

        clock.set(millis("2026-11-05T10:21:00Z"));
        after.flush(false);
        assertThat(row(after, Window.HOUR, Category.HTTP).count()).isEqualTo(6);
        assertThat(row(after, Window.TODAY, Category.HTTP).count()).isEqualTo(6);
        assertThat(after.trends(Period.DAY).get(Category.HTTP).points().stream().mapToLong(Point::count).sum())
                .isEqualTo(6);

        clock.set(millis("2026-11-05T11:30:00Z"));
        assertThat(category(stats(100), Window.HOUR, Category.HTTP).rows()).isEmpty();
        after.clear();
        assertThat(category(after, Window.HOUR, Category.HTTP).rows()).isEmpty();
    }

    @Test
    void clearOperationsKeepsResourceHistory() {
        MetricsArchive archive = archive();
        OperationStats stats = new OperationStats(100, archive, clock::get, ZoneOffset.UTC);
        stats.record(Category.JOB, "nightly", ms(1), false);
        stats.close();
        archive.appendResources(clock.get(), Map.of("heap", 1.0), TODAY);
        stats.clear();

        assertThat(operations("20261105")).doesNotExist();
        assertThat(directory.resolve("resources-20261105.tsv")).exists();
        assertThat(stats(100).snapshot(Window.MONTH).categories()).allSatisfy(c -> assertThat(c.rows()).isEmpty());
    }

    @Test
    void unusableDirectoryKeepsStatisticsInMemory() throws Exception {
        Path notADirectory = Files.writeString(directory.resolve("file"), "x");
        MetricsArchive archive = new MetricsArchive(notADirectory.resolve("metrics"), MB, 30, ZoneOffset.UTC);
        OperationStats stats = new OperationStats(100, archive, clock::get, ZoneOffset.UTC);
        stats.record(Category.HTTP, "a", ms(1), false);
        stats.close();

        assertThat(archive.available()).isFalse();
        assertThat(archive.status().persistent()).isFalse();
        assertThat(stats.snapshot(Window.TODAY).persistent()).isFalse();
        assertThat(row(stats, Window.TODAY, Category.HTTP).count()).isEqualTo(1);
        assertThat(stats.trend(Category.HTTP, "a", Period.DAY).persistent()).isFalse();
        assertThat(new MetricsArchive(null, MB, 30, ZoneOffset.UTC).available()).isFalse();
    }

    @Test
    void resourceHistoryCombinesSamplesPerBucketAndReadsTheArchiveAfterRestart() {
        ResourceHistory first = new ResourceHistory(archive(), clock::get, ZoneOffset.UTC);
        long minute = Math.floorDiv(clock.get(), MINUTE) * MINUTE;
        first.record(minute - 2 * MINUTE, Map.of("heap", 100.0, "hikaricp.connections.timeout:main", 1.0,
                "hikaricp.connections.pending:main@max", 7.0));
        first.record(minute - MINUTE, Map.of("heap", 300.0, "cpu", 0.25, "hikaricp.connections.timeout:main", 0.0,
                "hikaricp.connections.pending:main@max", 2.0));
        first.record(minute, Map.of());

        ResourceHistory.Series hour = first.series(Period.HOUR);
        assertThat(hour.timestamps()).hasSize(60).last().isEqualTo(minute);
        assertThat(hour.series().get("heap").subList(57, 60)).containsExactly(100.0, 300.0, null);
        assertThat(hour.series().get("cpu").get(58)).isEqualTo(0.25);
        assertThat(hour.persistent()).isTrue();

        ResourceHistory restarted = new ResourceHistory(archive(), clock::get, ZoneOffset.UTC);
        ResourceHistory.Series day = restarted.series(Period.DAY);
        assertThat(day.timestamps()).hasSize(144);
        List<Double> heap = new ArrayList<>(day.series().get("heap"));
        heap.removeIf(value -> value == null);
        assertThat(heap).containsExactly(200.0);
        assertThat(day.series().get("hikaricp.connections.timeout:main")).filteredOn(value -> value != null)
                .containsExactly(1.0);
        assertThat(day.series().get("hikaricp.connections.pending:main@max")).filteredOn(value -> value != null)
                .containsExactly(7.0);

        ResourceHistory memoryOnly = new ResourceHistory(null, clock::get, ZoneOffset.UTC);
        memoryOnly.record(minute, Map.of("heap", 1.0));
        assertThat(memoryOnly.series(Period.HOUR).persistent()).isFalse();
        assertThat(memoryOnly.series(Period.HOUR).since()).isEqualTo(Instant.ofEpochMilli(minute));
        for (int i = 0; i < ResourceHistory.MEMORY_SAMPLES + 5; i++) memoryOnly.record(minute, Map.of("heap", 2.0));
        assertThat(memoryOnly.series(Period.HOUR).series().get("heap").get(59)).isEqualTo(2.0);
    }

    @Test
    void dayTrendsReadHistoryWithoutBlockingOtherViews() throws Exception {
        OperationStats first = stats(100);
        first.record(Category.REDIS, "GET", ms(1), false);
        first.close();
        clock.addAndGet(3 * DAY);
        MetricsArchive archive = archive();
        OperationStats stats = new OperationStats(100, archive, clock::get, ZoneOffset.UTC);
        stats.record(Category.REDIS, "GET", ms(3), false);

        java.util.concurrent.atomic.AtomicReference<Map<Category, OperationStats.Trend>> week =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread reader = new Thread(() -> week.set(stats.trends(Period.WEEK)));
        synchronized (archive) {
            reader.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (reader.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.sleep(5);
            assertThat(reader.getState()).isEqualTo(Thread.State.BLOCKED);

            java.util.concurrent.CompletableFuture<OperationStats.Snapshot> hour =
                    java.util.concurrent.CompletableFuture.supplyAsync(() -> stats.snapshot(Window.HOUR));
            assertThat(hour.get(2, TimeUnit.SECONDS).categories()).isNotEmpty();
        }
        reader.join(5_000);
        assertThat(week.get().get(Category.REDIS).points()).extracting(Point::count).containsOnly(0L, 1L)
                .filteredOn(count -> count == 1L).hasSize(2);
    }

    @Test
    void dayTrendsReuseUnchangedHistoryFilesAndRereadChangedOnes() throws Exception {
        OperationStats first = stats(100);
        first.record(Category.REDIS, "GET", ms(1), false);
        first.record(Category.REDIS, "SET", ms(1), false);
        first.close();
        clock.addAndGet(3 * DAY);
        OperationStats later = stats(100);
        assertThat(total(later.trends(Period.WEEK).get(Category.REDIS))).isEqualTo(2);
        assertThat(total(later.trend(Category.REDIS, "GET", Period.WEEK))).isEqualTo(1);

        Path file = operations("20261105");
        String original = Files.readString(file, StandardCharsets.UTF_8);
        java.nio.file.attribute.FileTime modified = Files.getLastModifiedTime(file);
        Files.writeString(file, "#".repeat(original.getBytes(StandardCharsets.UTF_8).length - 1) + "\n");
        Files.setLastModifiedTime(file, modified);
        assertThat(total(later.trends(Period.WEEK).get(Category.REDIS))).isEqualTo(2);
        assertThat(total(later.trend(Category.REDIS, "GET", Period.WEEK))).isEqualTo(1);

        String getLine = original.lines().filter(line -> line.endsWith("\tGET")).findFirst().orElseThrow();
        Files.writeString(file, original + getLine + "\n");
        assertThat(total(later.trends(Period.WEEK).get(Category.REDIS))).isEqualTo(3);
        assertThat(total(later.trend(Category.REDIS, "GET", Period.WEEK))).isEqualTo(2);
    }

    private static long total(OperationStats.Trend trend) {
        return trend.points().stream().mapToLong(Point::count).sum();
    }

    private OperationStats stats(int maxKeys) {
        return new OperationStats(maxKeys, archive(), clock::get, ZoneOffset.UTC);
    }

    private MetricsArchive archive() {
        return new MetricsArchive(directory, MB, 30, ZoneOffset.UTC);
    }

    private Path operations(String day) {
        return directory.resolve("operations-" + day + ".tsv");
    }

    private static long millis(String instant) {
        return Instant.parse(instant).toEpochMilli();
    }

    private static MetricsArchive.OperationRow row(String name) {
        long[] cells = new long[OperationStats.CELLS];
        cells[OperationStats.TOTAL] = 5;
        cells[OperationStats.MAX] = 5;
        cells[OperationStats.CPU] = 3;
        cells[OperationStats.ALLOCATED] = 1024;
        cells[OperationStats.MEASURED] = 1;
        cells[OperationStats.FIRST_BUCKET] = 1;
        cells[OperationStats.COUNT] = 1;
        return new MetricsArchive.OperationRow(Category.HTTP, name, cells);
    }

    private static Row row(OperationStats stats, Window window, Category category) {
        return category(stats, window, category).rows().get(0);
    }

    private static OperationStats.CategorySnapshot category(OperationStats stats, Window window, Category category) {
        return stats.snapshot(window).categories().stream().filter(c -> c.category() == category).findFirst()
                .orElseThrow();
    }

    private static long ms(long millis) {
        return TimeUnit.MILLISECONDS.toNanos(millis);
    }

}
