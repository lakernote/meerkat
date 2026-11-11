package io.github.lakernote.meerkat.autoconfigure;

import io.github.lakernote.meerkat.web.ObservabilityAccessPolicy;
import io.github.lakernote.meerkat.web.internal.CrossSiteRequestInterceptor;
import io.github.lakernote.meerkat.web.internal.ObservabilityController;
import io.github.lakernote.meerkat.web.internal.ObservabilityDashboardController;
import io.github.lakernote.meerkat.web.internal.LiveDiagnosticsController;
import io.github.lakernote.meerkat.web.internal.MetricsController;
import io.github.lakernote.meerkat.web.internal.ToolsController;
import io.github.lakernote.meerkat.web.internal.TrafficCaptureController;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Serves the bundled dashboard and its read-only API in Spring MVC applications. */
@AutoConfiguration(after = {ObservabilityAutoConfiguration.class, RuntimeMetricsAutoConfiguration.class})
@ConditionalOnClass(DispatcherServlet.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "meerkat", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(prefix = "meerkat.web", name = "enabled", havingValue = "true", matchIfMissing = true)
@Import({ObservabilityController.class, ObservabilityDashboardController.class, MetricsController.class,
        LiveDiagnosticsController.class, TrafficCaptureController.class, ToolsController.class})
public class ObservabilityWebAutoConfiguration implements WebMvcConfigurer {
    /**
     * Allows every request to view data and keeps clearing, traffic capture and the sensitive or heavy diagnostics
     * (toolbox operations, CPU profiling, duplicate class scans) disabled.
     * The host application is expected to protect the dashboard paths with its own authentication.
     */
    @Bean
    @ConditionalOnMissingBean
    ObservabilityAccessPolicy observabilityAccessPolicy() {
        return new BuiltInAccessPolicy();
    }

    private static final class BuiltInAccessPolicy implements ObservabilityAccessPolicy {
        @Override
        public boolean canView() {
            return true;
        }
    }

    /** Prints where the dashboard is served once the application is ready to take requests. */
    @Bean
    ApplicationListener<ApplicationReadyEvent> observabilityDashboardAddressLogger() {
        return event -> DashboardAddress.log(event.getApplicationContext().getEnvironment());
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/meerkat/assets/**")
                .addResourceLocations("classpath:/META-INF/resources/meerkat/assets/");
    }

    /** Keeps other sites from triggering the API's state-changing requests with the user's cookies. */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new CrossSiteRequestInterceptor()).addPathPatterns("/api/meerkat/**");
    }
}
