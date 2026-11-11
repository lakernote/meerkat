package io.github.lakernote.meerkat.capture.internal;

import io.github.lakernote.meerkat.trace.internal.TraceNode;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CaptureStoreTest {
    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));

    private CaptureRecord record(String id, String sessionId, Instant finishedAt) {
        return new CaptureRecord(id, sessionId, "trace-" + id, "GET", "/api/" + id, 200, 5, false, finishedAt,
                null, null, null, null);
    }

    @Test
    void keepsNewestRecordsUpToMax() {
        CaptureStore store = new CaptureStore(2, Duration.ofHours(1), clock);
        store.add(record("1-1", "1", clock.instant()));
        store.add(record("1-2", "1", clock.instant()));
        store.add(record("2-1", "2", clock.instant()));

        assertThat(store.size()).isEqualTo(2);
        assertThat(store.list(null)).extracting(CaptureRecord.Summary::id).containsExactly("2-1", "1-2");
        assertThat(store.list(" ")).hasSize(2);
        assertThat(store.list("1")).extracting(CaptureRecord.Summary::id).containsExactly("1-2");
        assertThat(store.list("9")).isEmpty();
        assertThat(store.find("1-2").path()).isEqualTo("/api/1-2");
        assertThat(store.find("1-1")).isNull();
    }

    private CaptureRecord large(String id, int chars) {
        TraceNode child = new TraceNode("Sql", "select", "", 1, 0, false, false, false, "", "main", 1, List.of(),
                java.util.Map.of("result", "r".repeat(chars)));
        TraceNode root = new TraceNode("Http", "GET /a", "", 2, 0, false, false, false, "", "main", 1,
                List.of(child), java.util.Map.of("request.body", "b".repeat(chars)));
        return new CaptureRecord(id, "1", "t", "GET", "/a", 200, 2, false, clock.instant(), null, null, null,
                List.of(root));
    }

    @Test
    void keepsNewestRecordsWithinTheHeapBudget() {
        long one = large("x", 10_000).estimatedBytes();
        assertThat(one).isGreaterThan(40_000);
        CaptureStore store = new CaptureStore(100, one * 2 + one / 2, Duration.ofHours(1), clock);
        store.add(large("1", 10_000));
        store.add(large("2", 10_000));
        store.add(large("3", 10_000));

        assertThat(store.list(null)).extracting(CaptureRecord.Summary::id).containsExactly("3", "2");
        assertThat(store.estimatedBytes()).isEqualTo(one * 2);

        store.add(large("huge", 100_000));
        assertThat(store.list(null)).extracting(CaptureRecord.Summary::id).containsExactly("huge");
        store.clear();
        assertThat(store.estimatedBytes()).isZero();
    }

    @Test
    void dropsRecordsOlderThanRetention() {
        CaptureStore store = new CaptureStore(10, Duration.ofMinutes(10), clock);
        store.add(record("old", "1", clock.instant()));
        clock.advance(Duration.ofMinutes(5));
        store.add(record("new", "1", clock.instant()));
        clock.advance(Duration.ofMinutes(6));

        assertThat(store.list(null)).extracting(CaptureRecord.Summary::id).containsExactly("new");
        assertThat(store.find("old")).isNull();
        clock.advance(Duration.ofMinutes(10));
        assertThat(store.find("new")).isNull();
        assertThat(store.size()).isZero();
    }

    @Test
    void stopsExpiringAtRecordWithoutFinishTime() {
        CaptureStore store = new CaptureStore(10, Duration.ofMinutes(1), clock);
        store.add(record("old", "1", clock.instant()));
        store.add(record("unknown", "1", null));
        clock.advance(Duration.ofHours(1));

        assertThat(store.list(null)).hasSize(1);
        assertThat(store.find("unknown")).isNotNull();
    }

    @Test
    void clearsAllRecords() {
        CaptureStore store = new CaptureStore(0, null);
        store.add(record("a", "1", Instant.now()));

        assertThat(store.size()).isEqualTo(1);
        assertThat(store.clear()).isEqualTo(1);
        assertThat(store.size()).isZero();
        assertThat(store.clear()).isZero();
    }

    @Test
    void defaultsInvalidRetention() {
        CaptureStore zero = new CaptureStore(5, Duration.ZERO, clock);
        CaptureStore negative = new CaptureStore(5, Duration.ofSeconds(-1), clock);
        zero.add(record("a", "1", clock.instant()));
        negative.add(record("a", "1", clock.instant()));
        clock.advance(Duration.ofMinutes(119));

        assertThat(zero.size()).isEqualTo(1);
        assertThat(zero.list(null)).hasSize(1);
        assertThat(negative.list(null)).hasSize(1);
        clock.advance(Duration.ofMinutes(2));
        assertThat(zero.list(null)).isEmpty();
    }

    @Test
    void recordNormalizesNullsAndSummarizes() {
        TraceNode node = new TraceNode("HTTP", "GET /x", "", 1, 0, false, false, false, "", "main", 1, List.of());
        Instant finished = clock.instant();
        CaptureRecord record = new CaptureRecord("1-1", "1", "t", "GET", "/x", 500, 9, true, finished,
                "java.lang.IllegalStateException", "boom", "stack", List.of(node));
        CaptureRecord empty = record("1-2", "1", finished);

        assertThat(empty.errorType()).isEmpty();
        assertThat(empty.errorMessage()).isEmpty();
        assertThat(empty.errorStackTrace()).isEmpty();
        assertThat(empty.nodes()).isEmpty();
        assertThat(record.nodes()).containsExactly(node);
        assertThat(record.summary()).isEqualTo(new CaptureRecord.Summary("1-1", "1", "t", "GET", "/x", 500, 9, true,
                "java.lang.IllegalStateException", finished));
    }
}
