package io.github.lakernote.meerkat.web.internal;

import io.github.lakernote.meerkat.config.MeerkatProperties;
import io.github.lakernote.meerkat.diagnostics.internal.ClassSources;
import io.github.lakernote.meerkat.diagnostics.internal.ConfigSources;
import io.github.lakernote.meerkat.diagnostics.internal.CpuProfiler;
import io.github.lakernote.meerkat.diagnostics.internal.HeapDiagnostics;
import io.github.lakernote.meerkat.diagnostics.internal.LogLevelOverrides;
import io.github.lakernote.meerkat.web.ObservabilityAccessPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggerConfiguration;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.server.ResponseStatusException;

import javax.management.MBeanException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.EnumSet;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolsControllerTest {
    private final FakeLoggingSystem logging = new FakeLoggingSystem();
    private final LogLevelOverrides logLevels = new LogLevelOverrides(logging, Duration.ofMinutes(5));
    private final CpuProfiler profiler = new CpuProfiler();
    private final HeapDiagnostics heap = mock(HeapDiagnostics.class);
    private final StandardEnvironment environment = new StandardEnvironment();
    private final ConfigSources config = new ConfigSources(environment);
    private final ClassSources classes = new ClassSources(getClass().getClassLoader());

    ToolsControllerTest() {
        environment.getPropertySources().addFirst(new MapPropertySource("test", Map.of("demo.name", "meerkat")));
    }

    @AfterEach
    void close() {
        logLevels.close();
        profiler.close();
    }

    private static ObservabilityAccessPolicy policy(boolean view, boolean operate) {
        return new ObservabilityAccessPolicy() {
            @Override
            public boolean canView() {
                return view;
            }

            @Override
            public boolean canOperate() {
                return operate;
            }
        };
    }

    private ToolsController controller(boolean view, boolean operate) {
        return new ToolsController(policy(view, operate), true, logLevels, profiler, heap, config, classes);
    }

    private static void assertStatus(Runnable call, HttpStatus status) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(status));
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");
        request.setRemoteUser("ops");
        return request;
    }

    @Test
    void servesThePageAndRedirectsWithoutTrailingSlash() throws Exception {
        ToolsController controller = controller(false, false);

        var redirect = controller.redirectToTools();
        assertThat(redirect.getStatusCode()).isEqualTo(HttpStatus.PERMANENT_REDIRECT);
        assertThat(redirect.getHeaders().getLocation()).hasToString("tools/");
        var page = controller.toolsPage();
        assertThat(page.getHeaders().getContentType()).isEqualTo(MediaType.TEXT_HTML);
        assertThat(page.getBody().exists()).isTrue();
        assertThat(page.getBody().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).contains("assets/tools.js");
    }

    @Test
    void reportsStatusAndPermissions() {
        logging.levels.put("ROOT", LogLevel.INFO);
        when(heap.hasPrevious()).thenReturn(true);

        ToolsController.Status status = controller(true, false).status();

        assertThat(status.enabled()).isTrue();
        assertThat(status.canOperate()).isFalse();
        assertThat(status.loggingSystem()).isTrue();
        assertThat(status.logLevels()).contains("DEBUG", "OFF");
        assertThat(status.maxLogLevelSeconds()).isEqualTo(300);
        assertThat(status.maxProfileSeconds()).isEqualTo(60);
        assertThat(status.profile()).isNull();
        assertThat(status.histogramBaseline()).isTrue();
        assertThat(status.configAvailable()).isTrue();
        assertThat(controller(true, true).status().canOperate()).isTrue();

        ToolsController bare = new ToolsController(policy(true, true), true, null, profiler, heap, null, classes);
        assertThat(bare.status().loggingSystem()).isFalse();
        assertThat(bare.status().logLevels()).isEmpty();
        assertThat(bare.status().configAvailable()).isFalse();
        assertStatus(() -> bare.loggers(null, 10), HttpStatus.NOT_FOUND);
        assertStatus(() -> bare.property("demo.name"), HttpStatus.NOT_FOUND);
        bare.destroy();

        assertStatus(() -> controller(false, true).status(), HttpStatus.FORBIDDEN);
    }

    @Test
    void disablesEveryToolWhenSwitchedOff() {
        ToolsController controller = new ToolsController(policy(true, true), false, logLevels, profiler, heap, config, classes);
        MockHttpServletRequest request = request();

        ToolsController.Status status = controller.status();
        assertThat(status.enabled()).isFalse();
        assertThat(status.canOperate()).isTrue();
        assertStatus(() -> controller.loggers(null, 10), HttpStatus.NOT_FOUND);
        assertStatus(() -> controller.setLevel(new ToolsController.LevelRequest("x", "DEBUG", 1L), request), HttpStatus.NOT_FOUND);
        assertStatus(() -> controller.startProfile(null, request), HttpStatus.NOT_FOUND);
        assertStatus(controller::latestProfile, HttpStatus.NOT_FOUND);
        assertStatus(() -> controller.histogram(10), HttpStatus.NOT_FOUND);
        assertStatus(() -> controller.property("demo.name"), HttpStatus.NOT_FOUND);
        assertStatus(() -> controller.classSource("java.lang.String"), HttpStatus.NOT_FOUND);
        assertStatus(controller::duplicateClasses, HttpStatus.NOT_FOUND);
    }

    @Test
    void setsListsAndRevertsTemporaryLogLevels() {
        logging.levels.put("ROOT", LogLevel.INFO);
        logging.levels.put("com.example.Order", null);
        ToolsController controller = controller(true, true);

        LogLevelOverrides.OverrideView override = controller.setLevel(
                new ToolsController.LevelRequest("com.example.Order", "debug", 60L), request());
        assertThat(override.level()).isEqualTo("DEBUG");
        assertThat(override.operator()).isEqualTo("ops@10.0.0.1");
        assertThat(logging.levels).containsEntry("com.example.Order", LogLevel.DEBUG);

        ToolsController.Loggers loggers = controller.loggers("order", 10);
        assertThat(loggers.loggers().loggers()).singleElement()
                .satisfies(logger -> assertThat(logger.overriddenUntil()).isNotNull());
        assertThat(loggers.overrides()).hasSize(1);

        assertThat(controller.revertLevel("com.example.Order", request()).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(logging.levels.get("com.example.Order")).isNull();
        assertStatus(() -> controller.revertLevel("com.example.Order", request()), HttpStatus.NOT_FOUND);

        assertStatus(() -> controller.setLevel(null, request()), HttpStatus.BAD_REQUEST);
        assertStatus(() -> controller.setLevel(new ToolsController.LevelRequest("x", "DEBUG", null), request()),
                HttpStatus.BAD_REQUEST);
        assertStatus(() -> controller.setLevel(new ToolsController.LevelRequest("x", "LOUD", 60L), request()),
                HttpStatus.BAD_REQUEST);
        assertStatus(() -> controller.setLevel(new ToolsController.LevelRequest("x", "DEBUG", 3600L), request()),
                HttpStatus.BAD_REQUEST);
        for (int index = 0; index < 50; index++) {
            controller.setLevel(new ToolsController.LevelRequest("bulk.logger" + index, "DEBUG", 60L), request());
        }
        assertStatus(() -> controller.setLevel(new ToolsController.LevelRequest("bulk.extra", "DEBUG", 60L), request()),
                HttpStatus.CONFLICT);
    }

    @Test
    void requiresTheOperatePermissionForSensitiveTools() {
        ToolsController viewer = controller(true, false);
        MockHttpServletRequest request = request();

        assertThat(viewer.loggers(null, 10)).isNotNull();
        assertStatus(() -> viewer.setLevel(new ToolsController.LevelRequest("x", "DEBUG", 60L), request), HttpStatus.FORBIDDEN);
        assertStatus(() -> viewer.revertLevel("x", request), HttpStatus.FORBIDDEN);
        assertStatus(() -> viewer.property("demo.name"), HttpStatus.FORBIDDEN);
        assertStatus(() -> viewer.searchProperties("demo"), HttpStatus.FORBIDDEN);
        assertStatus(() -> {
            try {
                viewer.heapDump(request, new MockHttpServletResponse());
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        }, HttpStatus.FORBIDDEN);

        ToolsController anonymous = controller(false, true);
        assertStatus(() -> anonymous.loggers(null, 10), HttpStatus.FORBIDDEN);
        assertStatus(() -> anonymous.classSource("java.lang.String"), HttpStatus.FORBIDDEN);
    }

    @Test
    void profilesCpuAndServesTheResult() {
        ToolsController viewer = controller(true, false);
        assertStatus(() -> viewer.startProfile(null, request()), HttpStatus.FORBIDDEN);
        assertStatus(viewer::latestProfile, HttpStatus.FORBIDDEN);
        assertStatus(() -> viewer.profile("any"), HttpStatus.FORBIDDEN);
        assertStatus(() -> viewer.stopProfile("any"), HttpStatus.FORBIDDEN);
        assertStatus(() -> viewer.collapsed("any"), HttpStatus.FORBIDDEN);
        ToolsController controller = controller(true, true);
        assertThat(controller.latestProfile().getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        CpuProfiler.SessionView session = controller.startProfile(
                new ToolsController.ProfileRequest(1L, 10, true, false, true), request());
        assertThat(controller.status().profile()).isNotNull();
        assertThat(viewer.status().profile()).isNull();
        assertThat(session.operator()).isEqualTo("ops@10.0.0.1");
        assertThat(session.includeBlocked()).isTrue();
        assertThat(session.excludeIdle()).isFalse();
        assertThat(session.groupByThread()).isTrue();
        assertStatus(() -> controller.startProfile(null, request()), HttpStatus.TOO_MANY_REQUESTS);
        await().atMost(10, TimeUnit.SECONDS)
                .until(() -> !"RUNNING".equals(controller.latestProfile().getBody().state()));

        CpuProfiler.Profile profile = controller.profile(session.id());
        assertThat(profile.session().state()).isEqualTo("FINISHED");
        assertThat(profile.root().total()).isPositive();
        var collapsed = controller.collapsed(session.id());
        assertThat(collapsed.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .contains("cpu-profile-" + session.id() + ".collapsed.txt");
        assertThat(collapsed.getBody()).isNotBlank();

        assertStatus(() -> controller.profile("missing"), HttpStatus.NOT_FOUND);
        assertStatus(() -> controller.collapsed("missing"), HttpStatus.NOT_FOUND);
        assertStatus(() -> controller.stopProfile("missing"), HttpStatus.NOT_FOUND);
        assertStatus(() -> controller.startProfile(new ToolsController.ProfileRequest(120L, null, null, null, null),
                request()), HttpStatus.BAD_REQUEST);

        CpuProfiler.SessionView defaults = controller.startProfile(null, request());
        assertThat(defaults.durationMs()).isEqualTo(10_000);
        assertThat(defaults.intervalMs()).isEqualTo(20);
        assertThat(defaults.includeBlocked()).isFalse();
        assertThat(defaults.excludeIdle()).isTrue();
        assertThat(controller.stopProfile(defaults.id()).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void takesHeapHistogramsWithinLimits() throws Exception {
        assertStatus(() -> controller(true, false).histogram(5), HttpStatus.FORBIDDEN);
        ToolsController controller = controller(true, true);
        HeapDiagnostics.Histogram histogram = new HeapDiagnostics.Histogram(Instant.now(), 10, 100, 1, null, null, null,
                List.of(new HeapDiagnostics.HistogramRow("byte[]", 10, 100, null, null, false)), List.of());
        when(heap.histogram(5)).thenReturn(histogram);

        assertThat(controller.histogram(5)).isSameAs(histogram);
        assertStatus(() -> controller.histogram(0), HttpStatus.BAD_REQUEST);
        assertStatus(() -> controller.histogram(501), HttpStatus.BAD_REQUEST);
        assertStatus(() -> controller.histogram(6), HttpStatus.TOO_MANY_REQUESTS);
        when(heap.histogram(7)).thenThrow(new MBeanException(new Exception("no"), "unavailable"));
        assertStatus(() -> controller.histogram(7), HttpStatus.NOT_IMPLEMENTED);
        when(heap.histogram(8)).thenThrow(new IllegalStateException("odd output"));
        assertStatus(() -> controller.histogram(8), HttpStatus.NOT_IMPLEMENTED);
    }

    @Test
    void streamsAndDeletesHeapDumps(@TempDir Path directory) throws Exception {
        ToolsController controller = controller(true, true);
        Path file = Files.writeString(directory.resolve("heap.hprof"), "JAVA PROFILE");
        HeapDiagnostics.HeapDump dump = mock(HeapDiagnostics.HeapDump.class);
        when(dump.fileName()).thenReturn("heap.hprof");
        when(dump.file()).thenReturn(file);
        when(dump.size()).thenReturn(12L);
        when(heap.dumpHeap()).thenReturn(dump);
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.heapDump(request(), response);

        assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        assertThat(response.getContentLengthLong()).isEqualTo(12);
        assertThat(response.getHeader(HttpHeaders.CONTENT_DISPOSITION)).contains("heap.hprof");
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(response.getContentAsString()).isEqualTo("JAVA PROFILE");
        verify(dump).close();

        doReturn(null).when(heap).dumpHeap();
        assertDumpStatus(controller, HttpStatus.TOO_MANY_REQUESTS);
        doThrow(new IllegalStateException("disk full")).when(heap).dumpHeap();
        assertDumpStatus(controller, HttpStatus.INSUFFICIENT_STORAGE);
        doThrow(new UnsupportedOperationException("no hotspot")).when(heap).dumpHeap();
        assertDumpStatus(controller, HttpStatus.NOT_IMPLEMENTED);
    }

    private static void assertDumpStatus(ToolsController controller, HttpStatus status) {
        assertStatus(() -> {
            try {
                controller.heapDump(request(), new MockHttpServletResponse());
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        }, status);
    }

    @Test
    void looksUpConfigurationAndClasses() {
        ToolsController controller = controller(true, true);

        ConfigSources.Lookup lookup = controller.property("demo.name");
        assertThat(lookup.value()).isEqualTo("meerkat");
        assertThat(lookup.definitions()).singleElement().satisfies(definition -> assertThat(definition.winner()).isTrue());
        assertThat(controller.searchProperties("demo.").matches()).extracting(ConfigSources.Match::name).containsExactly("demo.name");
        assertStatus(() -> controller.property(" "), HttpStatus.BAD_REQUEST);
        assertStatus(() -> controller.searchProperties("x".repeat(300)), HttpStatus.BAD_REQUEST);

        ClassSources.ClassLookup lookup2 = controller.classSource(getClass().getName());
        assertThat(lookup2.loaded()).isTrue();
        assertStatus(() -> controller.classSource("not a class"), HttpStatus.BAD_REQUEST);
    }

    @Test
    void scansDuplicateClassesOneAtATime() {
        ClassSources scanner = mock(ClassSources.class);
        ClassSources.DuplicateScan scan = new ClassSources.DuplicateScan(1, 1, 1, 1, false, null, List.of(), List.of(), List.of());
        when(scanner.scanDuplicates()).thenReturn(scan, (ClassSources.DuplicateScan) null);
        ToolsController viewer = new ToolsController(policy(true, false), true, logLevels, profiler, heap, config, scanner);
        assertStatus(viewer::duplicateClasses, HttpStatus.FORBIDDEN);
        ToolsController controller = new ToolsController(policy(true, true), true, logLevels, profiler, heap, config, scanner);

        assertThat(controller.duplicateClasses()).isSameAs(scan);
        assertStatus(controller::duplicateClasses, HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void buildsItsToolsFromTheApplicationContext() {
        StaticApplicationContext context = new StaticApplicationContext();
        context.getBeanFactory().registerSingleton("springBootLoggingSystem", logging);
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        MeerkatProperties properties = new MeerkatProperties();
        properties.getTools().setLogLevelMaxDuration(Duration.ofMinutes(2));
        beans.addBean("meerkatProperties", properties);
        ObjectProvider<MeerkatProperties> provider = beans.getBeanProvider(MeerkatProperties.class);

        ToolsController controller = new ToolsController(policy(true, true), provider, context);
        ToolsController.Status status = controller.status();
        assertThat(status.loggingSystem()).isTrue();
        assertThat(status.maxLogLevelSeconds()).isEqualTo(120);
        assertThat(status.configAvailable()).isTrue();
        controller.destroy();

        ToolsController defaults = new ToolsController(policy(true, true),
                new StaticListableBeanFactory().getBeanProvider(MeerkatProperties.class), new StaticApplicationContext());
        assertThat(defaults.status().enabled()).isTrue();
        assertThat(defaults.status().loggingSystem()).isTrue();
        assertThat(defaults.status().maxLogLevelSeconds()).isEqualTo(3600);
        defaults.destroy();
    }

    @Test
    void identifiesTheOperator() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.2");
        assertThat(ToolsController.operator(request)).isEqualTo("anonymous@10.0.0.2");
        request.setRemoteUser(" ");
        assertThat(ToolsController.operator(request)).isEqualTo("anonymous@10.0.0.2");
        request.setRemoteUser("ops");
        assertThat(ToolsController.operator(request)).isEqualTo("ops@10.0.0.2");
        request.setUserPrincipal(() -> "alice");
        assertThat(ToolsController.operator(request)).isEqualTo("alice@10.0.0.2");
    }

    static final class FakeLoggingSystem extends LoggingSystem {
        final Map<String, LogLevel> levels = new LinkedHashMap<>();

        @Override
        public void beforeInitialize() {
        }

        @Override
        public Set<LogLevel> getSupportedLogLevels() {
            return EnumSet.allOf(LogLevel.class);
        }

        @Override
        public void setLogLevel(String loggerName, LogLevel level) {
            levels.put(loggerName, level);
        }

        @Override
        public List<LoggerConfiguration> getLoggerConfigurations() {
            List<LoggerConfiguration> configurations = new ArrayList<>();
            levels.forEach((name, level) -> configurations.add(configuration(name, level)));
            return configurations;
        }

        @Override
        public LoggerConfiguration getLoggerConfiguration(String loggerName) {
            return levels.containsKey(loggerName) ? configuration(loggerName, levels.get(loggerName)) : null;
        }

        private static LoggerConfiguration configuration(String name, LogLevel level) {
            return new LoggerConfiguration(name, level, level == null ? LogLevel.INFO : level);
        }
    }
}
