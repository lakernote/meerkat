package io.github.lakernote.meerkat.starter.boot3;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lakernote.meerkat.trace.internal.TraceNode;
import io.github.lakernote.meerkat.trace.internal.store.TraceRecord;
import io.github.lakernote.meerkat.trace.internal.store.TraceStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.FileSystemUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** A capture session records the entry, SQL and outbound HTTP of matching requests, and nothing else. */
@SpringBootTest(classes = TrafficCaptureE2ETest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "meerkat.metrics.enabled=false",
                "spring.datasource.url=jdbc:h2:mem:capture;DB_CLOSE_DELAY=-1",
                "spring.datasource.generate-unique-name=false",
                "spring.datasource.hikari.pool-name=capture-pool"
        })
class TrafficCaptureE2ETest {
    private static final Path STORAGE_DIRECTORY = createStorageDirectory();
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @Autowired
    private TraceStore traceStore;

    @org.springframework.test.context.DynamicPropertySource
    static void traceStorage(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("meerkat.storage.directory", STORAGE_DIRECTORY::toString);
    }

    @AfterAll
    static void deleteStorage() throws IOException {
        FileSystemUtils.deleteRecursively(STORAGE_DIRECTORY);
    }

    @Test
    void capturesMatchingRequestsWithTheirSqlAndOutboundCalls() throws Exception {
        JsonNode status = JSON.readTree(get("/api/meerkat/capture/status").body());
        assertThat(status.path("enabled").asBoolean()).isTrue();
        assertThat(status.path("canCapture").asBoolean()).isTrue();

        HttpResponse<String> started = post("/api/meerkat/capture/sessions", """
                {"method":"POST","pathPattern":"/orders/**","headerName":"X-Debug-User","headerValue":"alice",
                 "maxCount":1,"durationSeconds":60}""", Map.of());
        assertThat(started.statusCode()).as(started.body()).isEqualTo(200);
        String sessionId = JSON.readTree(started.body()).path("id").asText();

        // Not matching: wrong header value.
        HttpResponse<String> ordinary = post("/orders/7", "{\"note\":\"bob\"}", Map.of("X-Debug-User", "bob"));
        assertThat(ordinary.statusCode()).isEqualTo(500);
        String ordinaryTraceId = ordinary.headers().firstValue("X-Trace-Id").orElseThrow();
        TraceRecord ordinaryTrace = awaitTrace(ordinaryTraceId);
        assertThat(ordinaryTrace).isNotNull();
        assertThat(ordinaryTrace.treeNodes()).isNotEmpty();
        assertThat(flattenNodes(ordinaryTrace.treeNodes()))
                .anySatisfy(node -> assertThat(node.kind()).isEqualTo("Database"))
                .allSatisfy(node -> assertThat(node.captures()).isEmpty());

        HttpResponse<String> captured = post("/orders/7", "{\"note\":\"secret-note\"}",
                Map.of("X-Debug-User", "alice"));
        assertThat(captured.statusCode()).isEqualTo(500);
        String traceId = captured.headers().firstValue("X-Trace-Id").orElseThrow();
        // The session stops after one request.
        post("/orders/8", "{\"note\":\"late\"}", Map.of("X-Debug-User", "alice"));

        JsonNode records = awaitRecords(sessionId);
        assertThat(records).hasSize(1);
        JsonNode summary = records.get(0);
        assertThat(summary.path("traceId").asText()).isEqualTo(traceId);
        assertThat(summary.path("status").asInt()).isEqualTo(500);
        JsonNode record = JSON.readTree(get("/api/meerkat/capture/records/" + summary.path("id").asText()).body());

        List<JsonNode> nodes = new ArrayList<>();
        record.path("nodes").forEach(node -> flatten(node, nodes));
        JsonNode root = nodes.get(0);
        assertThat(root.path("captures").path("request").asText()).contains("POST /orders/7")
                .containsIgnoringCase("x-debug-user: alice");
        assertThat(root.path("captures").path("request.body").asText()).isEqualTo("{\"note\":\"secret-note\"}");
        assertThat(root.path("captures").path("response").asText()).contains("500");
        assertThat(root.path("captures").path("response.body").asText()).contains("order=7 widget", "updated=1",
                "restTemplate=pong:" + traceId, "restClient=pong:" + traceId);

        List<JsonNode> sql = nodes.stream().filter(node -> "Database".equals(node.path("kind").asText())).toList();
        JsonNode select = sql.stream().filter(node -> node.path("captures").path("sql").asText().startsWith("SELECT"))
                .findFirst().orElseThrow();
        assertThat(select.path("captures").path("sql").asText()).isEqualTo("SELECT id, name FROM orders WHERE id = ?");
        assertThat(select.path("captures").path("params").asText()).isEqualTo("[1] 7");
        assertThat(select.path("captures").path("rows").asText()).contains("ID | NAME", "7 | widget", "rows read: 1");
        JsonNode update = sql.stream().filter(node -> node.path("captures").path("sql").asText().startsWith("UPDATE"))
                .findFirst().orElseThrow();
        assertThat(update.path("captures").path("params").asText()).isEqualTo("[1] 'secret-note', [2] 7");
        assertThat(update.path("captures").path("result").asText()).isEqualTo("updated rows: 1");

        List<JsonNode> remote = nodes.stream().filter(node -> "Remote".equals(node.path("kind").asText())).toList();
        assertThat(remote).hasSize(2).allSatisfy(node -> {
            assertThat(node.path("captures").path("request").asText()).contains("POST http://localhost:" + port + "/ping");
            assertThat(node.path("captures").path("request.body").asText()).isEqualTo("hello");
            assertThat(node.path("captures").path("response").asText()).contains("200");
            assertThat(node.path("captures").path("response.body").asText()).isEqualTo("pong:" + traceId);
        });

        // Captured data stays out of the normal trace store.
        TraceRecord trace = awaitTrace(traceId);
        assertThat(trace).isNotNull();
        assertThat(trace.treeNodes()).isNotEmpty();
        assertThat(flattenNodes(trace.treeNodes())).allSatisfy(node -> assertThat(node.captures()).isEmpty());
        assertThat(get("/api/meerkat/traces/" + traceId).body()).doesNotContain("secret-note");
    }

