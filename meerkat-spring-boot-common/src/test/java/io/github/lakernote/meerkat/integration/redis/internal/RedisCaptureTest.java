package io.github.lakernote.meerkat.integration.redis.internal;

import io.github.lakernote.meerkat.metrics.internal.OperationStats;
import io.github.lakernote.meerkat.trace.SpanKind;
import io.github.lakernote.meerkat.trace.internal.CaptureLimits;
import io.github.lakernote.meerkat.trace.internal.CaptureThreadState;
import io.github.lakernote.meerkat.trace.internal.TraceContext;
import io.github.lakernote.meerkat.trace.internal.TraceNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RedisCaptureTest {
    private static final byte[] KEY = "cache-key".getBytes(StandardCharsets.UTF_8);
    private final AtomicReference<List<TraceNode>> captured = new AtomicReference<>();
    private final RedissonTraceConnectionFactoryPostProcessor processor =
            new RedissonTraceConnectionFactoryPostProcessor(() -> new OperationStats(10));

    @AfterEach
    void clear() {
        TraceContext.clear();
        CaptureThreadState.clear();
    }

    private void startCapture() {
        TraceContext.startRoot("GET /cache", SpanKind.HTTP);
        TraceContext.currentRootTraceHandle().beginCapture((completion, nodes) -> captured.set(nodes),
                new CaptureLimits(1024, 5, 10_000, 1_000_000));
    }

    private Map<String, String> redisCaptures() {
        TraceContext.stopRoot(Long.MAX_VALUE);
        TraceNode root = captured.get().get(0);
        assertThat(root.children()).hasSize(1);
        return root.children().get(0).captures();
    }

    @Test
    void capturesCommandArgumentsAndResult() {
        startCapture();
        RedisConnection connection = processor.wrap(connection(null));

        assertThat(connection.get(KEY)).isEqualTo("cache-value".getBytes(StandardCharsets.UTF_8));

        Map<String, String> captures = redisCaptures();
        assertThat(captures.get("command")).startsWith("GET ").contains("cache-key");
        assertThat(captures.get("result")).contains("cache-value");
        assertThat(captures).doesNotContainKey("error");
    }

    @Test
    void capturesCommandWithoutArgumentsAndFailure() {
        startCapture();
        RedisConnection connection = processor.wrap(connection(new IllegalStateException("Redis down")));

        assertThatThrownBy(connection::ping).hasMessage("Redis down");

        Map<String, String> captures = redisCaptures();
        assertThat(captures).containsEntry("command", "PING")
                .containsEntry("error", "java.lang.IllegalStateException: Redis down")
                .doesNotContainKey("result");
    }

    @Test
    void recordsNothingOutsideCapturedTraces() {
        TraceContext.startRoot("GET /cache", SpanKind.HTTP);
        TraceContext.currentRootTraceHandle().requireTree();
        AtomicReference<io.github.lakernote.meerkat.trace.internal.TraceCompletion> completion =
                new AtomicReference<>();
        processor.wrap(connection(null)).get(KEY);
        TraceContext.stopRoot(0, null, completion::set);

        assertThat(completion.get().nodes().get(0).children()).singleElement()
                .satisfies(node -> assertThat(node.captures()).isEmpty());
    }

    private static RedisConnection connection(RuntimeException failure) {
        return (RedisConnection) Proxy.newProxyInstance(
                RedisConnection.class.getClassLoader(), new Class<?>[]{RedisConnection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "get" -> "cache-value".getBytes(StandardCharsets.UTF_8);
                    case "ping" -> {
                        if (failure != null) throw failure;
                        yield "PONG";
                    }
                    case "isPipelined", "isQueueing", "isClosed" -> false;
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "fake connection";
                    default -> null;
                });
    }
}
