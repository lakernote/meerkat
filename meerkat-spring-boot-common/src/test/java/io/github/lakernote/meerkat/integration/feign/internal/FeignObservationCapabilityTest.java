package io.github.lakernote.meerkat.integration.feign.internal;

import feign.Client;
import feign.Contract;
import feign.Param;
import feign.RequestLine;
import feign.MethodMetadata;
import feign.Request;
import feign.RequestTemplate;
import feign.Response;
import feign.Target;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FeignObservationCapabilityTest {
    @Test
    void publishesClientObservationWithTemplateUriAndStatus() throws IOException {
        List<Observation.Context> stopped = new ArrayList<>();
        ObservationRegistry registry = registry(stopped);
        Client client = new FeignObservationCapability(registry).enrich((Client) (request, options) ->
                Response.builder().status(404).request(request).headers(Map.of()).build());

        client.execute(request("http://inventory/items/42?token=secret"), new Request.Options());

        assertThat(stopped).hasSize(1);
        Observation.Context context = stopped.get(0);
        assertThat(context.getName()).isEqualTo("http.client.requests");
        assertThat(context.getLowCardinalityKeyValue("method").getValue()).isEqualTo("GET");
        assertThat(context.getLowCardinalityKeyValue("uri").getValue()).isEqualTo("/items/{id}");
        assertThat(context.getLowCardinalityKeyValue("client.name").getValue()).isEqualTo("inventory-service");
        assertThat(context.getLowCardinalityKeyValue("status").getValue()).isEqualTo("404");
        assertThat(context.getLowCardinalityKeyValue("outcome").getValue()).isEqualTo("CLIENT_ERROR");
        assertThat(context.getLowCardinalityKeyValue("exception").getValue()).isEqualTo("none");
        assertThat(keys(context)).isEqualTo(SPRING_CLIENT_KEYS);
    }

    @Test
    void recordsFailuresAndDoesNotWrapTwice() {
        List<Observation.Context> stopped = new ArrayList<>();
        FeignObservationCapability capability = new FeignObservationCapability(registry(stopped));
        Client client = capability.enrich((Client) (request, options) -> {
            throw new IOException("connection refused");
        });
        assertThat(capability.enrich(client)).isSameAs(client);

        assertThatThrownBy(() -> client.execute(request("http://inventory/items/1"), new Request.Options()))
                .isInstanceOf(IOException.class);
        assertThat(stopped).singleElement().satisfies(context -> {
            assertThat(context.getError()).hasMessage("connection refused");
            assertThat(context.getLowCardinalityKeyValue("exception").getValue()).isEqualTo("IOException");
            assertThat(keys(context)).isEqualTo(SPRING_CLIENT_KEYS);
        });
    }

    /** Spring's RestTemplate/RestClient use these keys for the same meter; Prometheus rejects mismatches. */
    private static final java.util.Set<String> SPRING_CLIENT_KEYS =
            java.util.Set.of("method", "uri", "client.name", "status", "exception", "outcome");

    private static java.util.Set<String> keys(Observation.Context context) {
        java.util.Set<String> keys = new java.util.HashSet<>();
        context.getLowCardinalityKeyValues().forEach(keyValue -> keys.add(keyValue.getKey()));
        return keys;
    }

    interface InventoryApi {
        @RequestLine("GET /items/{id}?token={token}")
        String item(@Param("id") String id, @Param("token") String token);
    }

    private static ObservationRegistry registry(List<Observation.Context> stopped) {
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new ObservationHandler<>() {
            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }

            @Override
            public void onStop(Observation.Context context) {
                stopped.add(context);
            }
        });
        return registry;
    }

    private static Request request(String url) {
        MethodMetadata metadata = new Contract.Default().parseAndValidateMetadata(InventoryApi.class).get(0);
        RequestTemplate template = new RequestTemplate();
        template.methodMetadata(metadata);
        template.feignTarget(new Target.HardCodedTarget<>(Object.class, "inventory-service", "http://inventory"));
        return Request.create(Request.HttpMethod.GET, url, Map.of(), new byte[0], StandardCharsets.UTF_8, template);
    }
}
