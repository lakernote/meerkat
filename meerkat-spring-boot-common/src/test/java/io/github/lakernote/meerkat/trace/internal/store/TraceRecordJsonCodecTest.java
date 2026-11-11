package io.github.lakernote.meerkat.trace.internal.store;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import io.github.lakernote.meerkat.trace.internal.TraceNode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TraceRecordJsonCodecTest {
    private static final TraceRecordJsonCodec CODEC = TraceRecordJsonCodec.INSTANCE;
    private static final String TRICKY = "quote\" backslash\\ slash/ nl\n cr\r tab\t ctl\u0001 中文 emoji😀 lone\uD800 end";

    @Test
    void roundTripsEveryRecordAndNodeComponent() throws IOException {
        TraceRecord trace = fullTrace();

        assertThat(CODEC.read(encode(trace))).isEqualTo(trace);
    }

    @Test
    void writesSingleLineStandardJsonWithCamelCaseKeysAndIsoInstant() throws IOException {
        String json = new String(encode(fullTrace()), StandardCharsets.UTF_8);

        assertThat(json).doesNotContain("\n", "\r").startsWith("{\"id\":42,\"traceId\":\"trace-1\"");
        DocumentContext document = JsonPath.parse(json);
        assertThat(document.read("$.finishedAt", String.class)).isEqualTo("2026-10-21T10:00:00.123Z");
        assertThat(document.read("$.errorMessage", String.class)).isEqualTo(TRICKY);
        assertThat(document.read("$.treeNodes[0].children[0].name", String.class)).isEqualTo("SQL select");
        assertThat(document.read("$.treeNodes[0].startOffsetMs", Long.class)).isZero();
        assertThat(document.read("$.highAllocation", Boolean.class)).isTrue();
    }

    @Test
    void archiveFormatIsStable() throws IOException {
        TraceNode node = new TraceNode("Database", "q\"1", "", 2, 7L, false, true, false, "", "w", 3, List.of());
        TraceRecord trace = TraceRecord.builder()
                .id(1).traceId("t").sourceType("HTTP").name("n").tag("GET").status(500).durationMs(9)
                .slow(true).highAllocation(true).treeNodes(List.of(node))
                .finishedAt(Instant.parse("2026-10-21T10:00:00Z"))
                .threadId(4).threadName("th").cpuTimeMs(5).allocatedBytes(6).errorType("E").errorMessage("m\n")
                .build();

        assertThat(new String(encode(trace), StandardCharsets.UTF_8)).isEqualTo("{\"id\":1,\"traceId\":\"t\","
                + "\"name\":\"n\",\"tag\":\"GET\",\"status\":500,\"durationMs\":9,\"slow\":true,\"error\":false,"
                + "\"treeNodes\":[{\"kind\":\"Database\",\"name\":\"q\\\"1\",\"tag\":\"\",\"durationMs\":2,"
                + "\"startOffsetMs\":7,\"slow\":false,\"error\":true,\"unfinishedAtTraceEnd\":false,"
                + "\"errorType\":\"\",\"threadName\":\"w\",\"threadId\":3,\"children\":[]}],"
                + "\"finishedAt\":\"2026-10-21T10:00:00Z\",\"threadId\":4,\"cpuTimeMs\":5,\"allocatedBytes\":6,"
                + "\"sourceType\":\"HTTP\",\"errorType\":\"E\",\"errorMessage\":\"m\\n\",\"threadName\":\"th\","
                + "\"errorStackTrace\":\"\",\"highCpu\":false,\"highAllocation\":true}");
    }

    @Test
    void ignoresUnknownFieldsAndDefaultsMissingOnes() throws IOException {
        String json = "{ \"traceId\" : \"t-1\", \"future\": {\"nested\": [1, -2.5e3, true, null, \"x\", {}]},"
                + " \"treeNodes\": [{\"name\": \"n\", \"extra\": [[]]}], \"sourceType\": \"HTTP\", \"slow\": true,"
                + " \"logs\": [{\"offsetMs\": 1, \"level\": \"INFO\", \"message\": \"from an older archive\"}] }";

        TraceRecord trace = CODEC.read(json.getBytes(StandardCharsets.UTF_8));

        assertThat(trace.traceId()).isEqualTo("t-1");
        assertThat(trace.sourceType()).isEqualTo("HTTP");
        assertThat(trace.slow()).isTrue();
        assertThat(trace.finishedAt()).isNull();
        assertThat(trace.errorMessage()).isEmpty();
        assertThat(trace.treeNodes()).singleElement().satisfies(node -> {
            assertThat(node.name()).isEqualTo("n");
            assertThat(node.kind()).isEqualTo("OTHER");
            assertThat(node.children()).isEmpty();
        });
    }

    @Test
    void readsUnicodeEscapesProducedByOtherJsonWriters() throws IOException {
        String json = "{\"traceId\":\"\\u4e2d\\u6587\\ud83d\\ude00\\/\"}";

        assertThat(CODEC.read(json.getBytes(StandardCharsets.UTF_8)).traceId()).isEqualTo("中文😀/");
    }

    @Test
    void rejectsMalformedLines() {
        for (String malformed : List.of("", "{not-json}", "{\"traceId\":\"open", "{\"traceId\":1}",
                "{\"status\":\"x\"}", "{\"finishedAt\":\"yesterday\"}", "{} trailing", "{\"slow\":maybe}",
                "{\"traceId\":\"\\q\"}", "[]")) {
            assertThatThrownBy(() -> CODEC.read(malformed.getBytes(StandardCharsets.UTF_8)))
                    .as(malformed)
                    .isInstanceOf(IOException.class);
        }
    }

    @Test
    void rejectsPathologicallyDeepNestingWithoutStackOverflow() {
        String deepNodes = "{\"treeNodes\":" + "[{\"children\":".repeat(10_000);
        String deepUnknown = "{\"x\":" + "[".repeat(100_000);

        assertThatThrownBy(() -> CODEC.read(deepNodes.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IOException.class).hasMessageContaining("Nesting");
        assertThatThrownBy(() -> CODEC.read(deepUnknown.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IOException.class).hasMessageContaining("Nesting");
    }

    @Test
    void doesNotCloseTheTargetStream() throws IOException {
        CloseTrackingStream output = new CloseTrackingStream();

        CODEC.write(output, fullTrace());

        assertThat(output.closed).isFalse();
        assertThat(output.size()).isPositive();
    }

    private static byte[] encode(TraceRecord trace) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        CODEC.write(output, trace);
        return output.toByteArray();
    }

    private static TraceRecord fullTrace() {
        TraceNode child = new TraceNode("Database", "SQL select", "jdbc", 2, 1L, true, false, true,
                "SQLTimeout", "worker-2", 78, List.of());
        TraceNode root = new TraceNode("Http", "HTTP GET", TRICKY, 845, 0L, true, true, false,
                "HTTP_5XX", "http-nio-1", 77, List.of(child));
        return TraceRecord.builder()
                .id(42).traceId("trace-1").sourceType("HTTP").name("GET /orders/{id}").tag("GET")
                .status(500).durationMs(845).slow(true).error(true).highAllocation(true).treeNodes(List.of(root))
                .finishedAt(Instant.parse("2026-10-21T10:00:00.123Z"))
                .threadId(77).threadName("http-nio-1").cpuTimeMs(Long.MAX_VALUE).allocatedBytes(Long.MIN_VALUE)
                .errorType("HTTP_5XX").errorMessage(TRICKY)
                .errorStackTrace("java.lang.IllegalStateException\n\tat x.y(Z.java:1)")
                .build();
    }

    private static final class CloseTrackingStream extends ByteArrayOutputStream {
        private boolean closed;

        @Override
        public void close() {
            closed = true;
        }
    }
}
