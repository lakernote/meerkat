package io.github.lakernote.meerkat.trace;

import io.github.lakernote.meerkat.trace.internal.TraceContext;
import io.github.lakernote.meerkat.trace.internal.TraceCompletion;
import io.github.lakernote.meerkat.trace.internal.TraceNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class TraceBlockTest {

    @AfterEach
    void clearTrace() {
        TraceContext.clear();
    }

    @Test
    void callFailureMarksTheExactSpanAndRethrowsTheSameException() {
        IllegalStateException failure = new IllegalStateException("inventory unavailable");
        AtomicReference<TraceCompletion> completion = new AtomicReference<>();
        TraceContext.startRoot("POST /orders", SpanKind.HTTP);
        String rootSpanId = TraceContext.currentSpanId();

        Throwable thrown = catchThrowable(() -> TraceBlock.call("inventory.reserve", () -> {
            throw failure;
        }));

        assertThat(thrown).isSameAs(failure);
        assertThat(TraceContext.currentSpanId()).isEqualTo(rootSpanId);
        TraceContext.currentRootTraceHandle().requireTree();
        TraceContext.stopRoot(0, null, completion::set);
        assertFailedSpan(completion.get(), "inventory.reserve", IllegalStateException.class, "inventory unavailable");
    }

    @Test
    void runFailureMarksErrorsAndRethrowsTheSameError() {
        AssertionError failure = new AssertionError("assertion failed");
        AtomicReference<TraceCompletion> completion = new AtomicReference<>();
        TraceContext.startRoot("POST /orders", SpanKind.HTTP);

        Throwable thrown = catchThrowable(() -> TraceBlock.run("order.validate", () -> {
            throw failure;
        }));

        assertThat(thrown).isSameAs(failure);
        TraceContext.currentRootTraceHandle().requireTree();
        TraceContext.stopRoot(0, null, completion::set);
        assertFailedSpan(completion.get(), "order.validate", AssertionError.class, "assertion failed");
    }

    @Test
    void caughtInnerFailureLeavesTheSuccessfulOuterSpanUnmarked() {
        IllegalArgumentException failure = new IllegalArgumentException("invalid line item");
        AtomicReference<TraceCompletion> completion = new AtomicReference<>();
        TraceContext.startRoot("POST /orders", SpanKind.HTTP);

        TraceBlock.run("order.create", () -> {
            Throwable thrown = catchThrowable(() -> TraceBlock.run("line-item.validate", () -> {
                throw failure;
            }));
            assertThat(thrown).isSameAs(failure);
        });

        TraceContext.currentRootTraceHandle().requireTree();
        TraceContext.stopRoot(0, null, completion::set);
        TraceCompletion result = completion.get();
        TraceNode root = result.nodes().get(0);
        TraceNode outer = root.children().get(0);
        TraceNode inner = outer.children().get(0);
        assertThat(result.error()).isTrue();
        assertThat(result.reasons()).containsExactly(TraceContext.CODE_ERROR_REASON);
        assertThat(root.error()).isTrue();
        assertThat(outer.name()).isEqualTo("order.create");
        assertThat(outer.error()).isFalse();
        assertThat(inner.name()).isEqualTo("line-item.validate");
        assertThat(inner.error()).isTrue();
        assertThat(inner.unfinishedAtTraceEnd()).isFalse();
    }

    @Test
    void monitoringFailureNeverReplacesTheBusinessError() {
        Error failure = new UnreadableStackError("business failure");
        TraceContext.startRoot("POST /orders", SpanKind.HTTP);

        Throwable thrown = catchThrowable(() -> TraceBlock.run("order.fail", () -> {
            throw failure;
        }));

        assertThat(thrown).isSameAs(failure);
        assertThat(TraceContext.active()).isFalse();
    }

    @Test
    void sneakyCheckedFailureIsMarkedAndRethrownUnchanged() {
        IOException failure = new IOException("checked failure");
        AtomicReference<TraceCompletion> completion = new AtomicReference<>();
        TraceContext.startRoot("POST /orders", SpanKind.HTTP);

        Throwable thrown = catchThrowable(() -> TraceBlock.call("order.load", () -> {
            throwUnchecked(failure);
            return "unreachable";
        }));

        assertThat(thrown).isSameAs(failure);
        TraceContext.currentRootTraceHandle().requireTree();
        TraceContext.stopRoot(0, null, completion::set);
        assertFailedSpan(completion.get(), "order.load", IOException.class, "checked failure");
    }

    @Test
    void successfulBlockKeepsItsReturnValueAndExplicitTree() {
        AtomicReference<TraceCompletion> completion = new AtomicReference<>();
        TraceContext.startRoot("POST /orders", SpanKind.HTTP);

        String result = TraceBlock.call("order.success", () -> "created");

        TraceContext.currentRootTraceHandle().requireTree();
        TraceContext.stopRoot(0, null, completion::set);
        assertThat(result).isEqualTo("created");
        assertThat(completion.get().error()).isFalse();
        assertThat(completion.get().tree()).contains("order.success");
    }

    private static void assertFailedSpan(TraceCompletion result, String name,
                                         Class<? extends Throwable> failureType, String message) {
        assertThat(result.error()).isTrue();
        assertThat(result.reasons()).containsExactly(TraceContext.CODE_ERROR_REASON);
        assertThat(result.errorType()).isEqualTo(failureType.getName());
        assertThat(result.errorMessage()).isEqualTo(message);
        assertThat(result.nodes().get(0).error()).isTrue();
        assertThat(result.nodes().get(0).children()).singleElement().satisfies(span -> {
            assertThat(span.name()).isEqualTo(name);
            assertThat(span.error()).isTrue();
            assertThat(span.errorType()).isEqualTo(failureType.getName());
            assertThat(span.unfinishedAtTraceEnd()).isFalse();
        });
    }

    private static final class UnreadableStackError extends Error {
        private UnreadableStackError(String message) {
            super(message);
        }

        @Override
        public StackTraceElement[] getStackTrace() {
            throw new IllegalStateException("monitoring failure");
        }
    }

    private static void throwUnchecked(Throwable failure) {
        TraceBlockTest.<RuntimeException>sneakyThrow(failure);
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void sneakyThrow(Throwable failure) throws E {
        throw (E) failure;
    }
}
