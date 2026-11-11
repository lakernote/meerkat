package io.github.lakernote.meerkat.autoconfigure;

import io.github.lakernote.meerkat.capture.internal.CaptureClientHttpRequestFactory;
import io.github.lakernote.meerkat.capture.internal.CaptureFilter;
import io.github.lakernote.meerkat.capture.internal.CaptureSessions;
import io.github.lakernote.meerkat.capture.internal.CaptureStore;
import io.github.lakernote.meerkat.config.MeerkatProperties;
import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.DispatcherServlet;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * On-demand traffic capture for troubleshooting: records the full request, response, SQL, Redis commands and
 * outbound HTTP calls of requests that match an active capture session. Nothing is recorded until a session is
 * started from the dashboard, which needs {@code ObservabilityAccessPolicy#canCapture()}; without an active session
 * the hooks only check a flag.
 */
@AutoConfiguration(after = ObservabilityAutoConfiguration.class)
@ConditionalOnClass(DispatcherServlet.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "meerkat", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(prefix = "meerkat.trace", name = "enabled", havingValue = "true", matchIfMissing = true)
public class TrafficCaptureAutoConfiguration {
    /** Runs after the server observation filter, which opens the root trace, and before security filters. */
    static final int FILTER_ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

    @Bean
    CaptureSessions meerkatCaptureSessions(MeerkatProperties properties) {
        return new CaptureSessions(properties.getCapture().getMaxDuration(), properties.getCapture().getMaxRecords());
    }

    @Bean
    CaptureStore meerkatCaptureStore(MeerkatProperties properties) {
        MeerkatProperties.Capture capture = properties.getCapture();
        long maxBytes = capture.getMaxStoredSize() == null ? Long.MAX_VALUE : capture.getMaxStoredSize().toBytes();
        return new CaptureStore(capture.getMaxRecords(), maxBytes, capture.getRetention());
    }

    @Bean
    FilterRegistrationBean<CaptureFilter> meerkatCaptureFilter(CaptureSessions sessions, CaptureStore store,
                                                               MeerkatProperties properties) {
        FilterRegistrationBean<CaptureFilter> registration =
                new FilterRegistrationBean<>(new CaptureFilter(sessions, store, properties.getCapture()));
        registration.setName("meerkatCaptureFilter");
        registration.setOrder(FILTER_ORDER);
        registration.setAsyncSupported(true);
        registration.setDispatcherTypes(DispatcherType.REQUEST);
        registration.addUrlPatterns("/*");
        return registration;
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(RestTemplate.class)
    static class HttpClientCapture {
        /**
         * Wraps the request factory of RestTemplate, RestClient and RestClient.Builder beans. Calls outside a captured
         * request get the original request unchanged. A request factory is used instead of an interceptor because any
         * interceptor makes Spring buffer each request body in memory, which would break large uploads.
         */
        @Bean
        static BeanPostProcessor meerkatHttpClientCapturePostProcessor() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    try {
                        if (bean instanceof RestTemplate template) {
                            wrap(template);
                        } else if (bean instanceof RestClient.Builder builder) {
                            wrap(builder);
                        } else if (bean instanceof RestClient client) {
                            RestClient.Builder builder = client.mutate();
                            if (wrap(builder)) return builder.build();
                        }
                    } catch (RuntimeException | LinkageError ignored) {
                        // 无法增强的客户端保持原样。
                    }
                    return bean;
                }
            };
        }

        static void wrap(RestTemplate template) {
            // With interceptors present the getter returns Spring's intercepting factory; clear them to reach the
            // underlying one, then restore the same list without re-sorting it.
            List<ClientHttpRequestInterceptor> live = template.getInterceptors();
            List<ClientHttpRequestInterceptor> interceptors = new ArrayList<>(live);
            live.clear();
            try {
                ClientHttpRequestFactory base = template.getRequestFactory();
                if (!(base instanceof CaptureClientHttpRequestFactory)) {
                    template.setRequestFactory(new CaptureClientHttpRequestFactory(base));
                }
            } finally {
                live.addAll(interceptors);
            }
        }

        /** @return whether the builder now uses a capturing factory it did not use before */
        static boolean wrap(RestClient.Builder builder) {
            ClientHttpRequestFactory base = requestFactory(builder);
            if (base == null || base instanceof CaptureClientHttpRequestFactory) return false;
            builder.requestFactory(new CaptureClientHttpRequestFactory(base));
            return true;
        }

        /**
         * The factory the builder would build with: the configured one or the default it detects. The builder has no
         * getter, so this calls its private method, present with the same name in Spring 6.2 and 7.0; when that
         * fails the client is left without capture.
         */
        static ClientHttpRequestFactory requestFactory(RestClient.Builder builder) {
            try {
                Method init = builder.getClass().getDeclaredMethod("initRequestFactory");
                if (!ClientHttpRequestFactory.class.isAssignableFrom(init.getReturnType())) return null;
                init.setAccessible(true);
                return (ClientHttpRequestFactory) init.invoke(builder);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
                return null;
            }
        }
    }
}
