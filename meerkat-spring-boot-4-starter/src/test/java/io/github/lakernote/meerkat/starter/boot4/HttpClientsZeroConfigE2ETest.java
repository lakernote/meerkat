package io.github.lakernote.meerkat.starter.boot4;

import io.github.lakernote.meerkat.trace.internal.TraceNode;
import io.github.lakernote.meerkat.trace.internal.store.TraceRecord;
import io.github.lakernote.meerkat.trace.internal.store.TraceStore;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
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
import org.springframework.http.ResponseEntity;
import org.springframework.util.FileSystemUtils;
import org.springframework.web.bind.annotation.GetMapping;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** HTTP clients declared as plain beans are traced and propagate the trace ID without any application code. */
@SpringBootTest(classes = HttpClientsZeroConfigE2ETest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "meerkat.metrics.enabled=false",
                "meerkat.trace.http-slow-threshold=10s"
        })
class HttpClientsZeroConfigE2ETest {
    private static final Path STORAGE_DIRECTORY = createStorageDirectory();

    @LocalServerPort
    private int port;

    @Autowired
    private TraceStore traceStore;

    @Autowired
    private io.micrometer.core.instrument.MeterRegistry meterRegistry;

    @org.springframework.test.context.DynamicPropertySource
    static void traceStorage(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("meerkat.storage.directory", STORAGE_DIRECTORY::toString);
    }

    @AfterAll
    static void deleteStorage() throws IOException {
        FileSystemUtils.deleteRecursively(STORAGE_DIRECTORY);
    }

    @Test
    void restTemplateRestClientAndOkHttpBeansAreTracedAndPropagateTheTraceId() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/upstream")).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(500);
        String traceId = response.headers().firstValue("X-Trace-Id").orElseThrow();
        assertThat(response.body().lines().toList()).containsExactly(
                "restTemplate=" + traceId,
                "restClient=" + traceId,
                "okHttp=" + traceId);

        TraceRecord trace = awaitTrace(traceId);
        assertThat(trace).isNotNull();
        List<TraceNode> remote = flatten(trace.treeNodes()).stream()
                .filter(node -> "Remote".equals(node.kind()))
                .toList();
        assertThat(remote).as(flatten(trace.treeNodes()).toString()).hasSize(3);
        assertThat(remote).allSatisfy(node -> assertThat(node.name()).isEqualTo("GET localhost /downstream"));

        // Prometheus refuses meters that share a name but not tag keys, so every client must agree.
        List<java.util.Set<String>> tagKeys = meterRegistry.find("http.client.requests").timers().stream()
                .map(timer -> {
                    java.util.Set<String> keys = new java.util.TreeSet<>();
                    timer.getId().getTags().forEach(tag -> keys.add(tag.getKey()));
                    return (java.util.Set<String>) keys;
                })
                .distinct()
                .toList();
        assertThat(meterRegistry.find("http.client.requests").timers().stream().mapToLong(timer -> timer.count()).sum())
                .isEqualTo(3);
        assertThat(tagKeys).as(tagKeys.toString()).hasSize(1);
    }

    private TraceRecord awaitTrace(String traceId) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        TraceRecord trace;
        do {
            trace = traceStore.find(traceId, "HTTP");
            if (trace != null) return trace;
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        return null;
    }

    private static List<TraceNode> flatten(List<TraceNode> nodes) {
        List<TraceNode> all = new ArrayList<>();
        for (TraceNode node : nodes) {
            all.add(node);
            all.addAll(flatten(node.children()));
        }
        return all;
    }

    private static Path createStorageDirectory() {
        try {
            return Files.createTempDirectory("meerkat-http-clients-");
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    @SpringBootConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import(ClientsController.class)
    static class TestApplication {
        @Bean
        RestTemplate plainRestTemplate() {
            return new RestTemplate();
        }

        @Bean
        RestClient plainRestClient() {
            return RestClient.create();
        }

        @Bean
        OkHttpClient okHttpClient() {
            return new OkHttpClient();
        }
    }

    @RestController
    static class ClientsController {
        private final RestTemplate restTemplate;
        private final RestClient restClient;
        private final OkHttpClient okHttpClient;
        private final Environment environment;

        ClientsController(RestTemplate restTemplate, RestClient restClient, OkHttpClient okHttpClient,
                          Environment environment) {
            this.restTemplate = restTemplate;
            this.restClient = restClient;
            this.okHttpClient = okHttpClient;
            this.environment = environment;
        }

        @GetMapping(value = "/downstream", produces = "text/plain")
        String downstream(@RequestHeader(value = "X-Trace-Id", defaultValue = "") String traceId) {
            return traceId;
        }

        @GetMapping(value = "/upstream", produces = "text/plain")
        ResponseEntity<String> upstream() throws IOException {
            String url = "http://localhost:" + environment.getProperty("local.server.port") + "/downstream";
            Map<String, String> seen = new LinkedHashMap<>();
            seen.put("restTemplate", restTemplate.getForObject(url, String.class));
            seen.put("restClient", restClient.get().uri(url).retrieve().body(String.class));
            try (Response response = okHttpClient.newCall(new Request.Builder().url(url).build()).execute()) {
                seen.put("okHttp", response.body() == null ? "" : response.body().string());
            }
            StringBuilder body = new StringBuilder();
            seen.forEach((key, value) -> body.append(key).append('=').append(value).append('\n'));
            // An error response makes the trace a diagnostic trace that is kept in the store.
            return ResponseEntity.status(500).body(body.toString());
        }
    }
}
