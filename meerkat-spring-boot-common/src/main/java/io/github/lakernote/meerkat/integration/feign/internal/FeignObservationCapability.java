package io.github.lakernote.meerkat.integration.feign.internal;

import feign.Capability;
import feign.Client;
import feign.MethodMetadata;
import feign.Request;
import feign.RequestTemplate;
import feign.Response;
import feign.Target;
import io.github.lakernote.meerkat.trace.internal.CaptureTarget;
import io.github.lakernote.meerkat.trace.internal.CaptureThreadState;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

import java.io.IOException;
import java.net.URI;

/**
 * 为 Feign 调用发布 {@code http.client.requests} Observation，使其作为远程调用节点进入 Trace 与调用统计。
 * <p>仅在未引入 feign-micrometer 时使用；URI 取接口声明的模板，避免路径参数和 query 造成高基数。</p>
 */
public final class FeignObservationCapability implements Capability {
    static final String OBSERVATION_NAME = "http.client.requests";
    private final ObservationRegistry registry;

    public FeignObservationCapability(ObservationRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Client enrich(Client client) {
        if (client instanceof ObservedClient) return client;
        return new ObservedClient(client, registry);
    }

    private record ObservedClient(Client delegate, ObservationRegistry registry) implements Client {
        @Override
        public Response execute(Request request, Request.Options options) throws IOException {
            // Same tag keys as Spring's RestTemplate/RestClient convention: registries such as Prometheus reject a
            // meter whose keys differ from an existing one of the same name.
            Observation observation = Observation.createNotStarted(OBSERVATION_NAME, registry)
                    .lowCardinalityKeyValue("method", request.httpMethod().name())
                    .lowCardinalityKeyValue("uri", uriTemplate(request))
                    .lowCardinalityKeyValue("client.name", clientName(request))
                    .lowCardinalityKeyValue("status", "CLIENT_ERROR")
                    .lowCardinalityKeyValue("exception", "none")
                    .lowCardinalityKeyValue("outcome", "UNKNOWN")
                    .start();
            CaptureTarget capture = null;
            try (Observation.Scope ignored = observation.openScope()) {
                capture = CaptureThreadState.claimRemote();
                if (capture != null) FeignCapture.request(capture, request);
                Response response = delegate.execute(request, options);
                observation.lowCardinalityKeyValue("status", String.valueOf(response.status()))
                        .lowCardinalityKeyValue("outcome", outcome(response.status()));
                return capture == null ? response : FeignCapture.response(capture, response);
            } catch (IOException | RuntimeException | Error failure) {
                if (capture != null) capture.capture("error", failure.toString());
                String exception = failure.getClass().getSimpleName();
                observation.lowCardinalityKeyValue("exception", exception.isEmpty() ? failure.getClass().getName()
                        : exception);
                observation.error(failure);
                throw failure;
            } finally {
                observation.stop();
            }
        }
    }

    static String outcome(int status) {
        if (status >= 100 && status < 200) return "INFORMATIONAL";
        if (status >= 200 && status < 300) return "SUCCESS";
        if (status >= 300 && status < 400) return "REDIRECTION";
        if (status >= 400 && status < 500) return "CLIENT_ERROR";
        if (status >= 500 && status < 600) return "SERVER_ERROR";
        return "UNKNOWN";
    }

    static String uriTemplate(Request request) {
        RequestTemplate template = request.requestTemplate();
        MethodMetadata metadata = template == null ? null : template.methodMetadata();
        String uri = metadata != null && metadata.template() != null ? metadata.template().url() : null;
        if (uri == null || uri.isBlank()) uri = template != null ? template.path() : pathOf(request.url());
        int query = uri == null ? -1 : uri.indexOf('?');
        if (query >= 0) uri = uri.substring(0, query);
        return uri == null || uri.isBlank() ? "/" : uri;
    }

    static String clientName(Request request) {
        RequestTemplate template = request.requestTemplate();
        Target<?> target = template == null ? null : template.feignTarget();
        if (target != null && target.name() != null && !target.name().isBlank()) return target.name();
        try {
            String host = URI.create(request.url()).getHost();
            return host == null ? "none" : host;
        } catch (IllegalArgumentException invalid) {
            return "none";
        }
    }

    private static String pathOf(String url) {
        try {
            return URI.create(url).getRawPath();
        } catch (IllegalArgumentException invalid) {
            return "/";
        }
    }
}