    @Test
    void capturesSuccessfulRequestsWithTheirRequestResponseAndCallTree() throws Exception {
        HttpResponse<String> started = post("/api/meerkat/capture/sessions", """
                {"method":"POST","pathPattern":"/capture-success","maxCount":1,"durationSeconds":60}
                """, Map.of());
        assertThat(started.statusCode()).as(started.body()).isEqualTo(200);
        String sessionId = JSON.readTree(started.body()).path("id").asText();

        HttpResponse<String> response = post("/capture-success", "hello", Map.of());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("received:hello");
        String traceId = response.headers().firstValue("X-Trace-Id").orElseThrow();

        JsonNode records = awaitRecords(sessionId);
        assertThat(records).hasSize(1);
        JsonNode record = JSON.readTree(get("/api/meerkat/capture/records/"
                + records.get(0).path("id").asText()).body());
        assertThat(record.path("traceId").asText()).isEqualTo(traceId);
        assertThat(record.path("status").asInt()).isEqualTo(200);
        assertThat(record.path("nodes")).hasSize(1);
        JsonNode root = record.path("nodes").get(0);
        assertThat(root.path("kind").asText()).isEqualTo("Http");
        assertThat(root.path("captures").path("request").asText()).contains("POST /capture-success");
        assertThat(root.path("captures").path("request.body").asText()).isEqualTo("hello");
        assertThat(root.path("captures").path("response").asText()).contains("200");
        assertThat(root.path("captures").path("response.body").asText()).isEqualTo("received:hello");
    }

