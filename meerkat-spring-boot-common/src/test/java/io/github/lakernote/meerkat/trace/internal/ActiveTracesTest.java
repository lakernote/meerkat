package io.github.lakernote.meerkat.trace.internal;

import io.github.lakernote.meerkat.trace.SpanKind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ActiveTracesTest {
    @AfterEach
    void clear() {
        TraceContext.clear();
    }

    @Test
    void listsATrackedRootWithItsInnermostRunningSpanUntilItCompletes() {
        TraceContext.startRoot("GET /orders", SpanKind.HTTP, "/orders");
        RootTraceHandle handle = TraceContext.currentRootTraceHandle();
        handle.trackActive("trace-live-1", "HTTP", ThreadResourceSnapshot.capture(Thread.currentThread().getId()));
        try (ScopedSpan service = TraceContext.startScopedSpan("OrderService.list", SpanKind.SERVICE, "")) {
            TraceContext.startScopedSpan("finished", SpanKind.CODE, "").close();
            try (ScopedSpan sql = TraceContext.startScopedSpan("SELECT", SpanKind.DATABASE, "select * from orders")) {
                ActiveTraces.ActiveTrace live = find("trace-live-1");
                assertThat(live.source()).isEqualTo("HTTP");
                assertThat(live.name()).isEqualTo("GET /orders");
                assertThat(live.tag()).isEqualTo("/orders");
                assertThat(live.threadId()).isEqualTo(Thread.currentThread().getId());
                assertThat(live.threadName()).isEqualTo(Thread.currentThread().getName());
                assertThat(live.detached()).isFalse();
                assertThat(live.spanCount()).isEqualTo(4);
                assertThat(live.currentStep().kind()).isEqualTo("Database");
                assertThat(live.currentStep().name()).isEqualTo("SELECT");
                assertThat(live.currentStep().tag()).isEqualTo("select * from orders");
            }
            assertThat(find("trace-live-1").currentStep().name()).isEqualTo("OrderService.list");
        }
        assertThat(find("trace-live-1").currentStep()).isNull();

        TraceContext.stopRoot(0);
        assertThat(find("trace-live-1")).isNull();
    }

    @Test
    void untrackedRootsAreNotListedAndDiscardedRootsAreRemoved() {
        TraceContext.startRoot("job", SpanKind.SCHEDULE);
        RootTraceHandle untracked = TraceContext.currentRootTraceHandle();
        assertThat(ActiveTraces.snapshot()).noneMatch(trace -> trace.name().equals("job"));
        untracked.discard();

        TraceContext.startRoot("job", SpanKind.SCHEDULE);
        RootTraceHandle handle = TraceContext.currentRootTraceHandle();
        handle.trackActive("trace-live-2", "JOB", null);
        assertThat(find("trace-live-2")).isNotNull();
        handle.discard();
        assertThat(find("trace-live-2")).isNull();
    }

    @Test
    void marksAnEntryDetachedOnceItsThreadHasReturned() {
        TraceContext.startRoot("GET /async", SpanKind.HTTP);
        RootTraceHandle handle = TraceContext.currentRootTraceHandle();
        handle.trackActive("trace-live-3", "HTTP", null);
        TraceContext.detach(handle);

        assertThat(TraceContext.active()).isFalse();
        assertThat(find("trace-live-3").detached()).isTrue();
        handle.stop(0, null, completion -> { });
        assertThat(find("trace-live-3")).isNull();
    }

    @Test
    void sortsLongestRunningFirst() throws Exception {
        TraceTree older = new TraceTree("older", SpanKind.HTTP, "");
        older.track("older", "HTTP", null);
        Thread.sleep(5);
        TraceTree newer = new TraceTree("newer", SpanKind.HTTP, "");
        newer.track("newer", "HTTP", null);
        try {
            List<String> ids = ActiveTraces.snapshot().stream().map(ActiveTraces.ActiveTrace::traceId)
                    .filter(id -> id.equals("older") || id.equals("newer")).toList();
            assertThat(ids).containsExactly("older", "newer");
        } finally {
            older.discard();
            newer.discard();
        }
    }

    @Test
    void stopsTrackingAtTheCap() {
        List<TraceTree> trees = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < ActiveTraces.MAX_TRACKED + 5; i++) {
                TraceTree tree = new TraceTree("bulk", SpanKind.HTTP, "");
                tree.track("bulk-" + i, "HTTP", null);
                trees.add(tree);
            }
            assertThat(ActiveTraces.snapshot()).hasSizeLessThanOrEqualTo(ActiveTraces.MAX_TRACKED);
        } finally {
            trees.forEach(TraceTree::discard);
        }
        assertThat(ActiveTraces.snapshot()).noneMatch(trace -> trace.traceId().startsWith("bulk-"));
    }

    private static ActiveTraces.ActiveTrace find(String traceId) {
        return ActiveTraces.snapshot().stream().filter(trace -> trace.traceId().equals(traceId)).findFirst()
                .orElse(null);
    }
}
