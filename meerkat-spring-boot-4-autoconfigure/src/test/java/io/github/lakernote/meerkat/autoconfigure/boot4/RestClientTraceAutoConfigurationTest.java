package io.github.lakernote.meerkat.autoconfigure.boot4;

import io.github.lakernote.meerkat.config.MeerkatProperties;
import io.github.lakernote.meerkat.trace.internal.TraceIds;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RestClientTraceAutoConfigurationTest {
    @AfterEach
    void clearTraceId() {
        TraceIds.clear();
    }

    @Test
    void propagatesTraceIdWithoutAnInterceptorThatBuffersRequestBodies() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(RestClientTraceAutoConfiguration.class))
                .withBean(MeerkatProperties.class, MeerkatProperties::new)
                .run(context -> {
                    List<MockClientHttpRequest> sent = new ArrayList<>();
                    RestClient.Builder builder = RestClient.builder().requestFactory((uri, method) -> {
                        MockClientHttpRequest request = new MockClientHttpRequest(method, uri);
                        request.setResponse(new MockClientHttpResponse(new byte[0], HttpStatus.OK));
                        sent.add(request);
                        return request;
                    });
                    context.getBean(RestClientCustomizer.class).customize(builder);
                    builder.requestInterceptors(interceptors -> assertThat(interceptors).isEmpty());
                    TraceIds.putTraceId("0123456789abcdef0123456789abcdef");

                    builder.build().post().uri("http://inventory/items").body("payload").retrieve().toBodilessEntity();

                    assertThat(sent).singleElement().satisfies(request -> {
                        assertThat(request.getHeaders().getFirst("X-Trace-Id"))
                                .isEqualTo("0123456789abcdef0123456789abcdef");
                        assertThat(request.getBodyAsString()).isEqualTo("payload");
                    });
                });
    }
}
