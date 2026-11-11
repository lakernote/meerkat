package io.github.lakernote.meerkat.starter.boot3;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.lakernote.meerkat.metrics.internal.ResourceMetricsRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(classes = RuntimeMetricsE2ETest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "meerkat.storage.directory=target/runtime-metrics-e2e/${random.uuid}"
        })
class RuntimeMetricsE2ETest {
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @LocalServerPort
    private int port;

    @Autowired
    private ResourceMetricsRegistry resourceRegistry;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private org.springframework.core.env.Environment environment;

    @Test
    void ranksRealHttpEndpointsAndSqlTemplatesOnTheStatisticsPage() throws Exception {
        assertThat(get("/orders/1?items=1").statusCode()).isEqualTo(200);
        assertThat(get("/orders/2?items=3").statusCode()).isEqualTo(200);
        assertThat(get("/orders/3?items=5&sleepMs=600").statusCode()).isEqualTo(200);
        assertThat(get("/fail").statusCode()).isEqualTo(500);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            String body = get("/api/meerkat/metrics/operations?window=1h").body();
            assertThat(body)
                    .contains("\"window\":\"HOUR\"", "\"collecting\":true", "\"category\":\"HTTP\"",
                            "\"category\":\"SQL\"")
                    .contains("\"name\":\"/orders/{id} · GET\",\"count\":3,\"errors\":0")
                    .contains("\"name\":\"/fail · GET\",\"count\":1,\"errors\":1")
                    .contains("\"name\":\"SELECT COUNT(*) FROM orders WHERE id IN (?…)\",\"count\":3,\"errors\":0")
                    .doesNotContain("meerkat/", "traceQuery");
            // Entries carry the CPU time and allocation of the request thread; dependency calls cannot.
            assertThat(body)
                    .containsPattern(java.util.regex.Pattern.quote("\"name\":\"/orders/{id} · GET\",")
                            + "[^}]*\"meanCpuMs\":[0-9.]+,\"meanAllocatedBytes\":[1-9][0-9]*}")
                    .containsPattern(java.util.regex.Pattern.quote("\"name\":\"SELECT COUNT(*) FROM orders")
                            + "[^}]*\"meanCpuMs\":null,\"meanAllocatedBytes\":null}");
        });
        assertThat(get("/api/meerkat/metrics/operations?window=today").body())
                .contains("\"window\":\"TODAY\"", "\"persistent\":true", "\"name\":\"/fail · GET\"");
        assertThat(get("/api/meerkat/metrics/operations?window=30d").body())
                .contains("\"window\":\"MONTH\"", "\"name\":\"/fail · GET\"");
        assertThat(get("/api/meerkat/metrics/operations?window=week").statusCode()).isEqualTo(400);

        String trend = get("/api/meerkat/metrics/operations/trend?category=HTTP&name="
                + java.net.URLEncoder.encode("/orders/{id} · GET", java.nio.charset.StandardCharsets.UTF_8)
                + "&period=1h").body();
        assertThat(trend).contains("\"period\":\"HOUR\"", "\"stepMillis\":60000", "\"count\":3,\"errors\":0");
        assertThat(get("/api/meerkat/metrics/operations/trend?category=HTTP&name=x&period=24h").body())
                .contains("\"period\":\"DAY\"", "\"stepMillis\":600000");
        assertThat(get("/api/meerkat/metrics/operations/trends?period=7d").body())
                .contains("\"HTTP\":{\"period\":\"WEEK\"", "\"SQL\":{");
        assertThat(get("/api/meerkat/metrics/operations/trend?category=NOPE&name=x").statusCode())
                .isEqualTo(400);
        assertThat(HTTP_CLIENT.send(HttpRequest.newBuilder(uri("/api/meerkat/metrics/operations/reset"))
                        .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(403);

        HttpResponse<String> redirect = get("/meerkat/stats");
        assertThat(redirect.statusCode()).isEqualTo(308);
        assertThat(redirect.headers().firstValue("Location")).hasValue("stats/");
        HttpResponse<String> page = get("/meerkat/stats/");
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body()).contains("调用排行", "../assets/stats.js", "../metrics/");
        for (String asset : new String[] {"stats.js", "stats.css", "metrics.js", "metrics-common.js", "uplot.min.js"}) {
            assertThat(get("/meerkat/assets/" + asset).statusCode()).as(asset).isEqualTo(200);
        }
        assertThat(get("/meerkat/metrics/").body()).contains("../assets/metrics.js");
        assertThat(get("/meerkat/").body()).contains("href=\"stats/\"");
    }

    @Test
    void recordsSpringBootResourceMetersThroughTheMicrometerRegistry() throws Exception {
        // Tomcat thread meters need the MBean registry, which the starter turns on by default. Tomcat's registry
        // switch is JVM-wide, so the meters themselves are only asserted in RuntimeMetricsDefaultsTest.
        assertThat(environment.getProperty("server.tomcat.mbeanregistry.enabled")).isEqualTo("true");
        assertThat(meterRegistry).isInstanceOf(CompositeMeterRegistry.class);
        assertThat(((CompositeMeterRegistry) meterRegistry).getRegistries()).contains(resourceRegistry);
        meterRegistry.counter("orders.created").increment(2);
        io.micrometer.core.instrument.Timer.builder("http.server.requests").tags("uri", "/x", "status", "404")
                .register(meterRegistry).record(java.time.Duration.ofMillis(5));
        io.micrometer.core.instrument.Timer.builder("http.server.requests").tags("uri", "/y", "status", "429")
                .register(meterRegistry).record(java.time.Duration.ofMillis(5));

        ReflectionTestUtils.invokeMethod(resourceRegistry, "publish");

        String body = get("/api/meerkat/metrics/resources?period=1h").body();
        assertThat(body).contains("\"period\":\"HOUR\"", "\"persistent\":true", "\"jvm.memory.used:heap\":[",
                "\"jvm.memory.max:heap\":[",
                "\"jvm.threads.live\":[", "\"jvm.threads.peak\":[", "\"process.cpu.usage\":[",
                "\"process.cpu.usage@max\":[", "\"system.cpu.count\":[", "\"process.uptime\":[",
                "\"hikaricp.connections.max:HikariPool-1\":[", "\"hikaricp.connections.pending:HikariPool-1@max\":[",
                "\"executor.active:applicationTaskExecutor@max\":[", "\"executor.pool.core:applicationTaskExecutor\":[",
                "\"jvm.gc.live.data.size\":[", "\"disk.free:", "\"tomcat.sessions.active.current\":[",
                "\"http.server.requests:4xx\":[", "\"http.server.requests:429\":[",
                "\"meerkat.executor.rejected:applicationTaskExecutor\":[")
                .doesNotContain("orders.created", "meerkat.dashboard");
        assertThat(get("/api/meerkat/metrics/resources?period=2h").statusCode()).isEqualTo(400);
        assertThat(get("/api/meerkat/metrics/storage").body())
                .contains("\"persistent\":true", "\"retentionDays\":30");
    }

    @Test
    void keepsTheDayRankingAcrossRestarts() throws Exception {
        Path directory = Files.createTempDirectory("operation-stats-restart");
        try {
            for (int run = 1; run <= 2; run++) {
                try (ConfigurableApplicationContext application = new SpringApplicationBuilder(TestApplication.class)
                        .properties("server.port=0", "meerkat.storage.directory=" + directory)
                        .run()) {
                    int applicationPort = Integer.parseInt(
                            application.getEnvironment().getRequiredProperty("local.server.port"));
                    assertThat(get(applicationPort, "/orders/1?items=1").statusCode()).isEqualTo(200);
                    long expected = run;
                    await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(
                            get(applicationPort, "/api/meerkat/metrics/operations?window=today").body())
                            .contains("\"persistent\":true", "\"name\":\"/orders/{id} · GET\",\"count\":"
                                    + expected + ",")
                            .containsPattern("\"meanAllocatedBytes\":[1-9][0-9]*}"));
                }
            }
            try (var files = Files.list(directory.resolve("metrics"))) {
                assertThat(files.map(file -> file.getFileName().toString()))
                        .anySatisfy(name -> assertThat(name).matches("operations-\\d{8}\\.tsv"));
            }
        } finally {
            try (var paths = Files.walk(directory)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }

    private HttpResponse<String> get(int applicationPort, String path) throws Exception {
        return HTTP_CLIENT.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + applicationPort + path))
                        .timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return HTTP_CLIENT.send(HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    @SpringBootConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import(OrdersController.class)
    static class TestApplication {
        @Bean(destroyMethod = "close")
        HikariDataSource dataSource() {
            HikariConfig configuration = new HikariConfig();
            configuration.setJdbcUrl("jdbc:h2:mem:operation-stats-e2e;DB_CLOSE_DELAY=-1");
            configuration.setUsername("sa");
            configuration.setMaximumPoolSize(2);
            return new HikariDataSource(configuration);
        }
    }

    @RestController
    static class OrdersController {
        private final JdbcTemplate jdbcTemplate;

        OrdersController(JdbcTemplate jdbcTemplate) {
            this.jdbcTemplate = jdbcTemplate;
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS orders (id INT PRIMARY KEY)");
        }

        @GetMapping("/orders/{id}")
        Integer order(@PathVariable int id, @RequestParam int items, @RequestParam(defaultValue = "0") long sleepMs)
                throws InterruptedException {
            Thread.sleep(sleepMs);
            String placeholders = String.join(", ", Collections.nCopies(items, "?"));
            Object[] ids = new Object[items];
            for (int i = 0; i < items; i++) ids[i] = id + i;
            return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM orders WHERE id IN (" + placeholders + ")",
                    Integer.class, ids);
        }

        @GetMapping("/fail")
        String fail() {
            throw new IllegalStateException("boom");
        }
    }
}
