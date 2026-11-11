package io.github.lakernote.meerkat.starter.boot3;

import io.github.lakernote.meerkat.trace.SpanKind;
import io.github.lakernote.meerkat.trace.internal.TraceCompletion;
import io.github.lakernote.meerkat.trace.internal.TraceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.command.CommandAsyncExecutor;
import org.redisson.spring.data.connection.RedissonConnection;
import org.redisson.spring.data.connection.RedissonConnectionFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisStringCommands;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

@SpringBootTest(classes = RedissonCommandProviderTracingE2ETest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "meerkat.metrics.enabled=false",
                "meerkat.web.enabled=false"
        })
class RedissonCommandProviderTracingE2ETest {
    private static final byte[] KEY = "customer:42".getBytes(StandardCharsets.UTF_8);
    private static final byte[] VALUE = "Ada".getBytes(StandardCharsets.UTF_8);

    @Autowired
    private RedissonConnectionFactory connectionFactory;

    @Autowired
    private RedissonCommandFixture fixture;

    @AfterEach
    void clearTrace() {
        TraceContext.clear();
    }

    @Test
    void tracesCommandsObtainedFromStringCommandsProvider() {
        assertThat(connectionFactory).isInstanceOf(RedissonConnectionFactory.class);
        assertThat(AopUtils.isCglibProxy(connectionFactory)).isTrue();

        AtomicReference<TraceCompletion> completion = new AtomicReference<>();
        TraceContext.startRoot("redisson-command-provider-success", SpanKind.HTTP);
        try {
            RedissonConnection connection = fixture.connection();
            org.springframework.data.redis.connection.RedisConnection tracedConnection = connectionFactory.getConnection();
            RedisStringCommands commands = tracedConnection.stringCommands();
            assertThat(commands).isSameAs(tracedConnection).isNotSameAs(connection);
            assertThat(commands.get(KEY)).containsExactly(VALUE);
        } finally {
            TraceContext.currentRootTraceHandle().requireTree();
            TraceContext.stopRoot(0, null, completion::set);
        }

        assertThat(completion.get()).isNotNull();
        assertThat(completion.get().error()).isFalse();
        assertThat(completion.get().nodes()).singleElement().satisfies(root ->
                assertThat(root.children()).singleElement().satisfies(redis -> {
                    assertThat(redis.kind()).isEqualTo("Redis");
                    assertThat(redis.name()).isEqualTo("Redis get");
                    assertThat(redis.tag()).isEqualTo("get");
                    assertThat(redis.error()).isFalse();
                }));
        assertThat(completion.get().tree()).doesNotContain("Redis stringCommands");
    }

    @Test
    void preservesProviderCommandFailureAndRecordsItsTrace() {
        AtomicReference<TraceCompletion> completion = new AtomicReference<>();
        TraceContext.startRoot("redisson-command-provider-failure", SpanKind.HTTP);
        try {
            RedisStringCommands commands = connectionFactory.getConnection().stringCommands();
            assertThatThrownBy(() -> commands.set(KEY, VALUE)).isSameAs(fixture.failure());
        } finally {
            TraceContext.currentRootTraceHandle().requireTree();
            TraceContext.stopRoot(0, null, completion::set);
        }

        assertThat(completion.get()).isNotNull();
        assertThat(completion.get().error()).isTrue();
        assertThat(completion.get().errorType()).isEqualTo(IllegalStateException.class.getName());
        assertThat(completion.get().errorMessage()).isEqualTo("Redis write unavailable");
        assertThat(completion.get().nodes()).singleElement().satisfies(root ->
                assertThat(root.children()).singleElement().satisfies(redis -> {
                    assertThat(redis.name()).isEqualTo("Redis set");
                    assertThat(redis.tag()).isEqualTo("set");
                }));
        assertThat(completion.get().tree()).doesNotContain("Redis stringCommands");
    }

    @SpringBootConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class TestApplication {
        @Bean
        RedissonCommandFixture redissonCommandFixture() {
            IllegalStateException failure = new IllegalStateException("Redis write unavailable");
            Redisson redisson = mock(Redisson.class);
            when(redisson.getCommandExecutor()).thenReturn(mock(CommandAsyncExecutor.class));
            RedissonConnection connection = spy(new RedissonConnection(redisson));
            doReturn(false).when(connection).isPipelined();
            doReturn(VALUE).when(connection).get(KEY);
            org.mockito.Mockito.doThrow(failure).when(connection).set(KEY, VALUE);
            RedissonConnectionFactory factory = spy(new RedissonConnectionFactory(mock(RedissonClient.class)));
            doReturn(connection).when(factory).getConnection();
            return new RedissonCommandFixture(factory, connection, failure);
        }

        @Bean
        RedissonConnectionFactory redissonConnectionFactory(RedissonCommandFixture fixture) {
            return fixture.factory();
        }
    }

    record RedissonCommandFixture(RedissonConnectionFactory factory, RedissonConnection connection,
                                  IllegalStateException failure) { }
}
