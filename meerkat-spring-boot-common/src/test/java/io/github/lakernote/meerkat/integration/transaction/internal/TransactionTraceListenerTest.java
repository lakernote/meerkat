package io.github.lakernote.meerkat.integration.transaction.internal;

import io.github.lakernote.meerkat.trace.internal.TraceIds;
import io.github.lakernote.meerkat.trace.internal.TraceCompletion;
import io.github.lakernote.meerkat.config.MeerkatProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionExecution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TransactionTraceListenerTest {
    @AfterEach
    void clearContext() {
        TraceIds.clear();
    }

    @Test
    void attachesTransactionAsSingleChildOfExistingHttpTrace() {
        MeerkatProperties properties = new MeerkatProperties();
        TransactionTraceListener listener = new TransactionTraceListener(properties);
        TransactionExecution execution = execution("OrderService#create", true);
        io.github.lakernote.meerkat.trace.internal.TraceContext.startRoot("POST /orders",
                io.github.lakernote.meerkat.trace.SpanKind.HTTP);

        listener.beforeBegin(execution);
        String transactionSpanId = io.github.lakernote.meerkat.trace.internal.TraceContext.currentSpanId();
        var connection = io.github.lakernote.meerkat.trace.internal.TraceContext.startScopedSpan(
                "getConnection", io.github.lakernote.meerkat.trace.SpanKind.DATABASE, "");
        assertThat(connection.parentSpanId()).isEqualTo(transactionSpanId);
        connection.close();
        listener.afterBegin(execution, null);
        var query = io.github.lakernote.meerkat.trace.internal.TraceContext.startScopedSpan(
                "OrderMapper#insert", io.github.lakernote.meerkat.trace.SpanKind.DATABASE, "INSERT");
        assertThat(query.parentSpanId()).isEqualTo(transactionSpanId);
        query.close();
        listener.afterCommit(execution, null);
        java.util.concurrent.atomic.AtomicReference<io.github.lakernote.meerkat.trace.internal.TraceCompletion> completion =
                new java.util.concurrent.atomic.AtomicReference<>();
        io.github.lakernote.meerkat.trace.internal.TraceContext.markSlow("TEST_CAPTURE");
        io.github.lakernote.meerkat.trace.internal.TraceContext.stopRoot(0, null, completion::set);

        assertThat(completion.get().tree()).contains("OrderService#create", "getConnection", "OrderMapper#insert");
        assertThat(completion.get().tree().indexOf("OrderService#create"))
                .isLessThan(completion.get().tree().indexOf("getConnection"));
        assertThat(completion.get().tree().indexOf("getConnection"))
                .isLessThan(completion.get().tree().indexOf("OrderMapper#insert"));
    }

    @Test
    void closesTransactionSpanWhenBeginFails() {
        MeerkatProperties properties = new MeerkatProperties();
        TransactionTraceListener listener = new TransactionTraceListener(properties);
        TransactionExecution execution = execution("OrderService#create", false);
        io.github.lakernote.meerkat.trace.internal.TraceContext.startRoot("POST /orders",
                io.github.lakernote.meerkat.trace.SpanKind.HTTP);
        String rootSpanId = io.github.lakernote.meerkat.trace.internal.TraceContext.currentSpanId();

        listener.beforeBegin(execution);
        assertThat(io.github.lakernote.meerkat.trace.internal.TraceContext.currentSpanId()).isNotEqualTo(rootSpanId);
        listener.afterBegin(execution, new IllegalStateException("connection unavailable"));

        assertThat(io.github.lakernote.meerkat.trace.internal.TraceContext.currentSpanId()).isEqualTo(rootSpanId);
        java.util.concurrent.atomic.AtomicReference<io.github.lakernote.meerkat.trace.internal.TraceCompletion> completion =
                new java.util.concurrent.atomic.AtomicReference<>();
        io.github.lakernote.meerkat.trace.internal.TraceContext.stopRoot(0, null, completion::set);
        assertThat(completion.get().tree()).contains("OrderService#create");
        assertThat(completion.get().error()).isTrue();
        assertThat(completion.get().errorMessage()).isEqualTo("connection unavailable");
    }

    @Test
    void completesTransactionThatFinishesOnAnotherThread() throws Exception {
        TransactionTraceListener listener = new TransactionTraceListener(new MeerkatProperties());
        TransactionExecution execution = execution("OrderService#create", false);
        io.github.lakernote.meerkat.trace.internal.TraceContext.startRoot("POST /orders",
                io.github.lakernote.meerkat.trace.SpanKind.HTTP);
        String rootSpanId = io.github.lakernote.meerkat.trace.internal.TraceContext.currentSpanId();
        listener.beforeBegin(execution);
        listener.afterBegin(execution, null);

        Thread completer = new Thread(() -> listener.afterRollback(execution, null));
        completer.start();
        completer.join();

        assertThat(io.github.lakernote.meerkat.trace.internal.TraceContext.currentSpanId()).isEqualTo(rootSpanId);
        java.util.concurrent.atomic.AtomicReference<TraceCompletion> completion =
                new java.util.concurrent.atomic.AtomicReference<>();
        io.github.lakernote.meerkat.trace.internal.TraceContext.stopRoot(0, null, completion::set);
        assertThat(completion.get().error()).isTrue();
        assertThat(completion.get().errorMessage()).isEqualTo("Transaction rolled back");
        var transaction = completion.get().nodes().get(0).children().get(0);
        assertThat(transaction.name()).isEqualTo("OrderService#create");
        assertThat(transaction.unfinishedAtTraceEnd()).isFalse();
        assertThat(transaction.error()).isTrue();
    }

    private static TransactionExecution execution(String name, boolean readOnly) {
        TransactionExecution execution = mock(TransactionExecution.class);
        when(execution.getTransactionName()).thenReturn(name);
        when(execution.isReadOnly()).thenReturn(readOnly);
        return execution;
    }
}
