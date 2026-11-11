package io.github.lakernote.meerkat.web.internal;

import io.github.lakernote.meerkat.config.MeerkatProperties;
import io.github.lakernote.meerkat.diagnostics.internal.ClassSources;
import io.github.lakernote.meerkat.diagnostics.internal.ConfigSources;
import io.github.lakernote.meerkat.diagnostics.internal.CpuProfiler;
import io.github.lakernote.meerkat.diagnostics.internal.HeapDiagnostics;
import io.github.lakernote.meerkat.diagnostics.internal.LogLevelOverrides;
import io.github.lakernote.meerkat.web.ObservabilityAccessPolicy;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import javax.management.JMException;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.Principal;
import java.time.Duration;
import java.util.List;

/**
 * Serves the toolbox page and its on-demand API. Listing loggers and looking up a class need the view permission;
 * changing log levels, CPU profiling, heap histograms and dumps, reading configuration values and scanning the class
 * path for duplicates also need {@link ObservabilityAccessPolicy#canOperate()}.
 */
@RestController
public class ToolsController implements DisposableBean {
    private static final Resource TOOLS_PAGE = new ClassPathResource("META-INF/resources/meerkat/tools.html");
    private static final Logger log = LoggerFactory.getLogger(ToolsController.class);
    private static final String API = "/api/meerkat/tools";
    private static final String LOGGING_SYSTEM_BEAN = "springBootLoggingSystem";
    private final ObservabilityAccessPolicy accessPolicy;
    private final boolean enabled;
    private final LogLevelOverrides logLevels;
    private final CpuProfiler profiler;
    private final HeapDiagnostics heap;
    private final ConfigSources config;
    private final ClassSources classes;

    @Autowired
    public ToolsController(ObservabilityAccessPolicy accessPolicy, ObjectProvider<MeerkatProperties> properties,
                           ApplicationContext context) {
        this(accessPolicy, tools(properties), loggingSystem(context), context);
    }

    private ToolsController(ObservabilityAccessPolicy accessPolicy, MeerkatProperties.Tools tools,
                            LoggingSystem loggingSystem, ApplicationContext context) {
        this(accessPolicy, tools.isEnabled(), loggingSystem == null ? null
                        : new LogLevelOverrides(loggingSystem, tools.getLogLevelMaxDuration()),
                new CpuProfiler(), new HeapDiagnostics(tools.getHeapHistogramInterval()),
                context.getEnvironment() instanceof ConfigurableEnvironment environment
                        ? new ConfigSources(environment) : null,
                new ClassSources(classLoader(context)));
    }

    ToolsController(ObservabilityAccessPolicy accessPolicy, boolean enabled, LogLevelOverrides logLevels,
                    CpuProfiler profiler, HeapDiagnostics heap, ConfigSources config, ClassSources classes) {
        this.accessPolicy = accessPolicy;
        this.enabled = enabled;
        this.logLevels = logLevels;
        this.profiler = profiler;
        this.heap = heap;
        this.config = config;
        this.classes = classes;
    }

    @GetMapping("/meerkat/tools")
    public ResponseEntity<Void> redirectToTools() {
        return ResponseEntity.status(HttpStatus.PERMANENT_REDIRECT).location(URI.create("tools/")).build();
    }

    @GetMapping("/meerkat/tools/")
    public ResponseEntity<Resource> toolsPage() {
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(TOOLS_PAGE);
    }

    @GetMapping(API + "/status")
    public Status status() {
        requireView();
        boolean canOperate = accessPolicy.canOperate();
        if (!enabled) return new Status(false, canOperate, false, List.of(), 0, 0, null, false, false);
        return new Status(true, canOperate, logLevels != null, logLevels == null ? List.of() : logLevels.levels(),
                logLevels == null ? 0 : logLevels.maxDurationSeconds(), CpuProfiler.MAX_DURATION.toSeconds(),
                canOperate ? profiler.latest() : null, heap.hasPrevious(), config != null);
    }

    // ---- log levels ----

    @GetMapping(API + "/loggers")
    public Loggers loggers(@RequestParam(required = false) String q,
                           @RequestParam(defaultValue = "200") int limit) {
        LogLevelOverrides overrides = logLevels();
        return new Loggers(overrides.loggers(q, limit), overrides.active());
    }

