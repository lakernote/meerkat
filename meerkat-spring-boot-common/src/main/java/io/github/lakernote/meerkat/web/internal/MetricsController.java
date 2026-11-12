package io.github.lakernote.meerkat.web.internal;

import io.github.lakernote.meerkat.metrics.internal.MetricsArchive;
import io.github.lakernote.meerkat.metrics.internal.OperationStats;
import io.github.lakernote.meerkat.metrics.internal.ResourceHistory;
import io.github.lakernote.meerkat.web.ObservabilityAccessPolicy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.Locale;
import java.util.Map;

/**
 * Serves the runtime metrics pages (call ranking and trends) and their API. The pages are always available so
 * navigation links never break; when metrics are disabled the API answers 404 and the pages explain how to enable
 * them.
 */
@RestController
public class MetricsController {
    private static final Resource STATS_PAGE = new ClassPathResource("META-INF/resources/meerkat/stats.html");
    private static final Resource METRICS_PAGE =
            new ClassPathResource("META-INF/resources/meerkat/metrics.html");
    private static final String API = "/api/meerkat/metrics";
    private final OperationStats operationStats;
    private final ResourceHistory resourceHistory;
    private final MetricsArchive archive;
    private final ObservabilityAccessPolicy accessPolicy;

    @Autowired
    public MetricsController(ObjectProvider<OperationStats> operationStats,
                             ObjectProvider<ResourceHistory> resourceHistory, ObjectProvider<MetricsArchive> archive,
                             ObservabilityAccessPolicy accessPolicy) {
        this(operationStats.getIfAvailable(), resourceHistory.getIfAvailable(), archive.getIfAvailable(),
                accessPolicy);
    }

    MetricsController(OperationStats operationStats, ResourceHistory resourceHistory, MetricsArchive archive,
                      ObservabilityAccessPolicy accessPolicy) {
        this.operationStats = operationStats;
        this.resourceHistory = resourceHistory;
        this.archive = archive;
        this.accessPolicy = accessPolicy;
    }

    @GetMapping("/meerkat/stats")
    public ResponseEntity<Void> redirectToStats() {
        // Keep the redirect relative so servlet context paths and reverse-proxy prefixes are preserved.
        return ResponseEntity.status(HttpStatus.PERMANENT_REDIRECT).location(URI.create("stats/")).build();
    }

    @GetMapping("/meerkat/stats/")
    public ResponseEntity<Resource> statsPage() {
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(STATS_PAGE);
    }

    @GetMapping("/meerkat/metrics")
    public ResponseEntity<Void> redirectToMetrics() {
        return ResponseEntity.status(HttpStatus.PERMANENT_REDIRECT).location(URI.create("metrics/")).build();
    }

    @GetMapping("/meerkat/metrics/")
    public ResponseEntity<Resource> metricsPage() {
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(METRICS_PAGE);
    }

    @GetMapping(API + "/operations")
    public OperationStats.Snapshot operations(@RequestParam(defaultValue = "1h") String window) {
        requireAccess();
        OperationStats stats = requireEnabled(operationStats);
        try {
            return stats.snapshot(OperationStats.Window.parse(window));
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "window must be 1h, today, 7d or 30d");
        }
    }

    @GetMapping(API + "/operations/trend")
    public OperationStats.Trend operationTrend(@RequestParam String category, @RequestParam String name,
                                               @RequestParam(defaultValue = "1h") String period) {
        requireAccess();
        OperationStats stats = requireEnabled(operationStats);
        return stats.trend(category(category), name, period(period));
    }

    @GetMapping(API + "/operations/trends")
    public Map<OperationStats.Category, OperationStats.Trend> operationTrends(
            @RequestParam(defaultValue = "1h") String period) {
        requireAccess();
        return requireEnabled(operationStats).trends(period(period));
    }

    @GetMapping(API + "/resources")
    public ResourceHistory.Series resources(@RequestParam(defaultValue = "1h") String period) {
        requireAccess();
        return requireEnabled(resourceHistory).series(period(period));
    }

    @GetMapping(API + "/storage")
    public MetricsArchive.StorageStatus storage() {
        requireAccess();
        return requireEnabled(archive).status();
    }

    @PostMapping(API + "/operations/reset")
    public ResponseEntity<Void> reset() {
        requireAccess();
        if (!accessPolicy.canClear()) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        try {
            requireEnabled(operationStats).clear();
        } catch (java.io.UncheckedIOException failure) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Could not delete the saved operation history", failure);
        }
        return ResponseEntity.noContent().build();
    }

    private static OperationStats.Period period(String value) {
        try {
            return OperationStats.Period.parse(value);
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "period must be 1h, 24h, 7d or 30d");
        }
    }

    private static OperationStats.Category category(String value) {
        try {
            return OperationStats.Category.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown category: " + value);
        }
    }

    private static <T> T requireEnabled(T component) {
        if (component == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Runtime metrics are disabled");
        return component;
    }

    private void requireAccess() {
        if (!accessPolicy.canView()) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
}
