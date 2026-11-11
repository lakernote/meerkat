package io.github.lakernote.meerkat.capture.internal;

import io.github.lakernote.meerkat.config.MeerkatProperties;
import io.github.lakernote.meerkat.trace.TraceIdContext;
import io.github.lakernote.meerkat.trace.internal.CaptureLimits;
import io.github.lakernote.meerkat.trace.internal.CaptureTarget;
import io.github.lakernote.meerkat.trace.internal.CaptureThreadState;
import io.github.lakernote.meerkat.trace.internal.CaptureValue;
import io.github.lakernote.meerkat.trace.internal.RootTraceHandle;
import io.github.lakernote.meerkat.trace.internal.TraceContext;
import io.github.lakernote.meerkat.web.internal.DashboardPaths;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.time.Instant;
import java.util.Collection;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Records the HTTP entry of requests that match an active capture rule and turns on capture for every dependency
 * call of that request. Runs inside the server observation filter, so the request's trace is already active.
 * <p>When no rule is active the filter only reads one volatile flag.</p>
 */
public final class CaptureFilter implements Filter {
    private final CaptureSessions sessions;
    private final CaptureStore store;
    private final MeerkatProperties.Capture limits;

    public CaptureFilter(CaptureSessions sessions, CaptureStore store, MeerkatProperties.Capture limits) {
        this.sessions = sessions;
        this.store = store;
        this.limits = limits;
    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain chain)
            throws IOException, ServletException {
        if (!sessions.anyActive()
                || !(servletRequest instanceof HttpServletRequest request)
                || !(servletResponse instanceof HttpServletResponse response)
                || request.getDispatcherType() != DispatcherType.REQUEST) {
            chain.doFilter(servletRequest, servletResponse);
            return;
        }
        Capture capture = start(request, response);
        if (capture == null) {
            chain.doFilter(servletRequest, servletResponse);
            return;
        }
        boolean failed = true;
        try {
            chain.doFilter(capture.request, capture.response);
            failed = false;
        } finally {
            capture.finish(failed);
        }
    }

    private Capture start(HttpServletRequest request, HttpServletResponse response) {
        try {
            String path = pathWithinApplication(request);
            if (DashboardPaths.isDashboardPath(path)) return null;
            RootTraceHandle root = TraceContext.currentRootTraceHandle();
            if (root == null) return null;
            CaptureSessions.Match match = sessions.match(request.getMethod(), path, request::getHeader);
            if (match == null) return null;
            return new Capture(request, response, root, match, path);
        } catch (RuntimeException ignored) {
            // 抓取失败时按未抓取处理，不影响请求。
            return null;
        }
    }

