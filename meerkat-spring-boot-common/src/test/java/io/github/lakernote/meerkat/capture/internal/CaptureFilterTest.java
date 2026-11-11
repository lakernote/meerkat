package io.github.lakernote.meerkat.capture.internal;

import io.github.lakernote.meerkat.config.MeerkatProperties;
import io.github.lakernote.meerkat.trace.SpanKind;
import io.github.lakernote.meerkat.trace.internal.CaptureThreadState;
import io.github.lakernote.meerkat.trace.internal.TraceContext;
import io.github.lakernote.meerkat.trace.internal.TraceIds;
import io.github.lakernote.meerkat.trace.internal.TraceNode;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.unit.DataSize;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CaptureFilterTest {
    private final CaptureSessions sessions = new CaptureSessions(Duration.ofMinutes(10), 100);
    private final CaptureStore store = new CaptureStore(10, Duration.ofHours(1));
    private final MeerkatProperties.Capture limits = new MeerkatProperties.Capture();
    private final CaptureFilter filter = new CaptureFilter(sessions, store, limits);

    @AfterEach
    void clear() {
        TraceContext.clear();
        TraceIds.clear();
        CaptureThreadState.clear();
    }

    private void startSession(String method, String path) {
        sessions.start(new CaptureRule(method, path, null, null, 10, 60), "tester");
    }

    private void runInTrace(ServletRequest request, ServletResponse response, FilterChain chain) throws Exception {
        TraceIds.putTraceId("trace-1");
        TraceContext.startRoot("HTTP", SpanKind.HTTP);
        Throwable failure = null;
        try {
            filter.doFilter(request, response, chain);
        } catch (Exception | Error thrown) {
            failure = thrown;
            throw thrown;
        } finally {
            TraceContext.stopRoot(Long.MAX_VALUE, failure, completion -> {
                assertThat(completion.nodes()).allSatisfy(node -> assertThat(node.captures()).isEmpty());
            });
        }
    }

    private static Map<String, String> rootCaptures(CaptureRecord record) {
        assertThat(record.nodes()).hasSize(1);
        TraceNode root = record.nodes().get(0);
        return root.captures();
    }

    @Test
    void recordsRequestAndResponseOfMatchingRequest() throws Exception {
        startSession("POST", "/api/**");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/orders");
        request.setQueryString("x=1");
        request.addHeader("X-User", "bob");
        request.setContentType("application/json");
        request.setContent("{\"a\":1}".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();

        runInTrace(request, response, (req, res) -> {
            assertThat(req).isNotSameAs(request);
            byte[] body = req.getInputStream().readAllBytes();
            assertThat(new String(body, StandardCharsets.UTF_8)).isEqualTo("{\"a\":1}");
            ((jakarta.servlet.http.HttpServletResponse) res).setStatus(201);
            res.setContentType("application/json");
            res.getWriter().write("{\"id\":7}");
            res.getWriter().flush();
        });

        assertThat(response.getContentAsString()).isEqualTo("{\"id\":7}");
        CaptureRecord record = store.find("1-1");
        assertThat(record).isNotNull();
        assertThat(record.sessionId()).isEqualTo("1");
        assertThat(record.traceId()).isEqualTo("trace-1");
        assertThat(record.method()).isEqualTo("POST");
        assertThat(record.path()).isEqualTo("/api/orders?x=1");
        assertThat(record.status()).isEqualTo(201);
        assertThat(record.error()).isFalse();
        assertThat(record.finishedAt()).isNotNull();
        Map<String, String> captures = rootCaptures(record);
        assertThat(captures).containsKeys("request", "request.body", "response", "response.body");
        assertThat(captures.get("request")).startsWith("POST /api/orders?x=1 HTTP/1.1")
                .contains("X-User: bob").contains("[client 127.0.0.1]");
        assertThat(captures.get("request.body")).isEqualTo("{\"a\":1}");
        assertThat(captures.get("response")).startsWith("201").contains("Content-Type: application/json");
        assertThat(captures.get("response.body")).isEqualTo("{\"id\":7}");
    }

    @Test
    void reportsWriterErrorsOfTheUnderlyingResponse() throws Exception {
        startSession("", "/**");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/broken");
        java.io.PrintWriter failing = new java.io.PrintWriter(java.io.Writer.nullWriter()) {
            @Override
            public boolean checkError() {
                return true;
            }
        };
        MockHttpServletResponse response = new MockHttpServletResponse() {
            @Override
            public java.io.PrintWriter getWriter() {
                return failing;
            }
        };

        AtomicReference<Boolean> error = new AtomicReference<>();
        runInTrace(request, response, (req, res) -> {
            res.getWriter().write("x");
            error.set(res.getWriter().checkError());
        });

        assertThat(error.get()).isTrue();
    }

    @Test
    void recordsBodiesWrittenThroughStreamsAndReaders() throws Exception {
        limits.setMaxBodySize(DataSize.ofBytes(4));
        startSession("", "/**");
        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/items");
        request.setContent("abcdefgh".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();

        runInTrace(request, response, (req, res) -> {
            char[] chars = new char[16];
            assertThat(req.getReader().read(chars, 0, chars.length)).isEqualTo(8);
            res.getOutputStream().write("0123456789".getBytes(StandardCharsets.UTF_8));
        });

        Map<String, String> captures = rootCaptures(store.find("1-1"));
        assertThat(captures.get("request.body")).isEqualTo("abcd\n… [body truncated, 8 total]");
        assertThat(captures.get("response.body")).isEqualTo("0123\n… [body truncated, 10 total]");
        assertThat(captures.get("response")).startsWith("200");
        assertThat(response.getContentAsString()).isEqualTo("0123456789");
    }

    @Test
    void labelsFileUploadsAndDownloadsWithoutRecordingThem() throws Exception {
        startSession("", "/**");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/files");
        request.setContentType("application/octet-stream");
        request.setContent(new byte[2048]);
        MockHttpServletResponse response = new MockHttpServletResponse();

        runInTrace(request, response, (req, res) -> {
            req.getInputStream().readAllBytes();
            res.setContentType("text/csv");
            ((jakarta.servlet.http.HttpServletResponse) res).setHeader("Content-Disposition",
                    "attachment; filename=report.csv");
            res.getOutputStream().write("a,b\n1,2\n".getBytes(StandardCharsets.UTF_8));
        });

        Map<String, String> captures = rootCaptures(store.find("1-1"));
        assertThat(captures.get("request.body"))
                .isEqualTo("[file upload, application/octet-stream, 2048 bytes; content not recorded]");
        assertThat(captures.get("response.body")).isEqualTo(
                "[file download, text/csv, 8 bytes, attachment; filename=report.csv; content not recorded]");
        assertThat(response.getContentAsString()).isEqualTo("a,b\n1,2\n");
    }

    @Test
    void recordsFormParametersWhenBodyWasNotRead() throws Exception {
        startSession("POST", "/form");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/form");
        request.setContentType("application/x-www-form-urlencoded");
        request.addParameter("a", "1", "2");
        request.addParameter("b", "3");

        runInTrace(request, new MockHttpServletResponse(), (req, res) -> { });

        assertThat(rootCaptures(store.find("1-1")).get("request.params")).isEqualTo("a=1\na=2\nb=3");
    }

    @Test
    void marksFailedRequestAsServerError() {
        startSession("GET", "/boom");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/boom");

        assertThatThrownBy(() -> runInTrace(request, new MockHttpServletResponse(), (req, res) -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        CaptureRecord record = store.find("1-1");
        assertThat(record.status()).isEqualTo(500);
        assertThat(record.error()).isTrue();
        assertThat(record.errorType()).isEqualTo(IllegalStateException.class.getName());
        assertThat(rootCaptures(record).get("response")).startsWith("500");
    }

    @Test
    void marksServerErrorStatusAsError() throws Exception {
        startSession("GET", "/unavailable");

        runInTrace(new MockHttpServletRequest("GET", "/unavailable"), new MockHttpServletResponse(),
                (req, res) -> ((jakarta.servlet.http.HttpServletResponse) res).setStatus(503));

        CaptureRecord record = store.find("1-1");
        assertThat(record.status()).isEqualTo(503);
        assertThat(record.error()).isTrue();
    }

    @Test
    void readsStatusOfAsyncRequestWhenTraceCompletes() throws Exception {
        startSession("GET", "/async");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/async");
        request.setAsyncSupported(true);
        MockHttpServletResponse response = new MockHttpServletResponse();

        runInTrace(request, response, (req, res) -> {
            req.startAsync();
            response.setStatus(202);
        });

        CaptureRecord record = store.find("1-1");
        assertThat(record.status()).isEqualTo(202);
        assertThat(rootCaptures(record).get("response")).startsWith("202");
    }

    @Test
    void stripsContextPathAndDefaultsEmptyPath() throws Exception {
        startSession("GET", "/api/**");
        startSession("GET", "/");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/app/api/x");
        request.setContextPath("/app");
        MockHttpServletRequest root = new MockHttpServletRequest("GET", "/app");
        root.setContextPath("/app");

        runInTrace(request, new MockHttpServletResponse(), (req, res) -> { });
        TraceContext.clear();
        runInTrace(root, new MockHttpServletResponse(), (req, res) -> { });

        assertThat(store.find("1-1").path()).isEqualTo("/api/x");
        assertThat(store.find("2-1").path()).isEqualTo("/");
    }

    @Test
    void passesThroughWithoutActiveSession() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/x");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> seen = new AtomicReference<>();

        runInTrace(request, response, (req, res) -> seen.set(req));

        assertThat(seen.get()).isSameAs(request);
        assertThat(store.size()).isZero();
    }

    @Test
    void passesThroughRequestsThatAreNotCaptured() throws Exception {
        startSession("GET", "/api/**");
        AtomicReference<ServletRequest> seen = new AtomicReference<>();
        FilterChain chain = (req, res) -> seen.set(req);

        MockHttpServletRequest async = new MockHttpServletRequest("GET", "/api/x");
        async.setDispatcherType(DispatcherType.ASYNC);
        runInTrace(async, new MockHttpServletResponse(), chain);
        assertThat(seen.get()).isSameAs(async);

        MockHttpServletRequest dashboard = new MockHttpServletRequest("GET", "/meerkat/api/x");
        startSession("GET", "/meerkat/**");
        runInTrace(dashboard, new MockHttpServletResponse(), chain);
        assertThat(seen.get()).isSameAs(dashboard);

        MockHttpServletRequest other = new MockHttpServletRequest("GET", "/other");
        runInTrace(other, new MockHttpServletResponse(), chain);
        assertThat(seen.get()).isSameAs(other);

        ServletRequest plain = mock(ServletRequest.class);
        filter.doFilter(plain, new MockHttpServletResponse(), chain);
        assertThat(seen.get()).isSameAs(plain);

        MockHttpServletRequest nonHttpResponse = new MockHttpServletRequest("GET", "/api/x");
        filter.doFilter(nonHttpResponse, mock(ServletResponse.class), chain);
        assertThat(seen.get()).isSameAs(nonHttpResponse);

        assertThat(store.size()).isZero();
        assertThat(sessions.list()).allSatisfy(view -> assertThat(view.capturedCount()).isZero());
    }

    @Test
    void passesThroughWithoutRootTrace() throws Exception {
        startSession("GET", "/api/**");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/x");
        AtomicReference<ServletRequest> seen = new AtomicReference<>();

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> seen.set(req));

        assertThat(seen.get()).isSameAs(request);
        assertThat(sessions.list().get(0).capturedCount()).isZero();
    }

    @Test
    void ignoresFailuresWhileStartingCapture() throws Exception {
        startSession("GET", "/api/**");
        HttpServletRequest broken = mock(HttpServletRequest.class);
        when(broken.getDispatcherType()).thenReturn(DispatcherType.REQUEST);
        when(broken.getRequestURI()).thenThrow(new IllegalStateException("recycled"));
        AtomicReference<ServletRequest> seen = new AtomicReference<>();

        runInTrace(broken, new MockHttpServletResponse(), (req, res) -> seen.set(req));

        assertThat(seen.get()).isSameAs(broken);
        assertThat(store.size()).isZero();
    }

    @Test
    void keepsRequestWorkingWhenStoreFails() throws Exception {
        CaptureFilter failingFilter = new CaptureFilter(sessions, null, limits);
        startSession("GET", "/api/**");
        TraceContext.startRoot("HTTP", SpanKind.HTTP);
        AtomicReference<Boolean> called = new AtomicReference<>(false);

        failingFilter.doFilter(new MockHttpServletRequest("GET", "/api/x"), new MockHttpServletResponse(),
                (req, res) -> called.set(true));
        TraceContext.stopRoot(Long.MAX_VALUE);

        assertThat(called.get()).isTrue();
        assertThat(sessions.list().get(0).capturedCount()).isEqualTo(1);
    }

    @Test
    void wrappersDelegateStreamOperations() throws IOException {
        BodyTee requestBody = new BodyTee(100, () -> "text/plain", () -> "UTF-8");
        MockHttpServletRequest raw = new MockHttpServletRequest("POST", "/x");
        raw.setContent("hi".getBytes(StandardCharsets.UTF_8));
        CapturingRequest request = new CapturingRequest(raw, requestBody);

        var in = request.getInputStream();
        assertThat(request.getInputStream()).isSameAs(in);
        assertThat(in.available()).isEqualTo(2);
        assertThat(in.isReady()).isTrue();
        assertThat(in.read()).isEqualTo('h');
        assertThat(in.isFinished()).isFalse();
        assertThat(in.read(new byte[4], 0, 4)).isEqualTo(1);
        assertThat(in.read()).isEqualTo(-1);
        assertThat(in.isFinished()).isTrue();
        in.close();
        assertThat(requestBody.render(100)).isEqualTo("hi");

        MockHttpServletRequest readerRaw = new MockHttpServletRequest("POST", "/x");
        readerRaw.setContent("ok".getBytes(StandardCharsets.UTF_8));
        BodyTee readerBody = new BodyTee(100, null, null);
        CapturingRequest readerRequest = new CapturingRequest(readerRaw, readerBody);
        var reader = readerRequest.getReader();
        assertThat(readerRequest.getReader()).isSameAs(reader);
        assertThat(reader.ready()).isTrue();
        assertThat(reader.readLine()).isEqualTo("ok");
        reader.close();
        assertThat(readerBody.render(100)).isEqualTo("ok");

        BodyTee responseBody = new BodyTee(100, null, null);
        MockHttpServletResponse rawResponse = new MockHttpServletResponse();
        CapturingResponse response = new CapturingResponse(rawResponse, responseBody);
        var out = response.getOutputStream();
        assertThat(response.getOutputStream()).isSameAs(out);
        assertThat(out.isReady()).isTrue();
        out.write('a');
        out.write("bc".getBytes(StandardCharsets.UTF_8), 0, 2);
        out.flush();
        out.close();
        assertThat(rawResponse.getContentAsString()).isEqualTo("abc");
        assertThat(responseBody.render(100)).isEqualTo("abc");

        BodyTee writerBody = new BodyTee(100, null, null);
        MockHttpServletResponse writerRaw = new MockHttpServletResponse();
        CapturingResponse writerResponse = new CapturingResponse(writerRaw, writerBody);
        var writer = writerResponse.getWriter();
        assertThat(writerResponse.getWriter()).isSameAs(writer);
        writer.write('x');
        writer.write("yz".toCharArray());
        writer.write("-end", 1, 3);
        writer.flush();
        writer.close();
        assertThat(writerRaw.getContentAsString()).isEqualTo("xyzend");
        assertThat(writerBody.render(100)).isEqualTo("xyzend");
    }
}
