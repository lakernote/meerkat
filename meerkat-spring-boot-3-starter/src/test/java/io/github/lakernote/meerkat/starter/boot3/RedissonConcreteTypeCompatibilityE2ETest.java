package io.github.lakernote.meerkat.starter.boot3;

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

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SpringBootTest(classes = RedissonConcreteTypeCompatibilityE2ETest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "meerkat.metrics.enabled=false",
                "meerkat.web.enabled=false"
        })
class RedissonConcreteTypeCompatibilityE2ETest {

    @Autowired
    private RedissonConnectionFactory connectionFactory;

    @Autowired
    private RedissonFixture fixture;

    @AfterEach
    void clearTrace() {
        TraceContext.clear();
    }

    @Test
    void preservesConcreteFactoryInjectionAndTracesPingWithoutARealRedisServer() throws Exception {
        assertThat(connectionFactory).isInstanceOf(RedissonConnectionFactory.class);
        assertThat(AopUtils.isCglibProxy(connectionFactory)).isTrue();
        Object ultimateTarget = AopTestUtils.getUltimateTargetObject(connectionFactory);
        assertThat(ultimateTarget).isSameAs(fixture.target());

        assertThat(connectionFactory.isFilterOkResponses()).isTrue();
        connectionFactory.setFilterOkResponses(false);
        assertThat(fixture.target().isFilterOkResponses()).isFalse();

        AtomicReference<TraceCompletion> completion = new AtomicReference<>();
        TraceContext.startRoot("redisson-compatibility-e2e", SpanKind.HTTP);
        try {
            RedisConnection connection = connectionFactory.getConnection();
            assertThat(connection).isNotSameAs(fixture.connection());
            assertThat(connection.ping()).isEqualTo("PONG");
        } finally {
            TraceContext.currentRootTraceHandle().requireTree();
            TraceContext.stopRoot(0, null, completion::set);
        }

        assertThat(completion.get()).isNotNull();
        assertThat(completion.get().tree()).contains("[Redis] Redis ping", "ping");
        assertThat(completion.get().nodes()).singleElement().satisfies(root ->
                assertThat(root.children()).singleElement().satisfies(redis -> {
                    assertThat(redis.kind()).isEqualTo("Redis");
                    assertThat(redis.name()).isEqualTo("Redis ping");
                    assertThat(redis.tag()).isEqualTo("ping");
                    assertThat(redis.error()).isFalse();
                }));
        verify(fixture.connection()).isPipelined();
        verify(fixture.connection()).ping();
        verifyNoInteractions(fixture.client());
    }

    @SpringBootConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class TestApplication {
        @Bean
        RedissonFixture redissonFixture() {
            RedissonClient client = mock(RedissonClient.class);
            RedisConnection connection = mock(RedisConnection.class);
            when(connection.isPipelined()).thenReturn(false);
            when(connection.ping()).thenReturn("PONG");

            RedissonConnectionFactory target = spy(new RedissonConnectionFactory(client));
            target.setFilterOkResponses(true);
            doReturn(connection).when(target).getConnection();
            return new RedissonFixture(target, client, connection);
        }

        @Bean
        RedissonConnectionFactory redissonConnectionFactory(RedissonFixture fixture) {
            return fixture.target();
        }
    }

    record RedissonFixture(RedissonConnectionFactory target, RedissonClient client,
                           RedisConnection connection) { }
}