    private static String pathWithinApplication(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (uri == null) return "/";
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        }
        return uri.isEmpty() ? "/" : uri;
    }

    private final class Capture {
        private final HttpServletRequest request;
        private final HttpServletResponse response;
        private final CaptureTarget target;
        private final BodyTee requestBody;
        private final BodyTee responseBody;
        private final AtomicReference<String> responseSnapshot = new AtomicReference<>();
        private volatile int status;

        private Capture(HttpServletRequest raw, HttpServletResponse rawResponse, RootTraceHandle root,
                        CaptureSessions.Match match, String path) {
            int maxBody = (int) Math.min(Integer.MAX_VALUE, Math.max(0, limits.getMaxBodySize().toBytes()));
            requestBody = new BodyTee(maxBody, BodyTee.UPLOAD, raw::getContentType, raw::getCharacterEncoding, null);
            responseBody = new BodyTee(maxBody, BodyTee.DOWNLOAD, rawResponse::getContentType,
                    rawResponse::getCharacterEncoding, () -> rawResponse.getHeader("Content-Disposition"));
            request = new CapturingRequest(raw, requestBody);
            response = new CapturingResponse(rawResponse, responseBody);
            String traceId = TraceIdContext.currentTraceId();
            String method = raw.getMethod();
            String query = raw.getQueryString();
            String fullPath = query == null || query.isEmpty() ? path : path + "?" + query;
            String recordId = match.sessionId() + "-" + match.sequence();
            root.beginCapture((completion, nodes) -> store.add(new CaptureRecord(recordId, match.sessionId(),
                    traceId == null ? "" : traceId, method, fullPath, status(), completion.costMs(),
                    completion.error() || status() >= 500, Instant.now(), completion.errorType(),
                    completion.errorMessage(), completion.stackTrace(), nodes)),
                    new CaptureLimits(maxBody, limits.getMaxResultRows(), limits.getMaxValueChars(),
                            limits.getMaxTraceChars()));
            target = root.captureTarget();
            if (target != null) {
                target.capture("request", requestHead(raw, fullPath));
                target.capture("request.body", requestBody);
                target.capture("response", (CaptureValue) maxChars -> {
                    String snapshot = responseSnapshot.get();
                    return snapshot != null ? snapshot : responseHead(rawResponse, 0);
                });
                target.capture("response.body", responseBody);
            }
        }

        private int status() {
            if (status > 0) return status;
            try {
                return response.getStatus();
            } catch (RuntimeException ignored) {
                return 0;
            }
        }

        /** @param failed the request threw; the container turns this into a 500 after the filters return */
        void finish(boolean failed) {
            try {
                CaptureThreadState.clear();
                if (target == null) return;
                if (requestBody.empty() && isForm(request.getContentType())) {
                    target.capture("request.params", parameters(request.getParameterMap()));
                }
                // 异步请求在入口线程返回后仍会写响应；只为同步请求提前固定状态和响应头。
                if (!request.isAsyncStarted()) {
                    int current = response.getStatus();
                    status = failed && current < 400 ? 500 : current;
                    responseSnapshot.set(responseHead(response, status));
                }
            } catch (RuntimeException ignored) {
                // 抓取失败不能影响请求。
            }
        }
    }

    private static String requestHead(HttpServletRequest request, String fullPath) {
        StringBuilder head = new StringBuilder(256).append(request.getMethod()).append(' ').append(fullPath)
                .append(' ').append(request.getProtocol());
        Enumeration<String> names = request.getHeaderNames();
        while (names != null && names.hasMoreElements()) {
            String name = names.nextElement();
            Enumeration<String> values = request.getHeaders(name);
            while (values != null && values.hasMoreElements()) {
                head.append('\n').append(name).append(": ").append(values.nextElement());
            }
        }
        head.append("\n\n[client ").append(request.getRemoteAddr()).append(']');
        return head.toString();
    }

    /** @param status overrides the response status when positive */
    private static String responseHead(HttpServletResponse response, int status) {
        try {
            StringBuilder head = new StringBuilder(128).append(status > 0 ? status : response.getStatus());
            String contentType = response.getContentType();
            Collection<String> names = response.getHeaderNames();
            boolean contentTypeListed = false;
            for (String name : names) {
                if ("content-type".equals(name.toLowerCase(Locale.ROOT))) contentTypeListed = true;
                for (String value : response.getHeaders(name)) head.append('\n').append(name).append(": ").append(value);
            }
            if (!contentTypeListed && contentType != null) head.append("\nContent-Type: ").append(contentType);
            return head.toString();
        } catch (RuntimeException unavailable) {
            return "[response headers unavailable]";
        }
    }

    private static boolean isForm(String contentType) {
        return contentType != null
                && contentType.toLowerCase(Locale.ROOT).startsWith("application/x-www-form-urlencoded");
    }

    private static String parameters(Map<String, String[]> parameters) {
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, String[]> entry : parameters.entrySet()) {
            for (String value : entry.getValue()) {
                if (!text.isEmpty()) text.append('\n');
                text.append(entry.getKey()).append('=').append(value);
            }
        }
        return text.toString();
    }
}
