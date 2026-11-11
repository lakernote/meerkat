package io.github.lakernote.meerkat.starter.boot3;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lakernote.meerkat.web.ObservabilityAccessPolicy;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The toolbox runs each diagnostic on demand and gates the sensitive ones behind canOperate(). */
@SpringBootTest(classes = ToolsE2ETest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "meerkat.metrics.enabled=false",
                "meerkat.tools.heap-histogram-interval=0s",
                "e2e.tools.greeting=inline",
                "e2e.tools.message=hello ${e2e.tools.greeting}"
        })
class ToolsE2ETest {
    private static final Path STORAGE_DIRECTORY = createStorageDirectory();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String API = "/api/meerkat/tools";
    private static volatile boolean operate = true;
    private final HttpClient client = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @org.springframework.test.context.DynamicPropertySource
    static void properties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("meerkat.storage.directory", STORAGE_DIRECTORY::toString);
        registry.add("e2e.tools.greeting", () -> "dynamic");
    }

    @AfterAll
    static void deleteStorage() throws IOException {
        FileSystemUtils.deleteRecursively(STORAGE_DIRECTORY);
    }

    @Test
    void servesThePageAndReportsPermissions() throws Exception {
        HttpResponse<String> redirect = get("/meerkat/tools");
        assertThat(redirect.statusCode()).isEqualTo(308);
        assertThat(redirect.headers().firstValue("Location")).hasValue("tools/");
        HttpResponse<String> page = get("/meerkat/tools/");
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body()).contains("assets/tools.js?v=", "assets/tools.css?v=");
        assertThat(get("/meerkat/assets/tools.js").statusCode()).isEqualTo(200);
        assertThat(get("/meerkat/assets/tools.css").statusCode()).isEqualTo(200);

        JsonNode status = json(get(API + "/status"));
        assertThat(status.path("enabled").asBoolean()).isTrue();
        assertThat(status.path("canOperate").asBoolean()).isTrue();
        assertThat(status.path("loggingSystem").asBoolean()).isTrue();
        assertThat(status.path("maxProfileSeconds").asLong()).isEqualTo(60);
    }

    @Test
    void setsATemporaryLogLevelThatRevertsByItself() throws Exception {
        String logger = "io.github.lakernote.e2e.ToolsProbe";
        HttpResponse<String> set = post(API + "/loggers/overrides",
                "{\"logger\":\"" + logger + "\",\"level\":\"DEBUG\",\"durationSeconds\":1}");
        assertThat(set.statusCode()).as(set.body()).isEqualTo(200);
        assertThat(json(set).path("previousLevel").isNull()).isTrue();

        JsonNode loggers = json(get(API + "/loggers?q=ToolsProbe"));
        assertThat(loggers.path("loggers").path("loggers").get(0).path("configuredLevel").asText()).isEqualTo("DEBUG");
        assertThat(loggers.path("overrides")).hasSize(1);

        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        JsonNode reverted;
        do {
            Thread.sleep(100);
            reverted = json(get(API + "/loggers?q=ToolsProbe"));
        } while (!reverted.path("overrides").isEmpty() && System.nanoTime() < deadline);
        assertThat(reverted.path("overrides")).isEmpty();
        assertThat(reverted.path("loggers").path("loggers").get(0).path("configuredLevel").isNull()).isTrue();

        assertThat(post(API + "/loggers/overrides",
                "{\"logger\":\"" + logger + "\",\"level\":\"TRACE\",\"durationSeconds\":60}").statusCode()).isEqualTo(200);
        assertThat(post(API + "/loggers/overrides/revert?logger=" + logger, "").statusCode()).isEqualTo(204);
        assertThat(post(API + "/loggers/overrides/revert?logger=" + logger, "").statusCode()).isEqualTo(404);
        assertThat(post(API + "/loggers/overrides",
                "{\"logger\":\"" + logger + "\",\"level\":\"LOUD\",\"durationSeconds\":60}").statusCode()).isEqualTo(400);
    }

    @Test
    void profilesTheCpuIntoAFlameGraph() throws Exception {
        Thread spinner = new Thread(ToolsE2ETest::spin, "tools-e2e-spinner");
        spinner.setDaemon(true);
        spinner.start();
        HttpResponse<String> started = post(API + "/profiler", "{\"durationSeconds\":1,\"intervalMs\":10}");
        assertThat(started.statusCode()).as(started.body()).isEqualTo(200);
        String id = json(started).path("id").asText();
        assertThat(post(API + "/profiler", "{}").statusCode()).isEqualTo(429);

        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        JsonNode profile;
        do {
            Thread.sleep(200);
            profile = json(get(API + "/profiler/" + id));
        } while ("RUNNING".equals(profile.path("session").path("state").asText()) && System.nanoTime() < deadline);
        spinner.interrupt();

        assertThat(profile.path("session").path("state").asText()).isEqualTo("FINISHED");
        assertThat(profile.path("root").path("total").asLong()).isPositive();
        assertThat(profile.path("hotMethods")).isNotEmpty();
        assertThat(json(get(API + "/profiler")).path("id").asText()).isEqualTo(id);
        HttpResponse<String> collapsed = get(API + "/profiler/" + id + "/collapsed");
        assertThat(collapsed.statusCode()).isEqualTo(200);
        assertThat(collapsed.headers().firstValue("Content-Disposition")).hasValueSatisfying(value ->
                assertThat(value).contains("attachment", ".collapsed.txt"));
        assertThat(collapsed.body()).contains("ToolsE2ETest.spin");
        assertThat(get(API + "/profiler/missing").statusCode()).isEqualTo(404);
    }

    @Test
    void comparesHeapHistograms() throws Exception {
        HttpResponse<String> first = post(API + "/heap/histogram?limit=20", "");
        assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
        assertThat(json(first).path("rows")).hasSize(20);
        List<byte[]> retained = new ArrayList<>();
        for (int index = 0; index < 64; index++) retained.add(new byte[64 * 1024]);

        JsonNode second = json(post(API + "/heap/histogram?limit=20", ""));
        assertThat(retained).hasSize(64);
        assertThat(second.path("previousAt").isNull()).isFalse();
        assertThat(second.path("deltaBytes").isNumber()).isTrue();
        assertThat(second.path("rows").get(0).has("deltaBytes")).isTrue();
        assertThat(json(get(API + "/status")).path("histogramBaseline").asBoolean()).isTrue();
        assertThat(post(API + "/heap/histogram?limit=0", "").statusCode()).isEqualTo(400);
    }

    @Test
    void showsEveryPropertySourceAndTheWinner() throws Exception {
        JsonNode greeting = json(get(API + "/config?name=e2e.tools.greeting"));
        assertThat(greeting.path("value").asText()).isEqualTo("dynamic");
        JsonNode definitions = greeting.path("definitions");
        assertThat(definitions.size()).isGreaterThanOrEqualTo(2);
        assertThat(definitions.get(0).path("winner").asBoolean()).isTrue();
        assertThat(definitions.get(0).path("value").asText()).isEqualTo("dynamic");
        assertThat(definitions.get(1).path("winner").asBoolean()).isFalse();
        assertThat(definitions.get(1).path("value").asText()).isEqualTo("inline");

        assertThat(json(get(API + "/config?name=e2e.tools.message")).path("value").asText()).isEqualTo("hello dynamic");
        JsonNode search = json(get(API + "/config/search?q=e2e.tools."));
        assertThat(search.path("matches")).extracting(match -> match.path("name").asText())
                .contains("e2e.tools.greeting", "e2e.tools.message");
    }

    @Test
    void findsWhereClassesComeFrom() throws Exception {
        JsonNode lookup = json(get(API + "/classes?name=" + encode("org.springframework.boot.SpringApplication")));
        assertThat(lookup.path("loaded").asBoolean()).isTrue();
        assertThat(lookup.path("loadedFrom").asText()).contains("spring-boot");
        assertThat(lookup.path("copies")).isNotEmpty();
        assertThat(json(get(API + "/classes?name=com.example.Missing")).path("loaded").asBoolean()).isFalse();
        assertThat(get(API + "/classes?name=" + encode("not a class")).statusCode()).isEqualTo(400);

        HttpResponse<String> scan = post(API + "/classes/duplicates", "");
        assertThat(scan.statusCode()).as(scan.body()).isEqualTo(200);
        assertThat(json(scan).path("scannedSources").asInt()).isPositive();
    }

    @Test
    void deniesSensitiveToolsWithoutTheOperatePermission() throws Exception {
        operate = false;
        try {
            assertThat(json(get(API + "/status")).path("canOperate").asBoolean()).isFalse();
            assertThat(post(API + "/loggers/overrides",
                    "{\"logger\":\"x\",\"level\":\"DEBUG\",\"durationSeconds\":60}").statusCode()).isEqualTo(403);
            assertThat(post(API + "/loggers/overrides/revert?logger=x", "").statusCode()).isEqualTo(403);
            assertThat(get(API + "/config?name=e2e.tools.greeting").statusCode()).isEqualTo(403);
            assertThat(get(API + "/config/search?q=e2e").statusCode()).isEqualTo(403);
            assertThat(post(API + "/heap/dump", "").statusCode()).isEqualTo(403);
            assertThat(post(API + "/heap/histogram?limit=5", "").statusCode()).isEqualTo(403);
            assertThat(post(API + "/profiler", "{}").statusCode()).isEqualTo(403);
            assertThat(get(API + "/profiler").statusCode()).isEqualTo(403);
            assertThat(post(API + "/classes/duplicates", "").statusCode()).isEqualTo(403);
            assertThat(json(get(API + "/status")).path("profile").isObject()).isFalse();
            assertThat(get(API + "/loggers").statusCode()).isEqualTo(200);
            assertThat(get(API + "/classes?name=java.lang.String").statusCode()).isEqualTo(200);
        } finally {
            operate = true;
        }
    }

    @Test
    void rejectsStateChangingRequestsFromOtherSites() throws Exception {
        String revert = API + "/loggers/overrides/revert?logger=e2e.missing";
        assertThat(post(revert, "", "Sec-Fetch-Site", "cross-site").statusCode()).isEqualTo(403);
        assertThat(post(revert, "", "Sec-Fetch-Site", "same-site").statusCode()).isEqualTo(403);
        HttpResponse<String> foreign = post(revert, "", "Origin", "https://evil.example");
        assertThat(foreign.statusCode()).isEqualTo(403);
        assertThat(foreign.headers().firstValue("Content-Type")).hasValueSatisfying(type ->
                assertThat(type).contains("json"));
        assertThat(post(revert, "", "Sec-Fetch-Site", "same-origin").statusCode()).isEqualTo(404);
        assertThat(post(revert, "", "Origin", "http://localhost:" + port).statusCode()).isEqualTo(404);
        assertThat(post(revert, "").statusCode()).isEqualTo(404);
        assertThat(client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + API + "/status"))
                .header("Sec-Fetch-Site", "cross-site").build(), HttpResponse.BodyHandlers.ofString())
                .statusCode()).isEqualTo(200);
    }

    private static void spin() {
        long value = 0;
        while (!Thread.currentThread().isInterrupted()) value += System.nanoTime() % 7;
        if (value == 42) System.out.println(value);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static JsonNode json(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body, String... headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (headers.length > 0) request.headers(headers);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static Path createStorageDirectory() {
        try {
            return Files.createTempDirectory("meerkat-tools-");
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    @SpringBootConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class TestApplication {
        @Bean
        ObservabilityAccessPolicy toolsAccessPolicy() {
            return new ObservabilityAccessPolicy() {
                @Override
                public boolean canView() {
                    return true;
                }

                @Override
                public boolean canOperate() {
                    return operate;
                }
            };
        }
    }
}
