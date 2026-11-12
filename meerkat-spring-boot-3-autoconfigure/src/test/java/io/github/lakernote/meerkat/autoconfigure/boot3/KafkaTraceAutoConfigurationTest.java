package io.github.lakernote.meerkat.autoconfigure.boot3;

import io.github.lakernote.meerkat.trace.internal.TraceIds;
import io.github.lakernote.meerkat.autoconfigure.KafkaTraceAutoConfiguration;

import io.github.lakernote.meerkat.config.MeerkatProperties;
import io.github.lakernote.meerkat.integration.kafka.internal.TraceIdProducerInterceptor;
import io.github.lakernote.meerkat.integration.observation.internal.ObservationTraceHandler;
import io.github.lakernote.meerkat.trace.internal.store.TraceStore;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.kafka.support.micrometer.KafkaRecordReceiverContext;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.kafka.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.listener.RecordInterceptor;

import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaTraceAutoConfigurationTest {
    @Test
    void exposesTraceInterceptorAsBootListenerInterceptorBean() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(KafkaTraceAutoConfiguration.class))
                .withBean(MeerkatProperties.class, MeerkatProperties::new)
                .run(context -> {
                    assertThat(context.getBean(RecordInterceptor.class))
                            .isInstanceOf(io.github.lakernote.meerkat.integration.kafka.TraceIdRecordInterceptor.class);
                });
    }

    @Test
    void appendsProducerInterceptorAndKeepsConfiguredInterceptors() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(KafkaProducerTraceAutoConfiguration.class))
                .withBean(MeerkatProperties.class, () -> {
                    MeerkatProperties properties = new MeerkatProperties();
                    properties.getTrace().setHeaderName("x-correlation-id");
                    return properties;
                })
                .run(context -> {
                    DefaultKafkaProducerFactory<String, String> factory = new DefaultKafkaProducerFactory<>(Map.of(
                            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092",
                            ProducerConfig.INTERCEPTOR_CLASSES_CONFIG, List.of("example.ExistingInterceptor")));
                    context.getBean(DefaultKafkaProducerFactoryCustomizer.class).customize(factory);
                    Object configured = factory.getConfigurationProperties()
                            .get(ProducerConfig.INTERCEPTOR_CLASSES_CONFIG);
                    List<?> interceptors = (List<?>) configured;
                    List<String> names = interceptors.stream().map(String::valueOf).toList();
                    assertThat(names).contains("example.ExistingInterceptor",
                            TraceIdProducerInterceptor.class.getName());
                    assertThat(factory.getConfigurationProperties().get(
                            TraceIdProducerInterceptor.TRACE_ID_HEADER_CONFIG)).isEqualTo("x-correlation-id");
                    factory.destroy();
                });
    }

    @Test
    void setsConfiguredHeaderWhenTheInterceptorIsAlreadyListed() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(KafkaProducerTraceAutoConfiguration.class))
                .withBean(MeerkatProperties.class, () -> {
                    MeerkatProperties properties = new MeerkatProperties();
                    properties.getTrace().setHeaderName("x-correlation-id");
                    return properties;
                })
                .run(context -> {
                    DefaultKafkaProducerFactory<String, String> factory = new DefaultKafkaProducerFactory<>(Map.of(
                            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092",
                            ProducerConfig.INTERCEPTOR_CLASSES_CONFIG, TraceIdProducerInterceptor.class.getName()));
                    context.getBean(DefaultKafkaProducerFactoryCustomizer.class).customize(factory);
                    assertThat(factory.getConfigurationProperties().get(ProducerConfig.INTERCEPTOR_CLASSES_CONFIG))
                            .isEqualTo(TraceIdProducerInterceptor.class.getName());
                    assertThat(factory.getConfigurationProperties().get(
                            TraceIdProducerInterceptor.TRACE_ID_HEADER_CONFIG)).isEqualTo("x-correlation-id");
                    factory.destroy();
                });
    }

    @Test
    void producerUsesConfiguredHeaderAndDoesNotInventTraceIdsOutsideAnEntry() {
        TraceIdProducerInterceptor<String, String> interceptor = new TraceIdProducerInterceptor<>();
        interceptor.configure(Map.of(TraceIdProducerInterceptor.TRACE_ID_HEADER_CONFIG, "x-correlation-id"));
        var record = new org.apache.kafka.clients.producer.ProducerRecord<>("events", "key", "value");
        assertThat(interceptor.onSend(record).headers().lastHeader("x-correlation-id")).isNull();

        TraceIds.putTraceId("0123456789abcdef0123456789abcdef");
        try {
            assertThat(interceptor.onSend(record).headers().lastHeader("x-correlation-id").value())
                    .containsExactly("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
            assertThat(record.headers().lastHeader("X-Trace-Id")).isNull();
        } finally {
            TraceIds.clear();
        }
    }

    @Test
    void slowConsumerObservationUsesTraceIdFromKafkaRecordHeader() throws Exception {
        MeerkatProperties properties = new MeerkatProperties();
        properties.getTrace().setKafkaConsumerSlowThreshold(java.time.Duration.ofMillis(1));
        TraceStore history = TraceStore.inMemory(1024 * 1024);
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(KafkaTraceAutoConfiguration.class))
                .withBean(MeerkatProperties.class, () -> properties)
                .run(context -> {
                    ObservationTraceHandler handler = new ObservationTraceHandler(history,
                            properties, context.getBeansOfType(
                            io.github.lakernote.meerkat.integration.observation.ObservationTraceIdResolver.class)
                                    .values().stream().toList());
                    ConsumerRecord<String, String> record = new ConsumerRecord<>("events", 0, 1L, "key", "value");
                    record.headers().add(new RecordHeader("X-Trace-Id",
                            "trace-from-message".getBytes(StandardCharsets.UTF_8)));
                    KafkaRecordReceiverContext receiver = new KafkaRecordReceiverContext(record, "demo-listener", () -> "cluster");
                    receiver.setName("spring.kafka.listener");

                    handler.onStart(receiver);
                    Thread.sleep(5);
                    handler.onStop(receiver);

                    assertThat(history.recent(10)).singleElement()
                            .satisfies(trace -> assertThat(trace.traceId()).isEqualTo("trace-from-message"));
                });
    }

}
