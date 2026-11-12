package io.github.lakernote.meerkat.trace.internal.store;

import io.github.lakernote.meerkat.trace.internal.TraceNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TraceStoreTest {
    private static final Instant START = Instant.parse("2026-11-05T10:00:00Z");
    private static final long MB = 1024 * 1024;

    @TempDir
    Path root;

    private final MutableClock clock = new MutableClock(START);

    @Test
    void keepsOnlySlowFailedOrResourceHeavyEntryTraces() {
        TraceStore store = TraceStore.inMemory(1024 * 1024);

        assertThat(store.add(trace("ok").slow(false).build())).isNull();
        assertThat(store.add(trace("redis").sourceType("REDIS").build())).isNull();
        assertThat(store.add(trace("slow").build())).isNotNull();
        assertThat(store.add(trace("cpu").slow(false).highCpu(true).build())).isNotNull();

        assertThat(store.recent(10)).extracting(TraceRecord::traceId).containsExactly("cpu", "slow");
    }

    @Test
    void limitsTracesPerOperationAndOutcomeEachMinute() {
        TraceStore store = store(100, 128 * MB).limitPerOperation(2);

        for (int i = 0; i < 5; i++) {
            store.add(trace("err" + i).name("GET /pay").slow(false).error(true).errorType("Timeout").build());
        }
        store.add(trace("other-error").name("GET /pay").slow(false).error(true).errorType("NPE").build());
        store.add(trace("slow").name("GET /pay").build());
        store.add(trace("other-api").name("GET /cart").slow(false).error(true).errorType("Timeout").build());

        assertThat(store.recent(100)).extracting(TraceRecord::traceId)
                .containsExactly("other-api", "slow", "other-error", "err1", "err0");
        assertThat(store.storageStatus().throttledCount()).isEqualTo(3);

        clock.advance(Duration.ofMinutes(1));
        assertThat(store.add(trace("err-next").name("GET /pay").slow(false).error(true).errorType("Timeout").build()))
                .isNotNull();
    }

    @Test
    void memoryOnlyStoreForgetsTracesBeyondTheHotWindow() {
        TraceStore store = TraceStore.inMemory(2 * trace("a").build().estimatedBytes());
        store.add(trace("a").build());
        store.add(trace("b").build());
        store.add(trace("c").build());

        assertThat(store.size()).isEqualTo(2);
        assertThat(store.find("a", "")).isNull();
        assertThat(store.search(query("all", "", "", "")).items()).extracting(TraceSummary::traceId)
                .containsExactly("c", "b");
    }

    @Test
    void hotWindowIsBoundedByEstimatedSizeButAlwaysKeepsTheNewestTrace() {
        TraceRecord.Builder nPlusOne = trace("n-plus-one").treeNodes(java.util.stream.IntStream.range(0, 512)
                .mapToObj(i -> sql("select * from order_item where order_id = " + i + " and note = '" + "x".repeat(400) + "'"))
                .toList());
        long oneLargeTrace = nPlusOne.build().estimatedBytes();
        assertThat(oneLargeTrace).isGreaterThan(512 * 800);

        TraceStore store = TraceStore.inMemory(oneLargeTrace * 2);
        store.add(nPlusOne.traceId("large-1").build());
        store.add(nPlusOne.traceId("large-2").build());
        store.add(trace("small").build());

        assertThat(store.find("large-1", "")).isNull();
        assertThat(store.find("large-2", "")).isNotNull();
        assertThat(store.find("small", "")).isNotNull();

        TraceStore tiny = TraceStore.inMemory(1);
        tiny.add(nPlusOne.traceId("only").build());
        assertThat(tiny.find("only", "")).isNotNull();
        tiny.add(trace("next").build());
        assertThat(tiny.find("only", "")).isNull();
        assertThat(tiny.size()).isEqualTo(1);
    }

    @Test
    void listIndexIsBoundedByEstimatedSizeOnAddAndOnRestore() throws Exception {
        long rowBytes = TraceSummary.of(trace("t0").finishedAt(START).build()).estimatedBytes();
        TraceStore store = store(10, 128 * MB);
        store.maxIndexBytes(3 * rowBytes);
        store.start();
        for (int i = 0; i < 5; i++) store.add(trace("t" + i).build());

        assertThat(store.size()).isEqualTo(3);
        assertThat(store.search(query("all", "", "", "")).items()).extracting(TraceSummary::traceId)
                .containsExactly("t4", "t3", "t2");
        store.close();

        TraceStore restarted = store(10, 128 * MB);
        restarted.maxIndexBytes(2 * rowBytes);
        restarted.start();
        awaitIndexed(restarted);
        assertThat(restarted.search(query("all", "", "", "")).items()).extracting(TraceSummary::traceId)
                .containsExactly("t4", "t3");
    }

    @Test
    void prefersHttpEntryWhenSourceIsNotGiven() {
        TraceStore store = TraceStore.inMemory(1024 * 1024);
        store.add(trace("shared").sourceType("HTTP").name("GET /orders").build());
        store.add(trace("shared").sourceType("KAFKA_CONSUMER").name("orders-topic").build());

        assertThat(store.find("shared", "").name()).isEqualTo("GET /orders");
        assertThat(store.find("shared", "kafka_consumer").name()).isEqualTo("orders-topic");
        assertThat(store.find("missing", "")).isNull();
        assertThat(store.find(" ", "")).isNull();
    }

    @Test
    void readsEvictedTracesBackFromTheirSegmentAndRestoresTheIndexAfterRestart() throws Exception {
        TraceStore store = store(2, 128 * MB);
        store.start();
        for (int i = 0; i < 5; i++) store.add(trace("t" + i).treeNodes(List.of(sql("select * from orders_" + i))).build());

        TraceRecord evicted = store.find("t0", "HTTP");
        assertThat(evicted.treeNodes()).singleElement().extracting(TraceNode::tag).isEqualTo("select * from orders_0");
        assertThat(segments()).singleElement().extracting(path -> path.getFileName().toString())
                .isEqualTo("traces-20261105-1.jsonl");
        store.close();

        TraceStore restarted = store(2, 128 * MB);
        restarted.start();
        awaitIndexed(restarted);

        assertThat(restarted.size()).isEqualTo(5);
        assertThat(restarted.search(query("all", "", "", "")).items()).extracting(TraceSummary::traceId)
                .containsExactly("t4", "t3", "t2", "t1", "t0");
        assertThat(restarted.find("t3", "").treeNodes()).singleElement().extracting(TraceNode::tag)
                .isEqualTo("select * from orders_3");
        restarted.add(trace("t5").build());
        assertThat(segments()).singleElement().extracting(path -> path.getFileName().toString())
                .isEqualTo("traces-20261105-1.jsonl");
        assertThat(restarted.search(query("all", "", "", "")).items()).extracting(TraceSummary::traceId)
                .containsExactly("t5", "t4", "t3", "t2", "t1", "t0");
        assertThat(restarted.find("t5", "")).isNotNull();
        restarted.close();

        TraceStore again = store(2, 128 * MB);
        again.start();
        awaitIndexed(again);
        assertThat(again.size()).isEqualTo(6);
    }

    @Test
    void filtersByKindAndSourceAndPagesWithCursors() {
        TraceStore store = TraceStore.inMemory(1024 * 1024);
        for (int i = 0; i < 5; i++) {
            store.add(trace("slow" + i).build());
            clock.advance(Duration.ofMillis(1));
        }
        store.add(trace("failed").error(true).build());
        clock.advance(Duration.ofMillis(1));
        store.add(trace("job").sourceType("JOB").slow(false).highAllocation(true).build());

        TraceStore.TracePage first = store.search(new TraceStore.TraceQuery("all", "", "", "", 3));
        assertThat(first.total()).isEqualTo(7);
        assertThat(first.items()).extracting(TraceSummary::traceId).containsExactly("job", "failed", "slow4");
        TraceStore.TracePage second = store.search(new TraceStore.TraceQuery("all", "", "", first.nextCursor(), 3));
        assertThat(second.items()).extracting(TraceSummary::traceId).containsExactly("slow3", "slow2", "slow1");
        TraceStore.TracePage third = store.search(new TraceStore.TraceQuery("all", "", "", second.nextCursor(), 3));
        assertThat(third.items()).extracting(TraceSummary::traceId).containsExactly("slow0");
        assertThat(third.nextCursor()).isNull();

        assertThat(store.search(query("slow", "", "", "")).total()).isEqualTo(5);
        assertThat(store.search(query("error", "", "", "")).items()).extracting(TraceSummary::traceId)
                .containsExactly("failed");
        assertThat(store.search(query("resource", "", "", "")).items()).extracting(TraceSummary::traceId)
                .containsExactly("job");
        assertThat(store.search(query("all", "job", "", "")).items()).extracting(TraceSummary::traceId)
                .containsExactly("job");
    }

    @Test
    void textSearchMatchesSummaryFieldsAndSpansReadFromDisk() throws Exception {
        TraceStore store = store(1, 128 * MB);
        store.start();
        store.add(trace("with-sql").name("GET /orders").treeNodes(List.of(sql("SELECT * FROM Invoice"))).build());
        store.add(trace("with-error").name("GET /pay").errorType("java.sql.SQLException").build());
        store.add(trace("other").name("GET /users").build());

        TraceStore.TracePage bySql = store.search(query("all", "", "from invoice", ""));
        assertThat(bySql.items()).extracting(TraceSummary::traceId).containsExactly("with-sql");
        assertThat(bySql.total()).isEqualTo(-1);
        assertThat(store.search(query("all", "", "SQLException", "")).items()).extracting(TraceSummary::traceId)
                .containsExactly("with-error");
        assertThat(store.search(query("all", "", "with-sql", "")).items()).extracting(TraceSummary::traceId)
                .containsExactly("with-sql");
        assertThat(store.search(query("all", "", "\"quoted\" 中文", "")).items()).isEmpty();
    }

    @Test
    void textSearchStopsAtItsDiskBudgetAndContinuesFromTheCursor() throws Exception {
        TraceStore store = store(1, 128 * MB);
        store.start();
        store.add(trace("match").treeNodes(List.of(sql("select needle"))).build());
        for (int i = 0; i < 4; i++) {
            clock.advance(Duration.ofMillis(1));
            store.add(trace("miss" + i).treeNodes(List.of(sql("select hay"))).build());
        }
        store.searchScanBytes(1);

        List<String> found = new ArrayList<>();
        String cursor = "";
        int requests = 0;
        TraceStore.TracePage page;
        do {
            page = store.search(query("all", "", "needle", cursor));
            page.items().forEach(item -> found.add(item.traceId()));
            cursor = page.nextCursor();
            requests++;
        } while (cursor != null && requests < 20);

        assertThat(found).containsExactly("match");
        assertThat(requests).isGreaterThan(1);
    }

    @Test
    void rollsSegmentsPerDayAndBySizeAndDeletesExpiredDays() throws Exception {
        TraceStore store = store(1, 2 * MB);
        store.start();
        store.add(trace("day1").build());
        clock.advance(Duration.ofDays(1));
        store.add(trace("day2").build());
        String big = "x".repeat(200 * 1024);
        store.add(trace("big1").errorStackTrace(big).build());
        store.add(trace("big2").errorStackTrace(big).build());

        assertThat(segmentNames()).contains("traces-20261105-1.jsonl", "traces-20261106-1.jsonl",
                "traces-20261106-2.jsonl");

        clock.advance(Duration.ofDays(7));
        store.add(trace("later").build());

        assertThat(segmentNames()).containsExactly("traces-20261113-1.jsonl");
        assertThat(store.find("day1", "")).isNull();
        assertThat(store.search(query("all", "", "", "")).items()).extracting(TraceSummary::traceId)
                .containsExactly("later");
    }

    @Test
    void quietStoreStillDeletesExpiredSegments() throws Exception {
        TraceStore store = store(1, 128 * MB);
        store.start();
        store.add(trace("old1").build());
        store.add(trace("old2").build());
        assertThat(segmentNames()).containsExactly("traces-20261105-1.jsonl");

        clock.advance(Duration.ofDays(8));
        store.storageStatus();

        assertThat(segmentNames()).isEmpty();
        assertThat(store.find("old1", "")).isNull();
    }

    @Test
    void segmentDeletedWhileTheIndexIsRestoredIsNotListed() throws Exception {
        TraceStore first = store(10, 128 * MB);
        first.start();
        first.add(trace("old").build());
        first.close();

        java.util.concurrent.CountDownLatch reading = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        TraceRecordJsonCodec blocking = new TraceRecordJsonCodec() {
            @Override
            TraceRecord read(byte[] line) throws IOException {
                if (Thread.currentThread().getName().equals("meerkat-trace-index")) {
                    reading.countDown();
                    try {
                        release.await(5, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
                return super.read(line);
            }
        };
        TraceStore restarted = new TraceStore(blocking, root.resolve("traces"), 7, 128 * MB, hotBytes(10),
                task -> {
                    task.run();
                    return true;
                }, () -> { }, clock);
        restarted.start();
        assertThat(reading.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        clock.advance(Duration.ofDays(8));
        restarted.add(trace("new").build());
        assertThat(segmentNames()).containsExactly("traces-20261113-1.jsonl");
        release.countDown();
        awaitIndexed(restarted);

        assertThat(restarted.search(query("all", "", "", "")).items()).extracting(TraceSummary::traceId)
                .containsExactly("new");
        assertThat(restarted.size()).isEqualTo(1);
    }

    @Test
    void deletesOldestSegmentsBeyondTheStorageBudget() throws Exception {
        TraceStore store = store(1, 1 * MB);
        store.start();
        String big = "y".repeat(100 * 1024);
        for (int i = 0; i < 30; i++) store.add(trace("t" + i).errorStackTrace(big).build());

        long bytes = store.storageStatus().storageBytes();
        assertThat(bytes).isLessThanOrEqualTo(MB + 256 * 1024);
        assertThat(store.find("t0", "")).isNull();
        assertThat(store.find("t29", "")).isNotNull();
        assertThat(store.size()).isLessThan(30);
    }

    @Test
    void restoreSkipsMalformedAndTruncatedLines() throws Exception {
        Path directory = root.resolve("traces");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("traces-20261105-1.jsonl"),
                json(trace("good").finishedAt(START).id(1).build()) + "\n"
                        + "not json\n"
                        + json(trace("normal").slow(false).finishedAt(START).id(2).build()) + "\n"
                        + json(trace("cut").finishedAt(START).id(3).build()).substring(0, 20));
        Files.writeString(directory.resolve("unrelated.jsonl"), "x");

        TraceStore store = store(10, 128 * MB);
        store.start();
        awaitIndexed(store);

        assertThat(store.recent(10)).extracting(TraceRecord::traceId).containsExactly("good");
        assertThat(directory.resolve("unrelated.jsonl")).exists();

        store.add(trace("after").build());
        assertThat(segments()).extracting(path -> path.getFileName().toString())
                .containsExactlyInAnyOrder("traces-20261105-1.jsonl", "traces-20261105-2.jsonl");
    }

    @Test
    void clearDeletesFilesAndDiscardsQueuedWrites() throws Exception {
        List<Runnable> queued = new ArrayList<>();
        TraceStore store = store(1, 128 * MB, queued::add);
        store.start();
        store.add(trace("written").build());
        queued.remove(0).run();
        store.add(trace("queued").build());

        assertThat(store.clear()).isEqualTo(2);
        queued.forEach(Runnable::run);

        assertThat(segments()).isEmpty();
        assertThat(store.size()).isZero();
        assertThat(store.search(query("all", "", "", "")).items()).isEmpty();
        store.add(trace("after").build());
        assertThat(store.find("after", "")).isNotNull();
    }

    @Test
    void queuedWritesStayWithinTheHotBudgetWhenTheDiskFallsBehind() throws Exception {
        List<Runnable> queued = new ArrayList<>();
        TraceStore store = store(2, 128 * MB, queued::add);
        store.start();
        for (int i = 0; i < 10; i++) {
            store.add(trace("t" + i).treeNodes(List.of(sql("select * from orders_0"))).build());
        }

        assertThat(queued).hasSize(2);
        // Two hot records plus the two evicted ones still waiting for the writer.
        assertThat(store.size()).isEqualTo(4);
        queued.forEach(Runnable::run);
        queued.clear();
        store.add(trace("t10").treeNodes(List.of(sql("select * from orders_0"))).build());
        assertThat(queued).hasSize(1);
    }

    @Test
    void clearReportsSegmentsItCouldNotDeleteAndKeepsTheirNames() throws Exception {
        TraceStore store = store(10, 128 * MB);
        store.start();
        store.add(trace("first").build());
        Path segment = segments().get(0);
        Files.delete(segment);
        Files.createDirectories(segment.resolve("blocker"));

        assertThatThrownBy(store::clear).isInstanceOf(java.io.UncheckedIOException.class);
        store.add(trace("after").build());
        assertThat(store.storageStatus().persistent()).isTrue();
        assertThat(segments()).hasSize(2);

        Files.delete(segment.resolve("blocker"));
        store.clear();
        assertThat(segments()).isEmpty();
    }

    @Test
    void closeStopsTheIndexRebuild() throws Exception {
        TraceStore first = store(10, 128 * MB);
        first.start();
        for (int i = 0; i < 20; i++) first.add(trace("r" + i).build());
        first.close();

        TraceStore restarted = store(10, 128 * MB);
        restarted.start();
        restarted.close();
        restarted.add(trace("late").build());

        assertThat(restarted.storageStatus().indexing()).isFalse();
    }

    @Test
    void writesNeverRunOnTheRecordingThreadAndDroppedWritesStayInMemoryWhileHot() throws Exception {
        List<Runnable> queued = new ArrayList<>();
        TraceStore store = store(2, 128 * MB, queued::add);
        store.start();
        store.add(trace("a").build());
        assertThat(segments()).isEmpty();

        TraceStore rejecting = store(1, 128 * MB, task -> false);
        rejecting.start();
        rejecting.add(trace("dropped").build());
        assertThat(rejecting.find("dropped", "")).isNotNull();
        rejecting.add(trace("next").build());
        assertThat(rejecting.find("dropped", "")).isNull();
        assertThat(rejecting.size()).isEqualTo(1);
    }

    @Test
    void writeFailureFallsBackToMemoryOnly() throws Exception {
        TraceRecordJsonCodec failing = new TraceRecordJsonCodec() {
            @Override
            void write(OutputStream output, TraceRecord trace) throws IOException {
                throw new IOException("disk full");
            }
        };
        TraceStore store = new TraceStore(failing, root.resolve("traces"), 7, 128 * MB, hotBytes(10), task -> {
            task.run();
            return true;
        }, () -> { }, clock);
        store.start();
        store.add(trace("kept").build());

        assertThat(store.storageStatus().persistent()).isFalse();
        assertThat(store.find("kept", "")).isNotNull();
        store.add(trace("later").build());
        assertThat(store.size()).isEqualTo(2);
    }

    @Test
    void unusableDirectoryFallsBackToMemoryOnly() throws Exception {
        Path notADirectory = root.resolve("file");
        Files.writeString(notADirectory, "x");
        TraceStore store = new TraceStore(TraceRecordJsonCodec.INSTANCE, notADirectory, 7, 128 * MB, hotBytes(10),
                task -> true, () -> { }, clock);
        store.start();

        assertThat(store.storageStatus().persistent()).isFalse();
        store.add(trace("kept").build());
        assertThat(store.find("kept", "")).isNotNull();
    }

    @Test
    void oversizedTraceIsKeptInMemoryButNotWritten() throws Exception {
        TraceStore store = new TraceStore(TraceRecordJsonCodec.INSTANCE, root.resolve("traces"), 7, 1 * MB, 4 * MB,
                task -> {
                    task.run();
                    return true;
                }, () -> { }, clock);
        store.start();
        store.add(trace("huge").errorStackTrace("z".repeat(400 * 1024)).build());
        store.add(trace("small").build());

        assertThat(store.find("huge", "")).isNotNull();
        assertThat(Files.readString(segments().get(0))).contains("\"small\"").doesNotContain("\"huge\"");
    }

    @Test
    void rejectsInvalidQueries() {
        TraceStore store = TraceStore.inMemory(1024 * 1024);

        assertThatThrownBy(() -> store.search(query("bogus", "", "", ""))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.search(query("all", "", "", "abc"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.search(query("all", "", "", "1_x"))).isInstanceOf(IllegalArgumentException.class);
        assertThat(TraceStore.normalizeQuery("  A".repeat(150))).hasSize(TraceStore.MAX_QUERY_CHARS);
    }

    @Test
    void rawPrefilterIsOnlyUsedForPlainAsciiQueries() {
        assertThat(TraceStore.asciiNeedle("select")).isNotNull();
        assertThat(TraceStore.asciiNeedle("a\"b")).isNull();
        assertThat(TraceStore.asciiNeedle("中文")).isNull();
        byte[] haystack = "{\"tag\":\"SELECT * FROM T\"}".getBytes(StandardCharsets.UTF_8);
        assertThat(TraceStore.containsIgnoreAsciiCase(haystack, TraceStore.asciiNeedle("from t"))).isTrue();
        assertThat(TraceStore.containsIgnoreAsciiCase(haystack, TraceStore.asciiNeedle("update"))).isFalse();
    }

    @Test
    void summaryCountsUnfinishedSpansAndTruncatesLongErrors() {
        TraceNode unfinished = new TraceNode("ASYNC", "late", "", 1, 0, false, false, true, "", "", 1, List.of());
        TraceNode parent = new TraceNode("HTTP", "root", "", 1, 0, false, false, false, "", "", 1,
                List.of(unfinished, unfinished));
        TraceSummary summary = TraceSummary.of(trace("x").treeNodes(List.of(parent)).errorMessage("m".repeat(500))
                .finishedAt(START).build());

        assertThat(summary.unfinishedSpanCount()).isEqualTo(2);
        assertThat(summary.errorMessage()).hasSize(301);
    }

    /** Budget for {@code hot} records the size of a typical test trace with one SQL span. */
    private static long hotBytes(int hot) {
        return hot * trace("t0").treeNodes(List.of(sql("select * from orders_0"))).build().estimatedBytes();
    }

    private TraceStore store(int hot, long maxBytes) {
        return store(hot, maxBytes, task -> {
            task.run();
            return true;
        });
    }

    private TraceStore store(int hot, long maxBytes, Predicate<Runnable> writer) {
        return new TraceStore(TraceRecordJsonCodec.INSTANCE, root.resolve("traces"), 7, maxBytes, hotBytes(hot), writer,
                () -> { }, clock);
    }

    private static TraceStore.TraceQuery query(String kind, String source, String text, String before) {
        return new TraceStore.TraceQuery(kind, source, text, before, 50);
    }

    private static TraceRecord.Builder trace(String traceId) {
        return TraceRecord.builder().traceId(traceId).sourceType("HTTP").name("GET /" + traceId).tag("GET")
                .status(200).durationMs(900).slow(true);
    }

    private static TraceNode sql(String statement) {
        return new TraceNode("DB", "SQL", statement, 5, 1, false, false, false, "", "main", 1, List.of());
    }

    private static String json(TraceRecord trace) throws IOException {
        var output = new java.io.ByteArrayOutputStream();
        TraceRecordJsonCodec.INSTANCE.write(output, trace);
        return output.toString(StandardCharsets.UTF_8);
    }

    private List<Path> segments() throws IOException {
        Path directory = root.resolve("traces");
        if (!Files.isDirectory(directory)) return List.of();
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(path -> path.getFileName().toString().startsWith("traces-")).sorted().toList();
        }
    }

    private List<String> segmentNames() throws IOException {
        return segments().stream().map(path -> path.getFileName().toString()).toList();
    }

    private static void awaitIndexed(TraceStore store) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (store.storageStatus().indexing() && System.nanoTime() < deadline) Thread.sleep(10);
        assertThat(store.storageStatus().indexing()).isFalse();
    }

    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
