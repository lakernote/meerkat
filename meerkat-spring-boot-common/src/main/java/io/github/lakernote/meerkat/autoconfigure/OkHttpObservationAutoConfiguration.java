package io.github.lakernote.meerkat.autoconfigure;

import io.github.lakernote.meerkat.trace.internal.TraceIds;
import io.github.lakernote.meerkat.config.MeerkatProperties;
import io.github.lakernote.meerkat.trace.TraceIdContext;
import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import io.micrometer.core.instrument.binder.okhttp3.DefaultOkHttpObservationConvention;
import io.micrometer.core.instrument.binder.okhttp3.OkHttpContext;
import io.micrometer.core.instrument.binder.okhttp3.OkHttpObservationInterceptor;
import io.micrometer.observation.ObservationRegistry;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Adds Micrometer observations and trace-id propagation to application OkHttpClient beans. */
@AutoConfiguration(after = ObservabilityAutoConfiguration.class)
@ConditionalOnClass({OkHttpClient.class, OkHttpObservationInterceptor.class})
@ConditionalOnBean(ObservationRegistry.class)
@ConditionalOnProperty(prefix = "meerkat", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OkHttpObservationAutoConfiguration {
    private static final String OBSERVATION_NAME = "http.client.requests";
    private static final Pattern IDENTIFIER_SEGMENT = Pattern.compile(
            "(?i)[0-9]{2,}|[0-9a-f]{8}-[0-9a-f-]{27,}|[0-9a-f]{16,}");
    private static final int MAX_URI_CHARS = 256;
    @Bean
    static BeanPostProcessor okHttpObservationBeanPostProcessor(ObjectProvider<ObservationRegistry> registryProvider,
                                                                 ObjectProvider<MeerkatProperties> propertiesProvider) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (!(bean instanceof OkHttpClient client)) return bean;
                ObservationRegistry registry = registryProvider.getIfAvailable();
                MeerkatProperties properties = propertiesProvider.getIfAvailable();
                if (registry == null || properties == null) return bean;
                OkHttpClient.Builder builder = client.newBuilder();
                boolean hasTracePropagation = client.interceptors().stream()
                        .anyMatch(interceptor -> interceptor instanceof TraceIdPropagationInterceptor);
                if (!hasTracePropagation) builder.addInterceptor(new TraceIdPropagationInterceptor(properties));
                // Micrometer owns timing, status/error tags and Observation lifecycle. The bounded URI mapper
                // prevents path identifiers from creating a new time series per request.
                if (!hasObservationInterceptor(client)) {
                    builder.addInterceptor(OkHttpObservationInterceptor.builder(registry, OBSERVATION_NAME)
                            .uriMapper(OkHttpObservationAutoConfiguration::boundedUri)
                            .observationConvention(new SpringCompatibleConvention())
                            .build());
                }
                return builder.build();
            }
        };
    }

    private static boolean hasObservationInterceptor(OkHttpClient client) {
        return client.interceptors().stream().anyMatch(interceptor ->
                interceptor instanceof OkHttpObservationInterceptor);
    }

    static String boundedUri(Request request) {
        String path = request.url().encodedPath();
        if (path == null || path.isBlank()) return "UNKNOWN";
        StringBuilder out = new StringBuilder(Math.min(path.length(), MAX_URI_CHARS));
        int start = 0;
        while (start <= path.length() && out.length() < MAX_URI_CHARS) {
            int end = path.indexOf('/', start);
            if (end < 0) end = path.length();
            String segment = path.substring(start, end);
            out.append(identifierLike(segment) ? "{id}" : segment);
            if (end < path.length()) out.append('/');
            start = end + 1;
        }
        return out.length() > MAX_URI_CHARS ? out.substring(0, MAX_URI_CHARS) : out.toString();
    }

    /**
     * Path segments that identify one record, user or credential (numbers, UUIDs, hashes, e-mail addresses, JWTs
     * and other long tokens) would create a time series per value and may leak personal data.
     */
    static boolean identifierLike(String segment) {
        if (segment.isEmpty()) return false;
        if (IDENTIFIER_SEGMENT.matcher(segment).matches()) return true;
        if (segment.indexOf('@') >= 0 || segment.toLowerCase(java.util.Locale.ROOT).contains("%40")) return true;
        int digits = 0;
        for (int i = 0; i < segment.length(); i++) {
            if (Character.isDigit(segment.charAt(i))) digits++;
        }
        if (segment.length() >= 24 && (digits > 0 || segment.indexOf('.') >= 0)) return true;
        return digits >= 4;
    }

    /**
     * Gives OkHttp calls the tag keys of Spring's RestTemplate/RestClient {@code http.client.requests} meters:
     * registries such as Prometheus reject a meter whose keys differ from an existing one of the same name.
     */
    static final class SpringCompatibleConvention extends DefaultOkHttpObservationConvention {
        private static final List<String> KEPT_KEYS = List.of("method", "uri", "status", "outcome");

        SpringCompatibleConvention() {
            super(OBSERVATION_NAME);
        }

        @Override
        public KeyValues getLowCardinalityKeyValues(OkHttpContext context) {
            List<KeyValue> values = new ArrayList<>(6);
            for (KeyValue value : super.getLowCardinalityKeyValues(context)) {
                if (KEPT_KEYS.contains(value.getKey())) values.add(value);
            }
            Request request = context.getOriginalRequest();
            String host = request == null ? null : request.url().host();
            values.add(KeyValue.of("client.name", host == null || host.isEmpty() ? "none" : host));
            Throwable error = context.getError();
            String exception = error == null ? "none" : error.getClass().getSimpleName();
            values.add(KeyValue.of("exception", exception.isEmpty() ? error.getClass().getName() : exception));
            return KeyValues.of(values);
        }
    }

    private static final class TraceIdPropagationInterceptor implements okhttp3.Interceptor {
        private final MeerkatProperties properties;

        private TraceIdPropagationInterceptor(MeerkatProperties properties) {
            this.properties = properties;
        }

        @Override
        public okhttp3.Response intercept(Chain chain) throws java.io.IOException {
            Request request = chain.request();
            String headerName = properties.getTrace().getHeaderName();
            String traceId = TraceIdContext.currentTraceId();
            if (TraceIds.isValidTraceId(traceId) && request.header(headerName) == null) {
                request = request.newBuilder().header(headerName, traceId).build();
            }
            return chain.proceed(request);
        }
    }
}