    private JsonNode awaitRecords(String sessionId) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        JsonNode records;
        do {
            records = JSON.readTree(get("/api/meerkat/capture/records?sessionId=" + sessionId).body());
            if (!records.isEmpty()) {
                Thread.sleep(200);
                return JSON.readTree(get("/api/meerkat/capture/records?sessionId=" + sessionId).body());
            }
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        return records;
    }

    private TraceRecord awaitTrace(String traceId) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        do {
            TraceRecord trace = traceStore.find(traceId, "HTTP");
            if (trace != null) return trace;
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        return null;
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body, Map<String, String> headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(request::header);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void flatten(JsonNode node, List<JsonNode> all) {
        all.add(node);
        node.path("children").forEach(child -> flatten(child, all));
    }

    private static List<TraceNode> flattenNodes(List<TraceNode> nodes) {
        List<TraceNode> all = new ArrayList<>();
        for (TraceNode node : nodes) {
            all.add(node);
            all.addAll(flattenNodes(node.children()));
        }
        return all;
    }

    private static Path createStorageDirectory() {
        try {
            return Files.createTempDirectory("meerkat-capture-");
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    @SpringBootConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import(OrdersController.class)
    static class TestApplication {
        @Bean
        io.github.lakernote.meerkat.web.ObservabilityAccessPolicy captureAllowed() {
            return new io.github.lakernote.meerkat.web.ObservabilityAccessPolicy() {
                @Override
                public boolean canView() {
                    return true;
                }

                @Override
                public boolean canCapture() {
                    return true;
                }
            };
        }

        @Bean
        RestTemplate plainRestTemplate() {
            return new RestTemplate();
        }

        @Bean
        RestClient plainRestClient() {
            return RestClient.create();
        }
    }

    @RestController
    static class OrdersController {
        private final JdbcTemplate jdbc;
        private final RestTemplate restTemplate;
        private final RestClient restClient;
        private final Environment environment;

        OrdersController(JdbcTemplate jdbc, RestTemplate restTemplate, RestClient restClient,
                         Environment environment) {
            this.jdbc = jdbc;
            this.restTemplate = restTemplate;
            this.restClient = restClient;
            this.environment = environment;
            jdbc.execute("CREATE TABLE IF NOT EXISTS orders (id INT PRIMARY KEY, name VARCHAR(50), note VARCHAR(50))");
            jdbc.execute("MERGE INTO orders (id, name) KEY (id) VALUES (7, 'widget'), (8, 'gadget')");
        }

        @PostMapping(value = "/ping", produces = "text/plain")
        String ping(@RequestHeader(value = "X-Trace-Id", defaultValue = "") String traceId) {
            return "pong:" + traceId;
        }

        @PostMapping(value = "/capture-success", produces = "text/plain")
        String captureSuccess(@RequestBody String body) {
            return "received:" + body;
        }

        @PostMapping(value = "/orders/{id}", produces = "text/plain")
        ResponseEntity<String> order(@PathVariable int id, @RequestBody Map<String, String> body) {
            String order = jdbc.queryForObject("SELECT id, name FROM orders WHERE id = ?",
                    (rs, row) -> rs.getInt("id") + " " + rs.getString("name"), id);
            int updated = jdbc.update("UPDATE orders SET note = ? WHERE id = ?", body.get("note"), id);
            String url = "http://localhost:" + environment.getProperty("local.server.port") + "/ping";
            String viaTemplate = restTemplate.postForObject(url, "hello", String.class);
            String viaClient = restClient.post().uri(url).contentType(MediaType.TEXT_PLAIN).body("hello")
                    .retrieve().body(String.class);
            // An error response keeps the trace in the store, so the test can check it holds no captured data.
            return ResponseEntity.status(500).body("order=" + order + "\nupdated=" + updated
                    + "\nrestTemplate=" + viaTemplate + "\nrestClient=" + viaClient);
        }
    }
}
