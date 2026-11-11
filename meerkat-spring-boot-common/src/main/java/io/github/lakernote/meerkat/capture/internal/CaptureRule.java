package io.github.lakernote.meerkat.capture.internal;

import java.util.Locale;

/**
 * Which requests a capture session records.
 *
 * @param method          HTTP method, or blank for any
 * @param pathPattern     Ant-style path pattern such as {@code /api/orders/**}, matched against the path without
 *                        the context path
 * @param headerName      optional header that must be present, for example a test user marker
 * @param headerValue     optional exact value of {@code headerName}; blank accepts any value
 * @param maxCount        requests to capture before the session stops
 * @param durationSeconds how long the session stays active
 */
public record CaptureRule(String method, String pathPattern, String headerName, String headerValue, int maxCount,
                          long durationSeconds) {
    public static final int MAX_COUNT = 1_000;

    public CaptureRule {
        method = method == null ? "" : method.trim().toUpperCase(Locale.ROOT);
        pathPattern = pathPattern == null ? "" : pathPattern.trim();
        headerName = headerName == null ? "" : headerName.trim();
        headerValue = headerValue == null ? "" : headerValue.trim();
    }

    void validate(long maxDurationSeconds, int maxCount) {
        if (pathPattern.isEmpty() || !pathPattern.startsWith("/")) {
            throw new IllegalArgumentException("path must start with /");
        }
        if (pathPattern.length() > 512) throw new IllegalArgumentException("path is too long");
        if (!method.isEmpty() && !method.matches("[A-Z]{3,10}")) throw new IllegalArgumentException("invalid method");
        if (headerName.length() > 128 || headerValue.length() > 512) {
            throw new IllegalArgumentException("header is too long");
        }
        if (!headerValue.isEmpty() && headerName.isEmpty()) {
            throw new IllegalArgumentException("header value needs a header name");
        }
        if (this.maxCount < 1 || this.maxCount > maxCount) {
            throw new IllegalArgumentException("count must be between 1 and " + maxCount);
        }
        if (durationSeconds < 1 || durationSeconds > maxDurationSeconds) {
            throw new IllegalArgumentException("duration must be between 1 and " + maxDurationSeconds + " seconds");
        }
    }
}
