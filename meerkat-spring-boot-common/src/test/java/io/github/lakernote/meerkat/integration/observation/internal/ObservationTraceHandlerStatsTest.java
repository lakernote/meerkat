package io.github.lakernote.meerkat.integration.observation.internal;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.lakernote.meerkat.config.MeerkatProperties;
import io.github.lakernote.meerkat.metrics.internal.OperationStats;
import io.github.lakernote.meerkat.metrics.internal.OperationStats.Category;
import io.github.lakernote.meerkat.metrics.internal.OperationStats.Row;
import io.github.lakernote.meerkat.trace.internal.TraceContext;
import io.github.lakernote.meerkat.trace.internal.TraceIds;
import io.github.lakernote.meerkat.trace.internal.store.TraceStore;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ObservationTraceHandlerStatsTest {
    private final OperationStats stats = new OperationStats(100);
    private final ObservationRegistry registry = ObservationRegistry.create();

    ObservationTraceHandlerStatsTest() {
        registry.observationConfig().observationHandler(new ObservationTraceHandler(TraceStore.inMemory(1024 * 1024),
                new MeerkatProperties(), List.of(), stats));
    }

    @AfterEach
    void clearContexts() {
        TraceContext.clear();
        TraceIds.clear();
    }

    @Test
    void ranksHttpEndpointsByRouteAndMethodCountingServerErrors() {
        http("/orders/{id}", "GET", "200");
        http("/orders/{id}", "GET", "503");
        http("/orders/{id}", "GET", "404");

        assertThat(rows(Category.HTTP)).singleElement().satisfies(row -> {
            assertThat(row.name()).isEqualTo("/orders/{id} · GET");
            assertThat(row.count()).isEqualTo(3);
            assertThat(row.errors()).isEqualTo(1);
        });
    }

    @Test
    void ranksDependencyCallsEvenOutsideATrace() {
        Observation.createNotStarted("http.client.requests", registry)
                .lowCardinalityKeyValue("method", "POST")
                .lowCardinalityKeyValue("client.name", "pay.example.com")
                .lowCardinalityKeyValue("uri", "/charges/{id}")
                .lowCardinalityKeyValue("status", "500")
                .start().stop();
        Observation.createNotStarted("http.client.requests", registry)
                .lowCardinalityKeyValue("method", "GET")
                .lowCardinalityKeyValue("client.name", "cdn.example.com")
                .lowCardinalityKeyValue("uri", "none")
                .start().stop();
        Observation.createNotStarted("spring.data.redis", registry)
                .lowCardinalityKeyValue("db.operation", "get").start().stop();
        Observation observation = Observation.createNotStarted("spring.data.redis", registry)
                .lowCardinalityKeyValue("db.operation", "GET").start();
        observation.error(new IllegalStateException("down"));
        observation.stop();

        assertThat(rows(Category.REMOTE)).extracting(Row::name, Row::errors)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("POST pay.example.com /charges/{id}", 1L),
                        org.assertj.core.groups.Tuple.tuple("GET cdn.example.com", 0L));
        assertThat(rows(Category.REDIS)).singleElement().satisfies(row -> {
            assertThat(row.name()).isEqualTo("GET");
            assertThat(row.count()).isEqualTo(2);
            assertThat(row.errors()).isEqualTo(1);
        });
        assertThat(TraceContext.active()).isFalse();
    }

    @Test
    void remoteStatisticsDropQueryStringAndFragment() {
        for (String token : new String[] {"a", "b"}) {
            Observation.createNotStarted("http.client.requests", registry)
                    .lowCardinalityKeyValue("method", "GET")
                    .lowCardinalityKeyValue("client.name", "api.example.com")
                    .lowCardinalityKeyValue("uri", "/users?token=" + token + "#top")
                    .start().stop();
        }

        assertThat(rows(Category.REMOTE)).singleElement().satisfies(row -> {
            assertThat(row.name()).isEqualTo("GET api.example.com /users");
            assertThat(row.count()).isEqualTo(2);
        });
        assertThat(ObservationAttributes.withoutQuery("/a#x?y")).isEqualTo("/a");
    }

    @Test
    void kafkaStatisticsNeverUsePerMessageValues() {
        for (int offset = 0; offset < 3; offset++) {
            Observation.createNotStarted("spring.kafka.listener", registry)
                    .lowCardinalityKeyValue("messaging.source.name", "orders")
                    .lowCardinalityKeyValue("spring.kafka.listener.id", "order-listener")
                    .highCardinalityKeyValue("messaging.kafka.message.offset", String.valueOf(offset))
                    .start().stop();
        }
        Observation.createNotStarted("spring.kafka.template", registry)
                .lowCardinalityKeyValue("messaging.destination.name", "audit")
                .start().stop();

        assertThat(rows(Category.KAFKA_CONSUMER)).extracting(Row::name, Row::count)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("orders · order-listener", 3L));
        assertThat(rows(Category.KAFKA_PRODUCER)).extracting(Row::name).containsExactly("audit");
    }

    @Test
    void skipsJdbcObservationsTransactionsAndDashboardTraffic() {
        Observation.createNotStarted("spring.jdbc", registry)
                .lowCardinalityKeyValue("db.operation", "SELECT").start().stop();
        Observation.createNotStarted("spring.transaction", registry).start().stop();
        http("/meerkat/stats/", "GET", "200");
        http("/api/meerkat/stats", "GET", "200");

        assertThat(stats.snapshot(OperationStats.Window.TODAY).categories())
                .allSatisfy(category -> assertThat(category.rows()).isEmpty());
    }

    @Test
    void entriesCarryEntryThreadCpuAndAllocationButDependenciesDoNot() {
        Observation observation = Observation.createNotStarted("http.server.requests", registry)
                .lowCardinalityKeyValue("http.route", "/report")
                .lowCardinalityKeyValue("uri", "/report")
                .lowCardinalityKeyValue("method", "GET")
                .lowCardinalityKeyValue("status", "200")
                .start();
        try (Observation.Scope ignored = observation.openScope()) {
            byte[][] garbage = new byte[64][];
            for (int i = 0; i < garbage.length; i++) garbage[i] = new byte[16 * 1024];
            assertThat(garbage[63]).hasSize(16 * 1024);
            Observation.createNotStarted("http.client.requests", registry)
                    .lowCardinalityKeyValue("method", "GET")
                    .lowCardinalityKeyValue("client.name", "pay.example.com")
                    .lowCardinalityKeyValue("uri", "/charges")
                    .start().stop();
        }
        observation.stop();

        assertThat(rows(Category.HTTP)).singleElement().satisfies(row -> {
            assertThat(row.meanCpuMs()).isNotNull().isGreaterThanOrEqualTo(0.0);
            assertThat(row.meanAllocatedBytes()).isNotNull().isGreaterThanOrEqualTo(1024L * 1024);
        });
        assertThat(rows(Category.REMOTE)).singleElement().satisfies(row -> {
            assertThat(row.meanCpuMs()).isNull();
            assertThat(row.meanAllocatedBytes()).isNull();
        });
    }

    @Test
    void slowEntryLogIsAShortHeaderPlusTree() throws InterruptedException {
        MeerkatProperties properties = new MeerkatProperties();
        properties.getTrace().setHttpSlowThreshold(Duration.ofMillis(1));
        ObservationRegistry slowRegistry = ObservationRegistry.create();
        slowRegistry.observationConfig().observationHandler(new ObservationTraceHandler(TraceStore.inMemory(1024 * 1024),
                properties, List.of(), stats));
        Logger logger = (Logger) LoggerFactory.getLogger(TraceContext.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        boolean additive = logger.isAdditive();
        logger.setAdditive(false);
        logger.addAppender(appender);
        appender.start();
        try {
            Observation observation = Observation.createNotStarted("http.server.requests", slowRegistry)
                    .lowCardinalityKeyValue("http.route", "/slow")
                    .lowCardinalityKeyValue("method", "GET")
                    .lowCardinalityKeyValue("status", "200")
                    .start();
            Thread.sleep(5);
            observation.stop();

            assertThat(appender.list).singleElement().satisfies(event -> assertThat(event.getFormattedMessage())
                    .startsWith("Slow trace ").contains("/slow · GET").doesNotContain("cpu=", "allocated="));
        } finally {
            logger.detachAppender(appender);
            logger.setAdditive(additive);
            appender.stop();
        }
    }

    private void http(String route, String method, String status) {
        Observation.createNotStarted("http.server.requests", registry)
                .lowCardinalityKeyValue("http.route", route)
                .lowCardinalityKeyValue("uri", route)
                .lowCardinalityKeyValue("method", method)
                .lowCardinalityKeyValue("status", status)
                .start().stop();
    }

    private List<Row> rows(Category category) {
        return stats.snapshot(OperationStats.Window.TODAY).categories().stream()
                .filter(snapshot -> snapshot.category() == category).findFirst().orElseThrow().rows();
    }
}
