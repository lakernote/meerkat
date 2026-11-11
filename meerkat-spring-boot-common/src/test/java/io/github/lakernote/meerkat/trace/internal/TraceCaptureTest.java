package io.github.lakernote.meerkat.trace.internal;

import io.github.lakernote.meerkat.trace.SpanKind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TraceCaptureTest {
    private static final CaptureLimits LIMITS = new CaptureLimits(1024, 10, 1000, 100_000);
    private final AtomicReference<List<TraceNode>> captured = new AtomicReference<>();
    private final AtomicReference<TraceCompletion> capturedCompletion = new AtomicReference<>();
    private final CaptureSink sink = (completion, nodes) -> {
        capturedCompletion.set(completion);
        captured.set(nodes);
    };

    @AfterEach
    void clear() {
        TraceContext.clear();
        CaptureThreadState.clear();
    }

    private static void startCapturedRoot(CaptureSink sink, CaptureLimits limits) {
        TraceContext.startRoot("GET /orders", SpanKind.HTTP);
        TraceContext.currentRootTraceHandle().beginCapture(sink, limits);
    }

    @Test
    void returnsNoTargetWhenNotCapturing() {
        assertThat(TraceContext.currentCaptureTarget()).isNull();
        TraceContext.startRoot("GET /orders", SpanKind.HTTP);

        assertThat(TraceContext.currentCaptureTarget()).isNull();
        assertThat(TraceContext.currentRootTraceHandle().captureTarget()).isNull();
        try (ScopedSpan span = TraceContext.startScopedSpan("child", SpanKind.SERVICE, "")) {
            assertThat(span.captureTarget()).isNull();
        }
        assertThat(ScopedSpan.NOOP.captureTarget()).isNull();
        assertThat(new SpanHandle() {
            @Override
            public String spanId() {
                return "";
            }

            @Override
            public String parentSpanId() {
                return "";
            }

            @Override
            public void close() {
            }
        }.captureTarget()).isNull();
    }

    @Test
    void deliversCapturesOnlyToTheSink() {
        startCapturedRoot(sink, LIMITS);
        CaptureTarget root = TraceContext.currentCaptureTarget();
        root.capture("request", "GET /orders");
        try (ScopedSpan sql = TraceContext.startScopedSpan("SELECT orders", SpanKind.DATABASE, "")) {
            sql.captureTarget().capture("sql", "select * from orders where id = 7");
            assertThat(TraceContext.currentCaptureTarget()).isNotNull();
        }
        DetachedSpan redis = TraceContext.startDetachedSpan("GET", SpanKind.REDIS, "");
        redis.captureTarget().capture("command", "GET order:7");
        redis.close();
        TraceContext.markSlow("SLOW_SQL");
        AtomicReference<TraceCompletion> completion = new AtomicReference<>();

        TraceContext.stopRoot(Long.MAX_VALUE, null, completion::set);

        assertThat(completion.get().nodes()).isNotEmpty();
        assertThat(completion.get().tree()).doesNotContain("select * from orders");
        assertThat(allNodes(completion.get().nodes())).allSatisfy(node -> assertThat(node.captures()).isEmpty());
        assertThat(capturedCompletion.get()).isSameAs(completion.get());
        TraceNode rootNode = captured.get().get(0);
        assertThat(rootNode.captures()).containsExactly(Map.entry("request", "GET /orders"));
        assertThat(rootNode.children()).extracting(TraceNode::captures).containsExactly(
                Map.of("sql", "select * from orders where id = 7"), Map.of("command", "GET order:7"));
        assertThat(root.capturing()).isFalse();
        assertThat(root.limits()).isEqualTo(LIMITS);
    }

    @Test
    void ignoresValuesAfterCompletionAndBlankKeys() {
        startCapturedRoot(sink, LIMITS);
        CaptureTarget root = TraceContext.currentCaptureTarget();
        root.capture(null, "x");
        root.capture(" ", "x");
        root.capture("kept", 42);

        TraceContext.stopRoot(Long.MAX_VALUE);
        root.capture("late", "x");

        assertThat(captured.get().get(0).captures()).containsExactly(Map.entry("kept", "42"));
        assertThat(TraceContext.currentCaptureTarget()).isNull();
    }

    @Test
    void rendersLazyValuesWhenTheTraceCompletes() {
        startCapturedRoot(sink, LIMITS);
        StringBuilder body = new StringBuilder("partial");
        CaptureTarget root = TraceContext.currentCaptureTarget();
        root.capture("body", (CaptureValue) maxChars -> body.toString());
        root.capture("broken", (CaptureValue) maxChars -> {
            throw new IllegalStateException("closed");
        });
        root.capture("nothing", (CaptureValue) maxChars -> null);
        root.capture("null", null);
        body.append(" and complete");

        TraceContext.stopRoot(Long.MAX_VALUE);

        assertThat(captured.get().get(0).captures()).containsExactly(
                Map.entry("body", "partial and complete"),
                Map.entry("broken", "[capture failed: IllegalStateException]"),
                Map.entry("nothing", "null"),
                Map.entry("null", "null"));
    }

    @Test
    void boundsCapturedTextPerValueAndPerTrace() {
        startCapturedRoot(sink, new CaptureLimits(0, 0, 64, 150));
        CaptureTarget root = TraceContext.currentCaptureTarget();
        root.capture("a", "a".repeat(100));
        root.capture("b", "b".repeat(100));
        root.capture("c", "c".repeat(10));

        TraceContext.stopRoot(Long.MAX_VALUE);

        Map<String, String> captures = captured.get().get(0).captures();
        assertThat(captures.get("a")).isEqualTo("a".repeat(64) + "… [truncated, 100 chars]");
        assertThat(captures.get("b")).startsWith("b".repeat(62) + "…").contains("[truncated, 100 chars]");
        assertThat(captures.get("c")).isEqualTo(CaptureBudget.OMITTED);
    }

    @Test
    void limitsNumberOfCapturesPerSpan() {
        startCapturedRoot(sink, LIMITS);
        CaptureTarget root = TraceContext.currentCaptureTarget();
        for (int i = 0; i < Span.MAX_CAPTURES_PER_SPAN + 5; i++) root.capture("key-" + i, "v" + i);
        root.capture("key-0", "replaced");

        TraceContext.stopRoot(Long.MAX_VALUE);

        Map<String, String> captures = captured.get().get(0).captures();
        assertThat(captures).hasSize(Span.MAX_CAPTURES_PER_SPAN).containsEntry("key-0", "replaced")
                .doesNotContainKey("key-" + Span.MAX_CAPTURES_PER_SPAN);
    }

    @Test
    void keepsCompletingWhenTheSinkFails() {
        startCapturedRoot((completion, nodes) -> {
            throw new IllegalStateException("store failed");
        }, LIMITS);
        TraceContext.currentCaptureTarget().capture("request", "x");
        AtomicReference<TraceCompletion> completion = new AtomicReference<>();

        TraceContext.stopRoot(Long.MAX_VALUE, null, completion::set);

        assertThat(completion.get()).isNotNull();
        assertThat(TraceContext.active()).isFalse();
    }

    @Test
    void ignoresIncompleteOrLateCaptureRequests() {
        TraceTree trace = new TraceTree("root", SpanKind.HTTP, "");
        trace.beginCapture(null, LIMITS);
        assertThat(trace.capturing()).isFalse();
        trace.beginCapture(sink, null);
        assertThat(trace.capturing()).isFalse();
        assertThat(trace.takeCapture()).isNull();
        assertThat(CaptureTarget.of(trace, trace.rootSpan())).isNull();
        assertThat(CaptureTarget.of(null, trace.rootSpan())).isNull();

        trace.beginCapture(sink, LIMITS);
        assertThat(CaptureTarget.of(trace, null)).isNull();
        trace.captureSpan(null, "k", "v");
        trace.discard();
        assertThat(trace.capturing()).isFalse();
        trace.beginCapture(sink, LIMITS);
        assertThat(trace.capturing()).isFalse();
    }

    @Test
    void normalizesLimits() {
        CaptureLimits limits = new CaptureLimits(-1, -2, 10, -3);

        assertThat(limits).isEqualTo(new CaptureLimits(0, 0, 64, 0));
        CaptureBudget budget = new CaptureBudget(10, 0);
        assertThat(budget.render("anything")).isEqualTo(CaptureBudget.OMITTED);
    }

    @Test
    void handsRemoteTargetToTheClientOnce() {
        startCapturedRoot(sink, LIMITS);
        CaptureTarget target = TraceContext.currentCaptureTarget();
        CaptureTarget other = TraceContext.currentRootTraceHandle().captureTarget();

        assertThat(CaptureThreadState.claimRemote()).isNull();
        assertThat(CaptureThreadState.openRemote(target)).isNull();
        assertThat(CaptureThreadState.openRemote(other)).isSameAs(target);
        CaptureThreadState.restoreRemote(target);
        assertThat(CaptureThreadState.claimRemote()).isSameAs(target);
        assertThat(CaptureThreadState.claimRemote()).isNull();

        CaptureThreadState.openRemote(target);
        CaptureThreadState.restoreRemote(null);
        assertThat(CaptureThreadState.claimRemote()).isNull();

        CaptureThreadState.openRemote(target);
        TraceContext.stopRoot(Long.MAX_VALUE);
        assertThat(CaptureThreadState.claimRemote()).isNull();
    }

    @Test
    void remembersTheStatementOfTheCapturedQuery() {
        startCapturedRoot(sink, LIMITS);
        CaptureTarget target = TraceContext.currentCaptureTarget();
        Object statement = new Object();

        assertThat(CaptureThreadState.hasPendingStatement()).isFalse();
        assertThat(CaptureThreadState.statementTarget(statement)).isNull();
        CaptureThreadState.rememberStatement(statement, target);
        assertThat(CaptureThreadState.hasPendingStatement()).isTrue();
        assertThat(CaptureThreadState.statementTarget(statement)).isSameAs(target);
        assertThat(CaptureThreadState.statementTarget(new Object())).isNull();
        CaptureThreadState.rememberStatement(null, target);
        assertThat(CaptureThreadState.hasPendingStatement()).isFalse();
        CaptureThreadState.rememberStatement(statement, null);
        assertThat(CaptureThreadState.hasPendingStatement()).isFalse();

        CaptureThreadState.rememberStatement(statement, target);
        TraceContext.stopRoot(Long.MAX_VALUE);
        assertThat(CaptureThreadState.statementTarget(statement)).isNull();
        assertThat(CaptureThreadState.hasPendingStatement()).isFalse();
    }

    private static List<TraceNode> allNodes(List<TraceNode> nodes) {
        List<TraceNode> all = new java.util.ArrayList<>();
        for (TraceNode node : nodes) {
            all.add(node);
            all.addAll(allNodes(node.children()));
        }
        return all;
    }
}
