package io.github.lakernote.meerkat.autoconfigure.boot4;

import io.github.lakernote.meerkat.trace.internal.TraceIds;
import io.github.lakernote.meerkat.autoconfigure.ObservabilityAutoConfiguration;

import io.github.lakernote.meerkat.config.MeerkatProperties;
import io.github.lakernote.meerkat.trace.TraceIdContext;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;

/** 为 Spring Boot 管理的 RestClient.Builder 自动传播 Trace ID。 */
@AutoConfiguration(after = ObservabilityAutoConfiguration.class)
@ConditionalOnClass({RestClient.class, RestClientCustomizer.class})
@ConditionalOnProperty(prefix = "meerkat", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(prefix = "meerkat.trace", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RestClientTraceAutoConfiguration {

    @Bean
    RestClientCustomizer meerkatTraceIdRestClientCustomizer(MeerkatProperties properties) {
        // An initializer, unlike an interceptor, does not make RestClient buffer every request body in memory.
        return builder -> builder.requestInitializer(request -> {
            String traceId = TraceIdContext.currentTraceId();
            if (TraceIds.isValidTraceId(traceId)) {
                request.getHeaders().set(properties.getTrace().getHeaderName(), traceId);
            }
        });
    }
}
