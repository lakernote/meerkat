package io.github.lakernote.meerkat.integration.feign.internal;

import feign.Client;
import feign.Request;
import feign.Response;
import io.github.lakernote.meerkat.trace.SpanKind;
import io.github.lakernote.meerkat.trace.internal.CaptureLimits;
import io.github.lakernote.meerkat.trace.internal.CaptureTarget;
import io.github.lakernote.meerkat.trace.internal.CaptureThreadState;
import io.github.lakernote.meerkat.trace.internal.TraceContext;
import io.github.lakernote.meerkat.trace.internal.TraceNode;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FeignCaptureTest {
    private final AtomicReference<List<TraceNode>> captured = new AtomicReference<>();

    @AfterEach
    void clear() {
        TraceContext.clear();
        CaptureThreadState.clear();
    }

    private CaptureTarget startCapture(int maxBodyBytes) {
        TraceContext.startRoot("GET /orders", SpanKind.HTTP);
        TraceContext.currentRootTraceHandle().beginCapture((completion, nodes) -> captured.set(nodes),
                new CaptureLimits(maxBodyBytes, 5, 10_000, 1_000_000));
        return TraceContext.currentCaptureTarget();
    }

    private Map<String, String> finish() {
        TraceContext.stopRoot(Long.MAX_VALUE);
        return captured.get().get(0).captures();
    }

    private static Request request(String body, Map<String, Collection<String>> headers) {
        return Request.create(Request.HttpMethod.POST, "http://inventory/items", headers,
                body == null ? null : body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8, null);
    }

    @Test
    void capturesRequestAndTeesResponseBodyWhenTheRemoteTargetIsClaimed() throws IOException {
        CaptureTarget target = startCapture(1024);
        CaptureThreadState.openRemote(target);
        Map<String, Collection<String>> headers = new LinkedHashMap<>();
        headers.put("Content-Type", List.of("application/json"));
        Client client = new FeignObservationCapability(ObservationRegistry.NOOP).enrich((Client) (request, options) ->
                Response.builder().status(201).request(request)
                        .headers(Map.of("Content-Type", List.of("text/plain")))
                        .body("created", StandardCharsets.UTF_8).build());

        Response response = client.execute(request("{\"id\":1}", headers), new Request.Options());
        try (InputStream body = response.body().asInputStream()) {
            assertThat(new String(body.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("created");
        }

        Map<String, String> captures = finish();
        assertThat(captures.get("request")).isEqualTo("POST http://inventory/items\nContent-Type: application/json");
        assertThat(captures).containsEntry("request.body", "{\"id\":1}");
        assertThat(captures.get("response")).startsWith("201").contains("text/plain");
        assertThat(captures.get("response.body")).contains("created");
    }

    @Test
    void truncatesLargeRequestBodiesAndSkipsEmptyOnes() {
        CaptureTarget target = startCapture(4);

        FeignCapture.request(target, request("abcdefgh", Map.of()));
        Map<String, String> captures = finish();
        assertThat(captures.get("request.body")).startsWith("abcd").endsWith("[body truncated, 8 total]");

        target = startCapture(4);
        FeignCapture.request(target, request(null, Map.of()));
        assertThat(finish()).containsOnlyKeys("request");
    }

    @Test
    void labelsFileUploadsAndDownloadsWithoutRecordingThem() throws IOException {
        CaptureTarget target = startCapture(1024);
        FeignCapture.request(target, request("--x\r\nfile-bytes", Map.of("Content-Type",
                List.of("multipart/form-data; boundary=x"))));
        Response response = FeignCapture.response(target, Response.builder().status(200).request(request(null, Map.of()))
                .headers(Map.of("Content-Type", List.of("text/csv"),
                        "Content-Disposition", List.of("attachment; filename=r.csv")))
                .body("a,b", StandardCharsets.UTF_8).build());
        response.body().asInputStream().readAllBytes();

        Map<String, String> captures = finish();
        assertThat(captures.get("request.body"))
                .isEqualTo("[file upload, multipart/form-data, 15 bytes; content not recorded]");
        assertThat(captures.get("response.body"))
                .isEqualTo("[file download, text/csv, 3 bytes, attachment; filename=r.csv; content not recorded]");
    }

    @Test
    void returnsResponsesWithoutBodyUnchanged() {
        CaptureTarget target = startCapture(1024);
        Response response = Response.builder().status(204).request(request(null, Map.of()))
                .headers(Map.of()).build();

        assertThat(FeignCapture.response(target, response)).isSameAs(response);
        assertThat(finish()).containsEntry("response", "204").doesNotContainKey("response.body");
    }

    @Test
    void recordsFailuresOfCapturedCalls() {
        CaptureTarget target = startCapture(1024);
        CaptureThreadState.openRemote(target);
        Client client = new FeignObservationCapability(ObservationRegistry.NOOP).enrich((Client) (request, options) -> {
            throw new IOException("connection refused");
        });

        assertThatThrownBy(() -> client.execute(request(null, Map.of()), new Request.Options()))
                .isInstanceOf(IOException.class);

        assertThat(finish()).containsEntry("error", "java.io.IOException: connection refused");
    }

    @Test
    void leavesUncapturedCallsAlone() throws IOException {
        Response original = Response.builder().status(200).request(request(null, Map.of()))
                .headers(Map.of()).body("ok", StandardCharsets.UTF_8).build();
        Client client = new FeignObservationCapability(ObservationRegistry.NOOP)
                .enrich((Client) (request, options) -> original);

        assertThat(client.execute(request(null, Map.of()), new Request.Options())).isSameAs(original);
    }
}
