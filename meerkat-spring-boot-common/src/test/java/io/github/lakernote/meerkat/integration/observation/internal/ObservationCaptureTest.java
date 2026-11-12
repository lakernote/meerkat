package io.github.lakernote.meerkat.integration.observation.internal;

import io.github.lakernote.meerkat.config.MeerkatProperties;
import io.github.lakernote.meerkat.integration.redis.internal.LettuceCommandCapture;
import io.github.lakernote.meerkat.trace.SpanKind;
import io.github.lakernote.meerkat.trace.internal.CaptureLimits;
import io.github.lakernote.meerkat.trace.internal.CaptureTarget;
import io.github.lakernote.meerkat.trace.internal.CaptureThreadState;
import io.github.lakernote.meerkat.trace.internal.TraceContext;
import io.github.lakernote.meerkat.trace.internal.TraceIds;
import io.github.lakernote.meerkat.trace.internal.TraceNode;
import io.github.lakernote.meerkat.trace.internal.store.TraceStore;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.output.StatusOutput;
import io.lettuce.core.protocol.Command;
import io.lettuce.core.protocol.CommandArgs;
import io.lettuce.core.protocol.CommandType;
import io.lettuce.core.tracing.LettuceObservationContext;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.transport.SenderContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ObservationCaptureTest {
    private final ObservationRegistry registry = ObservationRegistry.create();
    private final AtomicReference<List<TraceNode>> captured = new AtomicReference<>();

    ObservationCaptureTest() {
        registry.observationConfig().observationHandler(new ObservationTraceHandler(
                TraceStore.inMemory(1024 * 1024), new MeerkatProperties(), List.of()));
    }

    @AfterEach
    void clear() {
        TraceContext.clear();
        TraceIds.clear();
        CaptureThreadState.clear();
    }

    private void startCapture() {
        TraceContext.startRoot("GET /orders", SpanKind.HTTP);
        TraceContext.currentRootTraceHandle().beginCapture((completion, nodes) -> captured.set(nodes),
                new CaptureLimits(1024, 5, 10_000, 1_000_000));
    }

    private TraceNode finish() {
        TraceContext.stopRoot(Long.MAX_VALUE);
        return captured.get().get(0);
    }

    private Observation remoteObservation() {
        SenderContext<Map<String, String>> sender = new SenderContext<>(Map::put);
        sender.setCarrier(new HashMap<>());
        return Observation.createNotStarted("http.client.requests", () -> sender, registry).start();
    }

    @Test
    void handsTheRemoteSpanTargetToClientInterceptorsInsideTheScope() {
        startCapture();
        Observation observation = remoteObservation();

        try (Observation.Scope ignored = observation.openScope()) {
            CaptureTarget target = CaptureThreadState.claimRemote();
            assertThat(target).isNotNull();
            assertThat(CaptureThreadState.claimRemote()).as("claimed only once").isNull();
            target.capture("request", "GET http://inventory/items");
        }
        observation.stop();

        assertThat(CaptureThreadState.claimRemote()).isNull();
        TraceNode root = finish();
        assertThat(root.children()).singleElement().satisfies(remote ->
                assertThat(remote.captures()).containsEntry("request", "GET http://inventory/items"));
    }

    @Test
    void reinstallsTheRemoteTargetWhenTheObservationIsInScopeAgainAfterAReset() {
        startCapture();
        Observation observation = remoteObservation();

        try (Observation.Scope scope = observation.openScope()) {
            scope.reset();
            assertThat(CaptureThreadState.claimRemote()).isNull();
        }
        try (Observation.Scope ignored = observation.openScope()) {
            assertThat(CaptureThreadState.claimRemote()).isNotNull();
        }
        observation.stop();
    }

    @Test
    void restoresTheOuterRemoteTargetWhenTheScopeCloses() {
        startCapture();
        CaptureTarget outer = TraceContext.currentCaptureTarget();
        CaptureThreadState.openRemote(outer);
        Observation observation = remoteObservation();

        try (Observation.Scope ignored = observation.openScope()) {
            assertThat(CaptureThreadState.claimRemote()).isNotNull().isNotSameAs(outer);
        }
        observation.stop();

        assertThat(CaptureThreadState.claimRemote()).isSameAs(outer);
    }

    @Test
    void scopeClosedOnAnotherThreadLeavesTheInstallingThreadsStateAlone() throws Exception {
        startCapture();
        CaptureTarget foreign = TraceContext.currentCaptureTarget();
        Observation observation = remoteObservation();
        Observation.Scope scope = observation.openScope();

        Thread other = new Thread(() -> {
            CaptureThreadState.openRemote(foreign);
            observation.openScope().close();
            // The other thread must not restore (clear) a target this observation never installed there.
            assertThat(CaptureThreadState.claimRemote()).isSameAs(foreign);
        });
        AtomicReference<Throwable> failure = new AtomicReference<>();
        other.setUncaughtExceptionHandler((thread, error) -> failure.set(error));
        other.start();
        other.join();
        assertThat(failure.get()).isNull();

        assertThat(CaptureThreadState.claimRemote()).as("still installed on this thread").isNotNull();
        scope.close();
        observation.stop();
        assertThat(CaptureThreadState.claimRemote()).isNull();
    }

    @Test
    void installsNothingWhenTheTraceIsNotCaptured() {
        TraceContext.startRoot("GET /orders", SpanKind.HTTP);
        Observation observation = remoteObservation();

        try (Observation.Scope ignored = observation.openScope()) {
            assertThat(CaptureThreadState.claimRemote()).isNull();
        }
        observation.stop();
    }

    @Test
    void capturesLettuceCommandAndReplyOnRedisSpans() {
        startCapture();
        LettuceObservationContext context = new LettuceObservationContext("redis");
        context.setCommand(command("OK", null));

        Observation.createNotStarted("lettuce.command", () -> context, registry).start().stop();

        TraceNode root = finish();
        assertThat(root.children()).singleElement().satisfies(redis -> {
            assertThat(redis.captures().get("command")).startsWith("SET").contains("order:1");
            assertThat(redis.captures()).containsEntry("result", "OK");
        });
    }

    @Test
    void lettuceCaptureRecordsErrorsAndIgnoresUnusableContexts() {
        startCapture();
        CaptureTarget target = TraceContext.currentCaptureTarget();

        LettuceCommandCapture.capture(new Observation.Context(), target);
        LettuceCommandCapture.capture(new LettuceObservationContext("redis"), target);
        assertThat(finishCapturesOf(target)).isEmpty();

        startCapture();
        target = TraceContext.currentCaptureTarget();
        LettuceObservationContext failed = new LettuceObservationContext("redis");
        failed.setCommand(command(null, "ERR wrong type"));
        LettuceCommandCapture.capture(failed, target);

        LettuceObservationContext noOutput = new LettuceObservationContext("redis");
        noOutput.setCommand(new Command<>(CommandType.PING, null));
        LettuceCommandCapture.capture(noOutput, target);

        Map<String, String> captures = finish().captures();
        assertThat(captures).containsEntry("error", "ERR wrong type").containsEntry("command", "PING");
    }

    private Map<String, String> finishCapturesOf(CaptureTarget ignored) {
        Map<String, String> captures = finish().captures();
        TraceContext.clear();
        return captures;
    }

    private static Command<String, String, String> command(String reply, String error) {
        StatusOutput<String, String> output = new StatusOutput<>(StringCodec.UTF8);
        if (reply != null) output.set(ByteBuffer.wrap(reply.getBytes(StandardCharsets.UTF_8)));
        if (error != null) output.setError(error);
        return new Command<>(CommandType.SET, output,
                new CommandArgs<>(StringCodec.UTF8).addKey("order:1").addValue("v"));
    }
}
