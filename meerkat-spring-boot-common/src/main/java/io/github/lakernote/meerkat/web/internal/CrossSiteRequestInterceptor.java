package io.github.lakernote.meerkat.web.internal;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;

/**
 * Rejects state-changing API requests sent by a browser from another origin, so a malicious page cannot use the
 * user's cookies or session to clear history, start captures, change log levels or run diagnostics.
 * <p>Browsers that send {@code Sec-Fetch-Site} are trusted only for {@code same-origin} and {@code none} (typed by
 * the user). Otherwise the {@code Origin} header must match the request's host and port. Requests without either
 * header, such as curl or server-to-server calls, are not sent by a browser on someone's behalf and are allowed.</p>
 */
public class CrossSiteRequestInterceptor implements HandlerInterceptor {
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");
    private static final Set<String> TRUSTED_SITES = Set.of("same-origin", "none");

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!SAFE_METHODS.contains(request.getMethod()) && isCrossSite(request)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cross-site request rejected");
        }
        return true;
    }

    static boolean isCrossSite(HttpServletRequest request) {
        String site = request.getHeader("Sec-Fetch-Site");
        if (site != null) return !TRUSTED_SITES.contains(site.trim().toLowerCase(Locale.ROOT));
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        // "null" is the opaque origin of sandboxed frames and privacy-sensitive redirects, never the dashboard.
        return origin != null && !sameOrigin(origin.trim(), request);
    }

    private static boolean sameOrigin(String origin, HttpServletRequest request) {
        URI source = uri(origin);
        if (source == null || source.getScheme() == null || source.getHost() == null) return false;
        String scheme = source.getScheme();
        int port = port(scheme, source.getPort());
        if (source.getHost().equalsIgnoreCase(request.getServerName())
                && port == port(request.getScheme(), request.getServerPort())) {
            return true;
        }
        // A proxy that forwards the Host header but not the scheme still matches here.
        String host = request.getHeader(HttpHeaders.HOST);
        URI target = host == null ? null : uri(scheme + "://" + host.trim());
        return target != null && source.getHost().equalsIgnoreCase(target.getHost())
                && port == port(scheme, target.getPort());
    }

    private static URI uri(String value) {
        try {
            return new URI(value);
        } catch (URISyntaxException invalid) {
            return null;
        }
    }

    private static int port(String scheme, int port) {
        if (port >= 0) return port;
        return "https".equalsIgnoreCase(scheme) ? 443 : "http".equalsIgnoreCase(scheme) ? 80 : -1;
    }
}
