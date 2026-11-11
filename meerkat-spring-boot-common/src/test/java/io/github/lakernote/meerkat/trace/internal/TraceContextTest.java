package io.github.lakernote.meerkat.trace.internal;

import io.github.lakernote.meerkat.trace.SpanKind;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class TraceContextTest {

    @AfterEach
    void clearContext() {
        TraceContext.resetRootScopes();
        TraceContext.clear();
    }

    @Test
    void shouldSkipRenderingForNormalOperationBelowThreshold() {
        TraceContext.startRoot("GET /api/users", SpanKind.HTTP);
        TraceContext.startScopedSpan("UserService#list", SpanKind.SERVICE, "").close();
        AtomicReference<TraceCompletion> result = new AtomicReference<>();

        TraceContext.stopRoot(Long.MAX_VALUE, null, result::set);

        assertThat(result.get().tree()).isEmpty();
        assertThat(result.get().slow()).isFalse();
        assertThat(result.get().error()).isFalse();
    }

    @Test
    void shouldRetainSuccessfulTreeWhenUserExplicitlyAddsCodeSpan() {
        TraceContext.startRoot("GET /api/real-flow", SpanKind.HTTP);
        TraceContext.startExplicitScopedSpan("Redis SET/GET", SpanKind.REDIS, "order cache").close();
        AtomicReference<TraceCompletion> result = new AtomicReference<>();

        TraceContext.stopRoot(Long.MAX_VALUE, null, result::set);

        assertThat(result.get().tree()).contains("GET /api/real-flow", "Redis SET/GET");
        assertThat(result.get().slow()).isFalse();
        assertThat(result.get().error()).isFalse();
    }

    @Test
    void shouldRenderTreeWhenChildMarksTraceSlow() {
        TraceContext.startRoot("GET /api/users", SpanKind.HTTP);
        TraceContext.startScopedSpan("UserService#list", SpanKind.SERVICE, "").close();
        TraceContext.markSlow("SLOW_SQL");
        AtomicReference<TraceCompletion> result = new AtomicReference<>();

        TraceContext.stopRoot(Long.MAX_VALUE, null, result::set);

        assertThat(result.get().tree()).contains("GET /api/users", "UserService#list");
        assertThat(result.get().slow()).isTrue();
        assertThat(result.get().reasons()).containsExactly("SLOW_SQL");
    }

    @Test
    void shouldRenderTreeWhenChildMarksTraceAsError() {
        TraceContext.startRoot("GET /api/users", SpanKind.HTTP);
        IllegalStateException failure = new IllegalStateException("boom");
        TraceContext.startScopedSpan("UserRepository#list", SpanKind.DATABASE, "").close();
        TraceContext.markError("SQL_ERROR", failure);
        AtomicReference<TraceCompletion> result = new AtomicReference<>();

        TraceContext.stopRoot(Long.MAX_VALUE, null, result::set);

        assertThat(result.get().tree()).contains("GET /api/users", "UserRepository#list");
        assertThat(result.get().error()).isTrue();
        assertThat(result.get().errorType()).isEqualTo(IllegalStateException.class.getName());
        assertThat(result.get().reasons()).containsExactly("SQL_ERROR");
    }

    @Test
    void shouldTreatZeroThresholdAsDisabledForNormalOperations() {
        TraceContext.startRoot("diagnostic", SpanKind.HTTP);
        AtomicReference<TraceCompletion> result = new AtomicReference<>();

        TraceContext.stopRoot(0, null, result::set);

        assertThat(result.get().tree()).isEmpty();
        assertThat(result.get().slow()).isFalse();
    }

    @Test
    void startRootMustNotReplaceAnActiveTrace() {
        assertThat(TraceContext.startRoot("GET /outer", SpanKind.HTTP)).isTrue();
        assertThat(TraceContext.startRoot("GET /inner", SpanKind.HTTP)).isFalse();
        AtomicReference<TraceCompletion> result = new AtomicReference<>();

        TraceContext.currentRootTraceHandle().requireTree();
        TraceContext.stopRoot(Long.MAX_VALUE, null, result::set);

        assertThat(result.get().tree()).contains("GET /outer").doesNotContain("GET /inner");
        assertThat(TraceContext.active()).isFalse();
    }

    @Test
    void scopedSpanCloseMustRestoreItsParentEvenWhenTheSpanWasDropped() {
        TraceContext.startRoot("GET /deep", SpanKind.HTTP);
        String rootId = TraceContext.currentSpanId();
        ScopedSpan[] spans = new ScopedSpan[TraceTree.DEFAULT_MAX_DEPTH + 2];
        for (int i = 0; i < spans.length; i++) {
            spans[i] = TraceContext.startScopedSpan("level-" + i, SpanKind.SERVICE, "");
        }
        assertThat(spans[spans.length - 1].spanId()).as("beyond max depth").isEmpty();

        for (int i = spans.length - 1; i >= 0; i--) spans[i].close();

        assertThat(TraceContext.currentSpanId()).isEqualTo(rootId);
    }

    @Test
    void closingAnOuterScopedSpanFirstLeavesTheThreadOnTheRootAfterTheInnerCloses() {
        TraceContext.startRoot("GET /out-of-order", SpanKind.HTTP);
        String rootId = TraceContext.currentSpanId();
        ScopedSpan outer = TraceContext.startScopedSpan("outer", SpanKind.SERVICE, "");
        ScopedSpan inner = TraceContext.startScopedSpan("inner", SpanKind.SERVICE, "");

        outer.close();
        assertThat(TraceContext.currentSpanId()).as("inner is still open").isEqualTo(inner.spanId());
        inner.close();
        inner.close();
        outer.close();

        assertThat(TraceContext.currentSpanId()).isEqualTo(rootId);
        ScopedSpan next = TraceContext.startScopedSpan("next", SpanKind.SERVICE, "");
        next.close();
        TraceContext.currentRootTraceHandle().requireTree();
        AtomicReference<TraceCompletion> result = new AtomicReference<>();
        TraceContext.stopRoot(Long.MAX_VALUE, null, result::set);
        assertThat(result.get().nodes()).singleElement().satisfies(root ->
                assertThat(root.children()).extracting(child -> child.name()).containsExactly("outer", "next"));
    }

    @Test
    void slowTraceLogUsesTheTraceIdOfTheTraceNotOfTheCompletingThread() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(TraceContext.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        boolean additive = logger.isAdditive();
        logger.setAdditive(false);
        logger.addAppender(appender);
        appender.start();
        try {
            RootTraceHandle handle = TraceContext.createRoot("GET /async", SpanKind.HTTP, "");
            handle.trackActive("trace-owner-1", "HTTP", null);
            Thread.sleep(5);
            Thread completer = new Thread(() -> {
                TraceIds.putTraceId("trace-other-thread");
                try {
                    handle.stop(1, null, ignored -> { });
                } finally {
                    TraceIds.clear();
                }
            });
            completer.start();
            completer.join();

            assertThat(appender.list).singleElement().satisfies(event ->
                    assertThat(event.getFormattedMessage())
                            .contains("traceId=trace-owner-1")
                            .doesNotContain("trace-other-thread"));
        } finally {
            logger.detachAppender(appender);
            logger.setAdditive(additive);
            appender.stop();
        }
    }

    @Test
    void shouldKeepFullErrorEvidenceAndIncludeTheBoundedTreeInTheLog() {
        Logger logger = (Logger) LoggerFactory.getLogger(TraceContext.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        boolean additive = logger.isAdditive();
        logger.setAdditive(false);
        logger.addAppender(appender);
        appender.start();
        try {
            IllegalStateException failure = new IllegalStateException("raw-error-message");
            TraceContext.startRoot("POST /orders", SpanKind.HTTP);
            TraceContext.startScopedSpan("OrderService#create", SpanKind.SERVICE, "").close();
            AtomicReference<TraceCompletion> result = new AtomicReference<>();

            TraceContext.stopRoot(Long.MAX_VALUE, failure, result::set);

            assertThat(result.get().tree()).contains("POST /orders", "OrderService#create");
            assertThat(result.get().stackTrace()).contains("raw-error-message");
            assertThat(appender.list).singleElement().satisfies(event -> {
                assertThat(event.getFormattedMessage())
                        .startsWith("Trace error ")
                        .contains(IllegalStateException.class.getName(), "traceId=", "POST /orders",
                                "OrderService#create")
                        .doesNotContain("raw-error-message");
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally {
            logger.detachAppender(appender);
            logger.setAdditive(additive);
            appender.stop();
        }
    }

    @Test
    void shouldIncludeTheBoundedTreeInSlowCallLogs() throws InterruptedException {
        Logger logger = (Logger) LoggerFactory.getLogger(TraceContext.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        boolean additive = logger.isAdditive();
        logger.setAdditive(false);
        logger.addAppender(appender);
        appender.start();
        try {
            TraceContext.startRoot("GET /slow", SpanKind.HTTP);
            TraceContext.startScopedSpan("http.client.requests", SpanKind.REMOTE, "/catalog").close();
            Thread.sleep(5);

            TraceContext.stopRoot(1);

            assertThat(appender.list).singleElement().satisfies(event ->
                    assertThat(event.getFormattedMessage())
                            .containsPattern("^Slow trace \\d+ms > 1ms (\\[[A-Z_,]+] )?traceId=\\S+\n")
                            .contains("GET /slow", "http.client.requests", "/catalog")
                            .doesNotContain("unfinishedSpans", "suppressed"));
        } finally {
            logger.detachAppender(appender);
            logger.setAdditive(additive);
            appender.stop();
        }
    }

    @Test
    void shouldRateLimitTraceSummariesAndReportPreviousSuppression() {
        TraceDiagnosticLog.Limiter limiter = new TraceDiagnosticLog.Limiter(2, 100);

        assertThat(limiter.tryAcquire(0)).isZero();
        assertThat(limiter.tryAcquire(1)).isZero();
        assertThat(limiter.tryAcquire(2)).isEqualTo(-1);
        assertThat(limiter.tryAcquire(100)).isEqualTo(1);
        assertThat(limiter.tryAcquire(101)).isZero();
        assertThat(limiter.tryAcquire(102)).isEqualTo(-1);
        // A stale caller cannot roll the limiter back into an older window.
        assertThat(limiter.tryAcquire(99)).isEqualTo(-1);
        assertThat(limiter.tryAcquire(200)).isEqualTo(2);
    }

    @Test
    void preformattedFailureEvidenceMustNotWalkTheThrowableStackAgain() {
        CountingFailure failure = new CountingFailure("boom");
        TraceContext.startRoot("POST /orders", SpanKind.HTTP);
        BoundedThrowableFormatter.FailureEvidence evidence = BoundedThrowableFormatter.capture(failure);
        TraceContext.markError("HTTP_ERROR", evidence);
        AtomicReference<TraceCompletion> result = new AtomicReference<>();

        TraceContext.stopRoot(Long.MAX_VALUE, failure, result::set);

        assertThat(failure.stackReads).hasValue(1);
        assertThat(result.get().stackTrace()).contains("CountingFailure");
    }

    private static final class CountingFailure extends RuntimeException {
        private final AtomicInteger stackReads = new AtomicInteger();

        private CountingFailure(String message) {
            super(message);
        }

        @Override
        public StackTraceElement[] getStackTrace() {
            stackReads.incrementAndGet();
            return super.getStackTrace();
        }
    }

    @Test
    void rootScopesNestAndRestoreThePositionAndTraceIdSavedWhenOpened() {
        RootTraceHandle first = TraceContext.createRoot("GET /first", SpanKind.HTTP, "");
        RootTraceHandle second = TraceContext.createRoot("GET /second", SpanKind.HTTP, "");
        try {
            TraceContext.openRootScope(first, "trace-first", true);
            TraceContext.openRootScope(first, "trace-first", true);
            assertThat(TraceContext.closeRootScope(first)).isFalse();
            assertThat(TraceContext.currentRootTraceHandle().trace()).isSameAs(first.trace());

            TraceContext.openRootScope(second, "trace-second", false);
            assertThat(TraceContext.currentRootTraceHandle().trace()).isSameAs(second.trace());
            assertThat(io.github.lakernote.meerkat.trace.TraceIdContext.currentTraceId()).isEqualTo("trace-second");
            assertThat(TraceContext.closeRootScope(second)).isTrue();
            assertThat(io.github.lakernote.meerkat.trace.TraceIdContext.currentTraceId()).isEqualTo("trace-first");

            assertThat(TraceContext.closeRootScope(first)).isTrue();
            assertThat(TraceContext.active()).isFalse();
            assertThat(io.github.lakernote.meerkat.trace.TraceIdContext.currentTraceId()).isNull();
            assertThat(TraceContext.closeRootScope(first)).isFalse();
        } finally {
            TraceIds.clear();
        }
    }

    @Test
    void closingAnOuterRootScopeDropsInnerScopesThatWereNeverClosed() {
        RootTraceHandle outer = TraceContext.createRoot("GET /outer", SpanKind.HTTP, "");
        RootTraceHandle leaked = TraceContext.createRoot("GET /leaked", SpanKind.HTTP, "");
        try {
            TraceContext.openRootScope(outer, "trace-outer", true);
            TraceContext.openRootScope(leaked, "trace-leaked", false);

            assertThat(TraceContext.closeRootScope(outer)).isTrue();
            assertThat(TraceContext.active()).isFalse();
            assertThat(io.github.lakernote.meerkat.trace.TraceIdContext.currentTraceId()).isNull();
            assertThat(TraceContext.closeRootScope(leaked)).isFalse();
        } finally {
            TraceIds.clear();
        }
    }

    @Test
    void resettingRootScopesRestoresTheStateBeforeTheFirstScope() {
        RootTraceHandle first = TraceContext.createRoot("GET /first", SpanKind.HTTP, "");
        RootTraceHandle second = TraceContext.createRoot("GET /second", SpanKind.HTTP, "");
        TraceIds.putTraceId("trace-before");
        try {
            TraceContext.openRootScope(first, "trace-first", false);
            TraceContext.openRootScope(second, "trace-second", false);

            TraceContext.resetRootScopes();
            TraceContext.resetRootScopes();

            assertThat(TraceContext.active()).isFalse();
            assertThat(io.github.lakernote.meerkat.trace.TraceIdContext.currentTraceId()).isEqualTo("trace-before");
            assertThat(TraceContext.closeRootScope(first)).isFalse();
        } finally {
            TraceIds.clear();
        }
    }

    @Test
    void rootScopeKeepsADeeperPositionInTheSameTrace() {
        RootTraceHandle root = TraceContext.createRoot("GET /tx", SpanKind.HTTP, "");
        try {
            TraceContext.openRootScope(root, "trace-tx", true);
            try (ScopedSpan transaction = TraceContext.startScopedSpan("tx", SpanKind.TRANSACTION, "")) {
                String transactionSpan = TraceContext.currentSpanId();
                TraceContext.openRootScope(root, "trace-tx", true);
                assertThat(TraceContext.currentSpanId()).isEqualTo(transactionSpan);
                TraceContext.closeRootScope(root);
                assertThat(TraceContext.currentSpanId()).isEqualTo(transactionSpan);
            }
            TraceContext.closeRootScope(root);
        } finally {
            TraceIds.clear();
        }
    }
}
