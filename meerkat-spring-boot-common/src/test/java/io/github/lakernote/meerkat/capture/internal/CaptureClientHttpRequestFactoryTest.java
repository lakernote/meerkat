package io.github.lakernote.meerkat.capture.internal;

import io.github.lakernote.meerkat.trace.SpanKind;
import io.github.lakernote.meerkat.trace.internal.CaptureLimits;
import io.github.lakernote.meerkat.trace.internal.CaptureTarget;
import io.github.lakernote.meerkat.trace.internal.CaptureThreadState;
import io.github.lakernote.meerkat.trace.internal.TraceContext;
import io.github.lakernote.meerkat.trace.internal.TraceNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.StreamingHttpOutputMessage;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CaptureClientHttpRequestFactoryTest {
    private final AtomicReference<MockClientHttpRequest> created = new AtomicReference<>();
    private final AtomicReference<ClientHttpResponse> response = new AtomicReference<>();
    private final ClientHttpRequestFactory factory = new CaptureClientHttpRequestFactory((uri, method) -> {
        MockClientHttpRequest request = new MockClientHttpRequest(method, uri);
        request.setResponse(response.get());
        created.set(request);
        return request;
    });
    private final AtomicReference<List<TraceNode>> captured = new AtomicReference<>();
    private CaptureTarget target;

    @BeforeEach
    void startCapturedTrace() {
        TraceContext.startRoot("GET /api", SpanKind.HTTP);
        TraceContext.currentRootTraceHandle().beginCapture((completion, nodes) -> captured.set(nodes),
                new CaptureLimits(8, 5, 1000, 100_000));
        target = TraceContext.currentCaptureTarget();
    }

    @AfterEach
    void clear() {
        TraceContext.clear();
        CaptureThreadState.clear();
    }

    private Map<String, String> finish() {
        TraceContext.stopRoot(Long.MAX_VALUE);
        return captured.get().get(0).captures();
    }

    @Test
    void returnsTheOriginalRequestOutsideACapturedTrace() throws IOException {
        TraceContext.clear();
        TraceContext.startRoot("GET /plain", SpanKind.HTTP);
        response.set(new MockClientHttpResponse(new byte[0], HttpStatus.OK));

        ClientHttpRequest request = factory.createRequest(URI.create("http://svc/a"), HttpMethod.GET);

        assertThat(request).isSameAs(created.get());
    }

    @Test
    void passesThroughWithoutClaimedTarget() throws IOException {
        MockClientHttpResponse ok = new MockClientHttpResponse(new byte[0], HttpStatus.OK);
        response.set(ok);
        ClientHttpRequest request = factory.createRequest(URI.create("http://svc/a"), HttpMethod.POST);
        request.getBody().write(1);

        assertThat(request.execute()).isSameAs(ok);
        assertThat(created.get().getBodyAsBytes()).containsExactly(1);
        assertThat(finish()).isEmpty();
    }

    @Test
    void recordsRequestAndResponseOfClaimedCall() throws IOException {
        MockClientHttpResponse raw = new MockClientHttpResponse(
                "response-body".getBytes(StandardCharsets.UTF_8), HttpStatus.CREATED);
        raw.getHeaders().setContentType(MediaType.TEXT_PLAIN);
        response.set(raw);
        ClientHttpRequest request = factory.createRequest(URI.create("http://svc/orders"), HttpMethod.POST);
        assertThat(request.getMethod()).isEqualTo(HttpMethod.POST);
        assertThat(request.getURI()).isEqualTo(URI.create("http://svc/orders"));
        assertThat(request.getAttributes()).isSameAs(created.get().getAttributes());
        CaptureThreadState.openRemote(target);
        request.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        OutputStream out = request.getBody();
        out.write('{');
        out.write("\"id\":12345}".getBytes(StandardCharsets.UTF_8));
        request.getHeaders().add("X-Token", "abc");

        ClientHttpResponse result = request.execute();

        assertThat(created.get().getBodyAsString()).isEqualTo("{\"id\":12345}");
        assertThat(result).isNotSameAs(raw);
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(result.getStatusText()).isEqualTo(raw.getStatusText());
        assertThat(result.getHeaders()).isSameAs(raw.getHeaders());
        InputStream stream = result.getBody();
        assertThat(result.getBody()).isSameAs(stream);
        assertThat(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("response-body");
        result.close();
        assertThat(CaptureThreadState.claimRemote()).isNull();

        Map<String, String> captures = finish();
        assertThat(captures.keySet()).containsExactly("request", "request.body", "response", "response.body");
        assertThat(captures.get("request")).startsWith("POST http://svc/orders")
                .contains("Content-Type: application/json").contains("X-Token: abc");
        assertThat(captures.get("request.body")).isEqualTo("{\"id\":12\n… [body truncated, 12 total]");
        assertThat(captures.get("response")).startsWith("201").contains("Content-Type: text/plain");
        assertThat(captures.get("response.body")).isEqualTo("response\n… [body truncated, 13 total]");
    }

    @Test
    void streamsLargeBodiesThroughWithoutBufferingThem() throws IOException {
        StreamingRequest streaming = new StreamingRequest();
        ClientHttpRequestFactory streamingFactory = new CaptureClientHttpRequestFactory((uri, method) -> streaming);
        ClientHttpRequest request = streamingFactory.createRequest(URI.create("http://svc/upload"), HttpMethod.PUT);
        CaptureThreadState.openRemote(target);
        StreamingHttpOutputMessage.Body upload = new StreamingHttpOutputMessage.Body() {
            @Override
            public void writeTo(OutputStream out) throws IOException {
                byte[] chunk = new byte[64 * 1024];
                java.util.Arrays.fill(chunk, (byte) 'x');
                for (int i = 0; i < 160; i++) out.write(chunk, 0, chunk.length);
            }

            @Override
            public boolean repeatable() {
                return true;
            }
        };

        assertThat(request).isInstanceOf(StreamingHttpOutputMessage.class);
        ((StreamingHttpOutputMessage) request).setBody(upload);
        request.execute().getBody().readAllBytes();

        assertThat(streaming.body).isNotNull().isNotSameAs(upload);
        assertThat(streaming.body.repeatable()).isTrue();
        assertThat(streaming.written).isEqualTo(160L * 64 * 1024);
        assertThat(finish().get("request.body")).isEqualTo("xxxxxxxx\n… [body truncated, 10485760 total]");
    }

    @Test
    void labelsFileUploadsWithoutRecordingThem() throws IOException {
        response.set(new MockClientHttpResponse(new byte[0], HttpStatus.OK));
        ClientHttpRequest request = factory.createRequest(URI.create("http://svc/files"), HttpMethod.POST);
        CaptureThreadState.openRemote(target);
        request.getHeaders().setContentType(MediaType.MULTIPART_FORM_DATA);
        request.getBody().write(new byte[4096]);

        request.execute();

        assertThat(created.get().getBodyAsBytes()).hasSize(4096);
        assertThat(finish().get("request.body"))
                .isEqualTo("[file upload, multipart/form-data, 4096 bytes; content not recorded]");
    }

    @Test
    void leavesStreamingBodiesAloneWithoutClaimedTarget() throws IOException {
        StreamingRequest streaming = new StreamingRequest();
        ClientHttpRequest request = new CaptureClientHttpRequestFactory((uri, method) -> streaming)
                .createRequest(URI.create("http://svc/upload"), HttpMethod.PUT);
        StreamingHttpOutputMessage.Body upload = out -> out.write(1);

        ((StreamingHttpOutputMessage) request).setBody(upload);

        assertThat(streaming.body).isSameAs(upload);
    }

    @Test
    void skipsEmptyRequestBodyAndHidesBinaryResponses() throws IOException {
        MockClientHttpResponse raw = new MockClientHttpResponse(new byte[] {1, 2, 3}, HttpStatus.OK);
        raw.getHeaders().setContentType(MediaType.IMAGE_PNG);
        response.set(raw);
        ClientHttpRequest request = factory.createRequest(URI.create("http://svc/logo"), HttpMethod.GET);
        CaptureThreadState.openRemote(target);

        request.execute().getBody().readAllBytes();

        Map<String, String> captures = finish();
        assertThat(captures).doesNotContainKey("request.body");
        assertThat(captures.get("request")).isEqualTo("GET http://svc/logo");
        assertThat(captures.get("response.body")).isEqualTo("[file download, image/png, 3 bytes; content not recorded]");
    }

    @Test
    void recordsFailedCall() throws IOException {
        ClientHttpRequest request = new CaptureClientHttpRequestFactory((uri, method) -> new MockClientHttpRequest(method, uri) {
            @Override
            protected ClientHttpResponse executeInternal() throws IOException {
                throw new IOException("connection refused");
            }
        }).createRequest(URI.create("http://svc/down"), HttpMethod.GET);
        CaptureThreadState.openRemote(target);

        assertThatThrownBy(request::execute).isInstanceOf(IOException.class);

        assertThat(finish().get("error")).isEqualTo("java.io.IOException: connection refused");
    }

    @Test
    void returnsRawResponseWhenItCannotBeRead() throws IOException {
        ClientHttpResponse broken = mock(ClientHttpResponse.class);
        when(broken.getHeaders()).thenReturn(new HttpHeaders());
        when(broken.getStatusCode()).thenThrow(new IOException("closed"));
        response.set(broken);
        ClientHttpRequest request = factory.createRequest(URI.create("http://svc/x"), HttpMethod.GET);
        CaptureThreadState.openRemote(target);

        assertThat(request.execute()).isSameAs(broken);
        assertThat(finish()).containsOnlyKeys("request");
    }

    @Test
    void exposesItsDelegate() {
        ClientHttpRequestFactory delegate = (uri, method) -> null;
        assertThat(new CaptureClientHttpRequestFactory(delegate).delegate()).isSameAs(delegate);
    }

    @Test
    void formatsHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("A", "1");
        headers.add("A", "2");
        headers.add("B", "3");

        assertThat(CaptureClientHttpRequestFactory.headers(headers)).isEqualTo("\nA: 1\nA: 2\nB: 3");
        assertThat(CaptureClientHttpRequestFactory.headers(new HttpHeaders())).isEmpty();
    }

    /** A streaming request that, like real clients, writes the body only during execute() and keeps no copy. */
    private static final class StreamingRequest extends MockClientHttpRequest implements StreamingHttpOutputMessage {
        Body body;
        long written;

        StreamingRequest() {
            super(HttpMethod.PUT, URI.create("http://svc/upload"));
            setResponse(new MockClientHttpResponse(new byte[0], HttpStatus.OK));
        }

        @Override
        public void setBody(Body body) {
            this.body = body;
        }

        @Override
        protected ClientHttpResponse executeInternal() throws IOException {
            body.writeTo(new OutputStream() {
                @Override
                public void write(int value) {
                    written++;
                }

                @Override
                public void write(byte[] buffer, int offset, int length) {
                    written += length;
                }
            });
            return super.executeInternal();
        }
    }
}
