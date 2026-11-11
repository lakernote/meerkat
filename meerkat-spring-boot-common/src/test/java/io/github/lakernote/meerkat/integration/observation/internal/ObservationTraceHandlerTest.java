package io.github.lakernote.meerkat.integration.observation.internal;

import io.github.lakernote.meerkat.trace.internal.TraceIds;
import io.github.lakernote.meerkat.config.MeerkatProperties;
import io.github.lakernote.meerkat.trace.SpanKind;
import io.github.lakernote.meerkat.trace.internal.TraceContext;
import io.github.lakernote.meerkat.trace.internal.TraceCompletion;
import io.github.lakernote.meerkat.trace.internal.store.TraceRecord;
import io.github.lakernote.meerkat.trace.internal.store.TraceStore;
import io.github.lakernote.meerkat.trace.internal.TraceNodeFixtures;
import io.github.lakernote.meerkat.trace.internal.ActiveTraces;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ObservationTraceHandlerTest {
    @AfterEach
    void clearContexts() {
        TraceContext.clear();
        TraceIds.clear();
    }

    @Test
    void writesTraceIdThroughSenderContextAndReadsItFromReceiverContext() {
        MeerkatProperties properties = new MeerkatProperties();
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new ObservationTraceHandler(
                TraceStore.inMemory(1024 * 1024), properties, java.util.List.of()));
        TraceIds.putTraceId("trace-sender-1");
        TraceContext.startRoot("GET /orders", SpanKind.HTTP);

        java.util.Map<String, String> outgoing = new java.util.HashMap<>();
        io.micrometer.observation.transport.SenderContext<java.util.Map<String, String>> sender =
                new io.micrometer.observation.transport.SenderContext<>(java.util.Map::put);
        sender.setCarrier(outgoing);
        Observation.createNotStarted("http.client.requests", () -> sender, registry).start().stop();
        assertThat(outgoing).containsEntry("X-Trace-Id", "trace-sender-1");
        TraceContext.clear();
        TraceIds.clear();

        java.util.Map<String, String> incoming = java.util.Map.of("X-Trace-Id", "trace-receiver-1");
        io.micrometer.observation.transport.ReceiverContext<java.util.Map<String, String>> receiver =
                new io.micrometer.observation.transport.ReceiverContext<>(java.util.Map::get);
        receiver.setCarrier(incoming);
        AtomicReference<String> seen = new AtomicReference<>();
        Observation observation = Observation.createNotStarted("spring.kafka.listener", () -> receiver, registry).start();
        seen.set(io.github.lakernote.meerkat.trace.TraceIdContext.currentTraceId());
        observation.stop();
        assertThat(seen.get()).isEqualTo("trace-receiver-1");
    }

    @Test
    void convertsFrameworkObservationToChildSpanInTheTraceTree() {
        MeerkatProperties properties = new MeerkatProperties();
        TraceStore history = TraceStore.inMemory(1024 * 1024);
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new ObservationTraceHandler(history, properties, java.util.List.of()));
        TraceIds.putTraceId("trace-redis-1");
        TraceContext.startRoot("GET /cache", SpanKind.HTTP);

        Observation observation = Observation.createNotStarted("spring.data.redis", registry)
                .contextualName("GET")
                .lowCardinalityKeyValue("db.operation", "GET");
        observation.start();
        observation.stop();

        TraceContext.currentRootTraceHandle().requireTree();
        TraceCompletion[] completion = new TraceCompletion[1];
        TraceContext.stopRoot(Long.MAX_VALUE, null, result -> completion[0] = result);
        assertThat(completion[0].tree()).contains("[Redis] GET");
        assertThat(completion[0].slow()).isFalse();
        history.add(TraceRecord.builder().traceId("trace-redis-1").sourceType("HTTP")
                .name("GET /cache").tag("GET").status(200).durationMs(20).slow(true).treeNodes(completion[0].nodes()).build());
        assertThat(TraceNodeFixtures.names(history.find("trace-redis-1", "HTTP").treeNodes())).contains("GET");
    }

    @Test
    void redactsJdbcObservationSqlAndDriverFailureEvidence() {
        MeerkatProperties properties = new MeerkatProperties();
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new ObservationTraceHandler(
                TraceStore.inMemory(1024 * 1024), properties, java.util.List.of()));
        String sqlSecret = "observation-secret-c91e--tail";
        String causeSecret = "bind-secret-4d2e";
        SQLException failure = new SQLException(
                "Syntax error in SQL SELECT '" + sqlSecret + "'",
                new IllegalArgumentException(causeSecret));
        AtomicReference<TraceCompletion> completion = new AtomicReference<>();

        TraceContext.startRoot("jdbc-observation", SpanKind.HTTP);
        Observation observation = Observation.createNotStarted("spring.jdbc", registry)
                .contextualName("SELECT")
                .lowCardinalityKeyValue("db.operation", "SELECT")
                .lowCardinalityKeyValue("jdbc.datasource.name", "orders")
                .highCardinalityKeyValue("jdbc.query[0]",
                        "SELECT '" + sqlSecret + "' FROM missing_table");
        observation.start();
        observation.error(failure);
        observation.stop();
        TraceContext.currentRootTraceHandle().requireTree();
        TraceContext.stopRoot(0, null, completion::set);

        TraceCompletion result = completion.get();
        assertThat(result.error()).isTrue();
        assertThat(result.reasons()).containsExactly("DATABASE_ERROR");
        assertThat(result.errorType()).isEqualTo(SQLException.class.getName());
        assertThat(result.errorMessage()).isEqualTo("JDBC query failed");
        assertThat(result.stackTrace())
                .contains(SQLException.class.getName(), IllegalArgumentException.class.getName())
                .doesNotContain(sqlSecret, causeSecret, "Syntax error in SQL", "secret");
        assertThat(result.tree())
                .contains("[Database] SELECT", "orders · SELECT ? FROM missing_table")
                .doesNotContain(sqlSecret, causeSecret, "secret");
        assertThat(result.nodes()).flatExtracting(node -> node.children())
                .anySatisfy(node -> {
                    assertThat(node.kind()).isEqualTo("Database");
                    assertThat(node.error()).isTrue();
                    assertThat(node.errorType()).isEqualTo(SQLException.class.getName());
                });
    }

    @Test
    void redactsJdbcFailureDiscoveredOnlyAtObservationStop() {
        MeerkatProperties properties = new MeerkatProperties();
        ObservationTraceHandler handler = new ObservationTraceHandler(
                TraceStore.inMemory(1024 * 1024), properties, java.util.List.of());
        Observation.Context context = new Observation.Context();
        context.setName("spring.jdbc");
        context.setContextualName("SELECT");
        context.addHighCardinalityKeyValue(io.micrometer.common.KeyValue.of(
                "jdbc.query[0]", "SELECT 'stop-secret--tail'"));
        SQLException failure = new SQLException("driver-stop-secret");
        AtomicReference<TraceCompletion> completion = new AtomicReference<>();

        TraceContext.startRoot("jdbc-observation-stop", SpanKind.HTTP);
        handler.onStart(context);
        context.setError(failure);
        handler.onStop(context);
        TraceContext.currentRootTraceHandle().requireTree();
        TraceContext.stopRoot(0, null, completion::set);

        assertThat(completion.get().error()).isTrue();
        assertThat(completion.get().reasons()).containsExactly("DATABASE_ERROR");
        assertThat(completion.get().errorType()).isEqualTo(SQLException.class.getName());
        assertThat(completion.get().errorMessage()).isEqualTo("JDBC query failed");
        assertThat(completion.get().stackTrace()).doesNotContain("driver-stop-secret", "secret");
        assertThat(completion.get().tree()).contains("SELECT ?").doesNotContain("stop-secret", "secret");
    }

    @Test
    void recordsStandaloneDependencyWithoutInventingTraceRoot() {
        MeerkatProperties properties = new MeerkatProperties();
        TraceStore history = TraceStore.inMemory(1024 * 1024);
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new ObservationTraceHandler(history, properties, java.util.List.of()));
        Observation observation = Observation.createNotStarted("spring.data.redis", registry)
                .contextualName("GET")
                .lowCardinalityKeyValue("db.operation", "GET");

        observation.start().stop();

        assertThat(history.recent(10)).isEmpty();
        history.add(TraceRecord.builder().traceId("orphan-trace").sourceType("HTTP")
                .name("GET /cache").tag("GET").status(200).durationMs(10).slow(true)
                .treeNodes(TraceNodeFixtures.named("tree")).build());

        assertThat(TraceNodeFixtures.names(history.find("orphan-trace", "HTTP").treeNodes())).containsExactly("tree");
    }

    @Test
    void recordsHttpServerErrorStatusAsFailedTraceWithoutThrowable() {
        MeerkatProperties properties = new MeerkatProperties();
        TraceStore history = TraceStore.inMemory(1024 * 1024);
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new ObservationTraceHandler(history, properties, java.util.List.of()));

        Observation.createNotStarted("http.server.requests", registry)
                .lowCardinalityKeyValue("http.request.method", "GET")
                .lowCardinalityKeyValue("http.route", "/orders/{id}")
                .lowCardinalityKeyValue("http.response.status_code", "503")
                .start().stop();

        assertThat(history.recent(10)).singleElement().satisfies(trace -> {
            assertThat(trace.status()).isEqualTo(503);
            assertThat(trace.error()).isTrue();
            assertThat(trace.treeNodes()).isNotEmpty();
        });
        assertThat(history.recent(10)).singleElement().satisfies(trace -> {
            assertThat(trace.errorType()).isEqualTo("HTTP_503");
            assertThat(trace.errorMessage()).isEqualTo("HTTP status 503");
        });
    }

    @Test
    void doesNotRetainFastSuccessfulHttpObservations() {
        MeerkatProperties properties = new MeerkatProperties();
        TraceStore history = TraceStore.inMemory(1024 * 1024);
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new ObservationTraceHandler(history, properties, java.util.List.of()));

        Observation.createNotStarted("http.server.requests", registry)
                .lowCardinalityKeyValue("http.request.method", "GET")
                .lowCardinalityKeyValue("http.route", "/orders/{id}")
                .start().stop();

        assertThat(history.recent(10)).isEmpty();
    }

    @Test
    void excludesBundledDashboardTrafficFromMeerkatRecords() {
        MeerkatProperties properties = new MeerkatProperties();
        TraceStore history = TraceStore.inMemory(1024 * 1024);
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new ObservationTraceHandler(history, properties, java.util.List.of()));

        Observation.createNotStarted("http.server.requests", registry)
                .lowCardinalityKeyValue("http.request.method", "GET")
                .lowCardinalityKeyValue("http.route", "/api/meerkat/traces")
                .start().stop();

        assertThat(history.recent(10)).isEmpty();
    }

    @Test
    void excludesDashboardTrafficWhenSpringAddsRouteOnlyAtObservationStop() {
        MeerkatProperties properties = new MeerkatProperties();
        TraceStore history = TraceStore.inMemory(1024 * 1024);
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new ObservationTraceHandler(history, properties, java.util.List.of()));

        Observation observation = Observation.createNotStarted("http.server.requests", registry)
                .lowCardinalityKeyValue("http.request.method", "GET").start();
        observation.lowCardinalityKeyValue("http.route", "/api/meerkat/traces").stop();

        assertThat(history.recent(10)).isEmpty();
        assertThat(TraceContext.active()).isFalse();
    }

    @Test
    void readsNumericHttpStatusAndIgnoresNonNumericValues() {
        TraceStore history = TraceStore.inMemory(1024 * 1024);
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new ObservationTraceHandler(
                history, new MeerkatProperties(), java.util.List.of()));

        for (String status : new String[] {"UNKNOWN", "", "5000", "-500", "200"}) {
            Observation.createNotStarted("http.server.requests", registry)
                    .lowCardinalityKeyValue("http.route", "/orders/" + status)
                    .lowCardinalityKeyValue("status", status).start().stop();
        }
        Observation.createNotStarted("http.server.requests", registry)
                .lowCardinalityKeyValue("http.route", "/failed")
                .lowCardinalityKeyValue("status", "503").start().stop();

        assertThat(history.recent(10)).singleElement().satisfies(trace -> {
            assertThat(trace.status()).isEqualTo(503);
            assertThat(trace.error()).isTrue();
            assertThat(trace.errorType()).isEqualTo("HTTP_503");
        });
    }

    @Test
    void recordsHttpObservationWhenContextualNameIsNotAvailableAtStart() throws Exception {
        MeerkatProperties properties = new MeerkatProperties();
        properties.getTrace().setHttpSlowThreshold(java.time.Duration.ofMillis(1));
        TraceStore history = TraceStore.inMemory(1024 * 1024);
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new ObservationTraceHandler(history, properties, java.util.List.of()));

        Observation observation = Observation.createNotStarted("http.server.requests", registry)
                .lowCardinalityKeyValue("http.request.method", "GET")
                .lowCardinalityKeyValue("http.route", "/orders")
                .start();
        Thread.sleep(5);
        observation.stop();

        assertThat(history.recent(10)).singleElement().satisfies(trace -> {
            assertThat(trace.name()).isEqualTo("/orders · GET");
            assertThat(TraceNodeFixtures.names(trace.treeNodes())).contains("/orders · GET");
        });
    }

    @Test
    void asyncSlowStopFinishesItsTraceWithoutEndingAnotherThreadsTrace() throws Exception {
        MeerkatProperties properties = new MeerkatProperties();
        properties.getTrace().setHttpSlowThreshold(java.time.Duration.ofMillis(1));
        TraceStore history = TraceStore.inMemory(1024 * 1024);
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new ObservationTraceHandler(history, properties, java.util.List.of()));
        Observation observation = Observation.createNotStarted("http.server.requests", registry)
                .lowCardinalityKeyValue("http.request.method", "GET")
                .lowCardinalityKeyValue("http.route", "/async")
                .start();
        Thread.sleep(5);

        boolean unrelatedTraceWasPreserved = CompletableFuture.supplyAsync(() -> {
            TraceContext.startRoot("GET /unrelated", SpanKind.HTTP);
            observation.stop();
            boolean active = TraceContext.active();
            TraceContext.clear();
            return active;
        }).join();

        assertThat(unrelatedTraceWasPreserved).isTrue();
        assertThat(TraceContext.active()).isFalse();
        assertThat(history.recent(10)).singleElement().satisfies(trace -> {
            assertThat(trace.name()).isEqualTo("/async · GET");
            assertThat(trace.sourceType()).isEqualTo("HTTP");
        });
    }

    @Test
    void listsARunningServletRequestOnTheLivePageUntilItStops() {
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new ObservationTraceHandler(TraceStore.inMemory(1024 * 1024),
                new MeerkatProperties(), java.util.List.of()));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/shop/orders/42");
        request.setContextPath("/shop");
        Observation observation = Observation.createNotStarted("http.server.requests",
                () -> new ServerRequestObservationContext(request, new MockHttpServletResponse()), registry);
        observation.start();
        try (Observation.Scope ignored = observation.openScope()) {
            ActiveTraces.ActiveTrace live = ActiveTraces.snapshot().stream()
                    .filter(trace -> trace.tag().equals("/orders/42")).findFirst().orElseThrow();
            assertThat(live.name()).isEqualTo("/orders/42 · POST");
            assertThat(live.source()).isEqualTo("HTTP");
            assertThat(live.traceId()).isEqualTo(io.github.lakernote.meerkat.trace.TraceIdContext.currentTraceId());
            assertThat(live.resourcesAtStart()).isNotNull();
        }
        observation.stop();
        assertThat(ActiveTraces.snapshot()).noneMatch(trace -> trace.tag().equals("/orders/42"));
    }

    @Test
    void leavesDashboardRequestsOffTheLivePage() {
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new ObservationTraceHandler(TraceStore.inMemory(1024 * 1024),
                new MeerkatProperties(), java.util.List.of()));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/meerkat/live/requests");
        Observation observation = Observation.createNotStarted("http.server.requests",
                () -> new ServerRequestObservationContext(request, new MockHttpServletResponse()), registry);
        observation.start();
        try {
            assertThat(ActiveTraces.snapshot())
                    .noneMatch(trace -> trace.tag().startsWith("/api/meerkat"));
        } finally {
            observation.stop();
        }
    }

    @Test
    void listsNonServletEntriesUnderTheirObservationName() {
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new ObservationTraceHandler(TraceStore.inMemory(1024 * 1024),
                new MeerkatProperties(), java.util.List.of()));
        Observation observation = Observation.createNotStarted("tasks.scheduled.execution", registry)
                .contextualName("ReportJob.run");
        observation.start();
        try (Observation.Scope ignored = observation.openScope()) {
            assertThat(ActiveTraces.snapshot()).anyMatch(trace -> trace.source().equals("JOB"));
        } finally {
            observation.stop();
        }
        assertThat(ActiveTraces.snapshot()).noneMatch(trace -> trace.source().equals("JOB"));
    }
}
