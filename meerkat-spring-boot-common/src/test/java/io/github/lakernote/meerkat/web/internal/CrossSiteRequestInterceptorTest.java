package io.github.lakernote.meerkat.web.internal;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CrossSiteRequestInterceptorTest {
    private final CrossSiteRequestInterceptor interceptor = new CrossSiteRequestInterceptor();

    private static MockHttpServletRequest request(String method) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/api/meerkat/traces/clear");
        request.setScheme("http");
        request.setServerName("app.example");
        request.setServerPort(8080);
        request.addHeader("Host", "app.example:8080");
        return request;
    }

    private static MockHttpServletRequest request(String method, String header, String value) {
        MockHttpServletRequest request = request(method);
        request.addHeader(header, value);
        return request;
    }

    private boolean allows(MockHttpServletRequest request) {
        try {
            return interceptor.preHandle(request, new MockHttpServletResponse(), new Object());
        } catch (ResponseStatusException rejected) {
            assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            return false;
        }
    }

    @Test
    void allowsRequestsWithoutBrowserHeaders() {
        assertThat(allows(request("POST"))).isTrue();
        assertThat(allows(request("DELETE"))).isTrue();
    }

    @Test
    void trustsFetchMetadataOnlyForSameOriginAndUserInitiatedRequests() {
        assertThat(allows(request("POST", "Sec-Fetch-Site", "same-origin"))).isTrue();
        assertThat(allows(request("POST", "Sec-Fetch-Site", "none"))).isTrue();
        assertThat(allows(request("POST", "Sec-Fetch-Site", "cross-site"))).isFalse();
        assertThat(allows(request("PUT", "Sec-Fetch-Site", "same-site"))).isFalse();

        MockHttpServletRequest sameOriginHeaderButCrossSite = request("POST", "Sec-Fetch-Site", "cross-site");
        sameOriginHeaderButCrossSite.addHeader("Origin", "http://app.example:8080");
        assertThat(allows(sameOriginHeaderButCrossSite)).isFalse();
    }

    @Test
    void comparesTheOriginWithTheRequestWhenFetchMetadataIsMissing() {
        assertThat(allows(request("POST", "Origin", "http://app.example:8080"))).isTrue();
        assertThat(allows(request("POST", "Origin", "HTTP://APP.EXAMPLE:8080"))).isTrue();
        assertThat(allows(request("POST", "Origin", "https://evil.example"))).isFalse();
        assertThat(allows(request("POST", "Origin", "http://app.example:9090"))).isFalse();
        assertThat(allows(request("POST", "Origin", "null"))).isFalse();
        assertThat(allows(request("POST", "Origin", "not a url"))).isFalse();
    }

    @Test
    void acceptsTheHostHeaderWhenAProxyTerminatesTls() {
        MockHttpServletRequest request = request("POST");
        request.setServerName("10.0.0.5");
        request.removeHeader("Host");
        request.addHeader("Host", "app.example");
        request.addHeader("Origin", "https://app.example");
        assertThat(allows(request)).isTrue();

        MockHttpServletRequest defaultPort = request("POST");
        defaultPort.setServerPort(80);
        defaultPort.removeHeader("Host");
        defaultPort.addHeader("Host", "app.example");
        defaultPort.addHeader("Origin", "http://app.example");
        assertThat(allows(defaultPort)).isTrue();
    }

    @Test
    void neverBlocksSafeMethods() {
        for (String method : new String[] {"GET", "HEAD", "OPTIONS"}) {
            assertThat(allows(request(method, "Sec-Fetch-Site", "cross-site"))).isTrue();
            assertThat(allows(request(method, "Origin", "https://evil.example"))).isTrue();
        }
    }
}