    @PostMapping(API + "/loggers/overrides")
    public LogLevelOverrides.OverrideView setLevel(@RequestBody LevelRequest body, HttpServletRequest request) {
        requireOperate();
        LogLevelOverrides overrides = logLevels();
        if (body == null || body.durationSeconds() == null) throw badRequest("durationSeconds is required");
        try {
            return overrides.set(body.logger(), body.level(), Duration.ofSeconds(body.durationSeconds()),
                    operator(request));
        } catch (IllegalArgumentException invalid) {
            throw badRequest(invalid.getMessage());
        } catch (IllegalStateException full) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, full.getMessage());
        }
    }

    @PostMapping(API + "/loggers/overrides/revert")
    public ResponseEntity<Void> revertLevel(@RequestParam String logger, HttpServletRequest request) {
        requireOperate();
        if (!logLevels().revert(logger, operator(request))) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return ResponseEntity.noContent().build();
    }

    // ---- CPU profiler: needs the operate permission because sampling costs CPU and stacks reveal the code ----

    @PostMapping(API + "/profiler")
    public CpuProfiler.SessionView startProfile(@RequestBody(required = false) ProfileRequest body,
                                                HttpServletRequest request) {
        requireOperate();
        ProfileRequest options = body == null ? new ProfileRequest(null, null, null, null, null) : body;
        CpuProfiler.SessionView session;
        try {
            session = profiler.start(new CpuProfiler.Options(
                    Duration.ofSeconds(options.durationSeconds() == null ? 10 : options.durationSeconds()),
                    options.intervalMs() == null ? 20 : options.intervalMs(),
                    Boolean.TRUE.equals(options.includeBlocked()), !Boolean.FALSE.equals(options.excludeIdle()),
                    Boolean.TRUE.equals(options.groupByThread())), operator(request));
        } catch (IllegalArgumentException invalid) {
            throw badRequest(invalid.getMessage());
        }
        if (session == null) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Another profiling session is running");
        }
        return session;
    }

    @GetMapping(API + "/profiler")
    public ResponseEntity<CpuProfiler.SessionView> latestProfile() {
        requireOperate();
        CpuProfiler.SessionView latest = profiler.latest();
        return latest == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(latest);
    }

    @GetMapping(API + "/profiler/{id}")
    public CpuProfiler.Profile profile(@PathVariable String id) {
        requireOperate();
        CpuProfiler.Profile profile = profiler.profile(id);
        if (profile == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return profile;
    }

    @PostMapping(API + "/profiler/{id}/stop")
    public ResponseEntity<Void> stopProfile(@PathVariable String id) {
        requireOperate();
        if (!profiler.stop(id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return ResponseEntity.noContent().build();
    }

    /** The samples in the collapsed-stack format read by flamegraph.pl and speedscope. */
    @GetMapping(API + "/profiler/{id}/collapsed")
    public ResponseEntity<String> collapsed(@PathVariable String id) {
        requireOperate();
        String collapsed = profiler.collapsed(id);
        if (collapsed == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return ResponseEntity.ok()
                .contentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, attachment("cpu-profile-" + id + ".collapsed.txt"))
                .body(collapsed);
    }

    // ---- heap ----

    /**
     * Runs a full GC and a class histogram; answers 429 while one runs or shortly after the previous one. Needs the
     * operate permission because the full GC pauses the whole application for a time that grows with the heap.
     */
    @PostMapping(API + "/heap/histogram")
    public HeapDiagnostics.Histogram histogram(@RequestParam(defaultValue = "100") int limit) {
        requireOperate();
        if (limit < 1 || limit > HeapDiagnostics.MAX_ROWS) throw badRequest("limit must be between 1 and 500");
        HeapDiagnostics.Histogram histogram;
        try {
            histogram = heap.histogram(limit);
        } catch (JMException | RuntimeException unavailable) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
                    "This JVM cannot take a class histogram: " + unavailable.getMessage());
        }
        if (histogram == null) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "A heap histogram was taken moments ago");
        }
        return histogram;
    }

    /** Writes a heap dump of live objects to a temporary file, streams it and deletes it. */
    @PostMapping(API + "/heap/dump")
    public void heapDump(HttpServletRequest request, HttpServletResponse response) throws IOException {
        requireOperate();
        HeapDiagnostics.HeapDump dump;
        try {
            dump = heap.dumpHeap();
        } catch (IllegalStateException noSpace) {
            throw new ResponseStatusException(HttpStatus.INSUFFICIENT_STORAGE, noSpace.getMessage());
        } catch (UnsupportedOperationException unsupported) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, unsupported.getMessage());
        }
        if (dump == null) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "A heap dump is running");
        try (dump) {
            log.warn("Meerkat heap dump {} downloaded by {}", dump.fileName(), operator(request));
            response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
            response.setContentLengthLong(dump.size());
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION, attachment(dump.fileName()));
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            Files.copy(dump.file(), response.getOutputStream());
            response.flushBuffer();
        }
    }

    // ---- configuration ----

    @GetMapping(API + "/config")
    public ConfigSources.Lookup property(@RequestParam String name) {
        requireOperate();
        try {
            return config().lookup(name);
        } catch (IllegalArgumentException invalid) {
            throw badRequest(invalid.getMessage());
        }
    }

    @GetMapping(API + "/config/search")
    public ConfigSources.Search searchProperties(@RequestParam(defaultValue = "") String q) {
        requireOperate();
        try {
            return config().search(q);
        } catch (IllegalArgumentException invalid) {
            throw badRequest(invalid.getMessage());
        }
    }

    // ---- classes ----

    @GetMapping(API + "/classes")
    public ClassSources.ClassLookup classSource(@RequestParam String name) {
        requireEnabled();
        try {
            return classes.lookup(name);
        } catch (IllegalArgumentException invalid) {
            throw badRequest(invalid.getMessage());
        }
    }

    /**
     * Lists classes found in more than one jar; answers 429 while another scan runs. Needs the operate permission
     * because the scan reads every jar on the class path for up to 20 seconds.
     */
    @PostMapping(API + "/classes/duplicates")
    public ClassSources.DuplicateScan duplicateClasses() {
        requireOperate();
        ClassSources.DuplicateScan scan = classes.scanDuplicates();
        if (scan == null) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "A scan is running");
        return scan;
    }

    @Override
    public void destroy() {
        if (logLevels != null) logLevels.close();
        profiler.close();
    }

    private LogLevelOverrides logLevels() {
        requireEnabled();
        if (logLevels == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No Spring Boot logging system is available");
        }
        return logLevels;
    }

    private ConfigSources config() {
        if (config == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No configurable environment");
        return config;
    }

    private void requireView() {
        if (!accessPolicy.canView()) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }

    private void requireEnabled() {
        requireView();
        if (!enabled) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "The toolbox is disabled; set meerkat.tools.enabled=true");
        }
    }

    private void requireOperate() {
        requireEnabled();
        if (!accessPolicy.canOperate()) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static String attachment(String fileName) {
        return ContentDisposition.attachment().filename(fileName).build().toString();
    }

    private static MeerkatProperties.Tools tools(ObjectProvider<MeerkatProperties> properties) {
        MeerkatProperties meerkat = properties.getIfAvailable();
        return meerkat == null ? new MeerkatProperties.Tools() : meerkat.getTools();
    }

    private static LoggingSystem loggingSystem(ApplicationContext context) {
        if (context.containsBean(LOGGING_SYSTEM_BEAN)
                && context.getBean(LOGGING_SYSTEM_BEAN) instanceof LoggingSystem loggingSystem) {
            return loggingSystem;
        }
        try {
            return LoggingSystem.get(classLoader(context));
        } catch (IllegalStateException unavailable) {
            return null;
        }
    }

    private static ClassLoader classLoader(ApplicationContext context) {
        ClassLoader loader = context.getClassLoader();
        return loader != null ? loader : ToolsController.class.getClassLoader();
    }

    static String operator(HttpServletRequest request) {
        Principal principal = request.getUserPrincipal();
        String user = principal != null ? principal.getName() : request.getRemoteUser();
        return (user == null || user.isBlank() ? "anonymous" : user) + "@" + request.getRemoteAddr();
    }

    /**
     * {@code canOperate} tells the page whether the sensitive tools are allowed; {@code logLevels} lists the levels
     * the logging system accepts and {@code profile} is the running or latest profiling session, shown only to
     * operators.
     */
    public record Status(boolean enabled, boolean canOperate, boolean loggingSystem, List<String> logLevels,
                         long maxLogLevelSeconds, long maxProfileSeconds, CpuProfiler.SessionView profile,
                         boolean histogramBaseline, boolean configAvailable) { }

    public record Loggers(LogLevelOverrides.LoggerList loggers, List<LogLevelOverrides.OverrideView> overrides) { }

    public record LevelRequest(String logger, String level, Long durationSeconds) { }

    /** Null fields use the defaults: 10 seconds every 20 ms, RUNNABLE threads only, idle native waits skipped. */
    public record ProfileRequest(Long durationSeconds, Integer intervalMs, Boolean includeBlocked, Boolean excludeIdle,
                                 Boolean groupByThread) { }
}
