package io.github.lakernote.meerkat.starter.boot4;

import io.github.lakernote.meerkat.trace.SpanKind;
import io.github.lakernote.meerkat.trace.internal.TraceCompletion;
import io.github.lakernote.meerkat.trace.internal.TraceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.redisson.spring.data.connection.RedissonConnectionFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.test.util.AopTestUtils;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.spy;

@SpringBootTest(classes = RedissonConcreteTypeCompatibilityE2ETest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "meerkat.metrics.enabled=false",
                "meerkat.web.enabled=false"
        })
class RedissonConcreteTypeCompatibilityE2ETest {
    private static final Object NATIVE_CONNECTION = new Object();

    @Autowired
    private ConcreteRedissonConsumer consumer;

    @Autowired
    private RedissonConnectionFactory connectionFactory;

    @Autowired
    private RedisConnection fakeRedisConnection;

    @AfterEach
    void clearTrace() {
        TraceContext.clear();
    }

    @Test
    void preservesConcreteFactoryAndVendorStateWhileTracingCommands() {
        RedissonConnectionFactory injectedFactory = consumer.connectionFactory();
        assertThat(injectedFactory).isSameAs(connectionFactory);
        assertThat(injectedFactory).isInstanceOf(RedissonConnectionFactory.class);
        assertThat(AopUtils.isCglibProxy(injectedFactory)).isTrue();
        assertThat(injectedFactory.isFilterOkResponses()).isTrue();

        RedissonConnectionFactory target = AopTestUtils.getUltimateTargetObject(injectedFactory);
        assertThat(target).isNotSameAs(injectedFactory);
        assertThat(target).isInstanceOf(RedissonConnectionFactory.class);
        assertThat(mockingDetails(target).isSpy()).isTrue();
        assertThat(target.isFilterOkResponses()).isTrue();

        AtomicReference<TraceCompletion> completion = new AtomicReference<>();
        TraceContext.startRoot("redisson-concrete-type-e2e", SpanKind.HTTP);
        RedisConnection tracedConnection;
        try {
            tracedConnection = injectedFactory.getConnection();
            assertThat(tracedConnection).isNotSameAs(fakeRedisConnection);
            assertThat(tracedConnection.getNativeConnection()).isSameAs(NATIVE_CONNECTION);
            assertThat(tracedConnection.ping()).isEqualTo("PONG");
        } finally {
            TraceContext.currentRootTraceHandle().requireTree();
            TraceContext.stopRoot(0, null, completion::set);
        }

        assertThat(completion.get()).isNotNull();
        assertThat(completion.get().tree()).contains("[Redis] Redis ping tag=\"ping\"");
    }

    @SpringBootConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class TestApplication {
        @Bean
        RedisConnection fakeRedisConnection() {
            return (RedisConnection) Proxy.newProxyInstance(
                    RedisConnection.class.getClassLoader(), new Class<?>[]{RedisConnection.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "ping" -> "PONG";
                        case "getNativeConnection" -> NATIVE_CONNECTION;
                        case "isPipelined", "isQueueing", "isClosed" -> false;
                        case "close", "openPipeline", "multi", "discard", "watch", "unwatch", "open" -> null;
                        case "equals" -> proxy == args[0];
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "toString" -> "fake Redisson connection";
                        default -> null;
                    });
        }

        @Bean
        RedissonConnectionFactory redissonConnectionFactory(RedisConnection fakeRedisConnection) {
            RedissonConnectionFactory factory = spy(new RedissonConnectionFactory(mock(RedissonClient.class)));
            factory.setFilterOkResponses(true);
            doReturn(fakeRedisConnection).when(factory).getConnection();
            return factory;
        }

        @Bean
        ConcreteRedissonConsumer concreteRedissonConsumer(RedissonConnectionFactory connectionFactory) {
            return new ConcreteRedissonConsumer(connectionFactory);
        }
    }

    record ConcreteRedissonConsumer(RedissonConnectionFactory connectionFactory) { }
}
