package io.github.lakernote.meerkat.integration.transaction.internal;

import io.github.lakernote.meerkat.trace.internal.TraceIds;
import io.github.lakernote.meerkat.config.MeerkatProperties;
import io.github.lakernote.meerkat.trace.*;
import io.github.lakernote.meerkat.trace.internal.*;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionExecutionListener;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Records Spring transaction boundaries without proxying application services.
 */
public final class TransactionTraceListener implements TransactionExecutionListener {
    private final MeerkatProperties properties;
    /**
     * Shared rather than thread-local because some transaction managers complete on another thread. Weak keys let
     * an execution that never reports completion be collected; Spring's executions use identity equality.
     */
    private final Map<TransactionExecution, State> states = Collections.synchronizedMap(new WeakHashMap<>());

    public TransactionTraceListener(MeerkatProperties properties) {
        this.properties = properties;
    }

    @Override
    public void beforeBegin(TransactionExecution execution) {
        if (ObservationSuppression.active()) return;
        try {
            String currentId = TraceIdContext.currentTraceId();
            boolean tracing = properties.getTrace().isEnabled();
            boolean needsTraceId = tracing && TraceContext.active();
            boolean ownsTraceId = needsTraceId && !TraceIds.isValidTraceId(currentId);
            String traceId = ownsTraceId ? TraceIds.getOrCreateTraceId()
                    : currentId == null ? "" : currentId;
            State state = new State(traceId, ownsTraceId,
                    safeName(execution.getTransactionName()), execution.isReadOnly());
            if (tracing && TraceContext.active()) {
                // Begin before the transaction manager acquires its connection so connection work is nested here.
                state.span = TraceContext.startScopedSpan(state.name, SpanKind.TRANSACTION,
                        state.readOnly ? "read-only" : "write");
            }
            if (state.span != null || ownsTraceId) states.put(execution, state);
        } catch (Throwable ignored) {
            states.remove(execution);
        }
    }

    @Override
    public void afterBegin(TransactionExecution execution, Throwable failure) {
        if (failure != null) finish(execution, false, failure);
    }

    @Override
    public void afterCommit(TransactionExecution execution, Throwable failure) {
        finish(execution, failure == null, failure);
    }

    @Override
    public void afterRollback(TransactionExecution execution, Throwable failure) {
        finish(execution, false, failure);
    }

    private void finish(TransactionExecution execution, boolean success, Throwable failure) {
        State state = states.remove(execution);
        if (state == null) return;
        try {
            if (state.span != null && !success) {
                BoundedThrowableFormatter.FailureEvidence error = failure == null
                        ? new BoundedThrowableFormatter.FailureEvidence(
                        "TransactionRollback", "Transaction rolled back", "")
                        : BoundedThrowableFormatter.capture(failure);
                // The span belongs to the trace that began the transaction, whichever thread completes it.
                state.span.markTraceError("TRANSACTION_ERROR", error);
            }
        } catch (Throwable ignored) {
            // Instrumentation must never change the transaction result.
        } finally {
            if (state.span != null) state.span.close();
            if (state.ownsTraceId && state.traceId.equals(TraceIdContext.currentTraceId())) TraceIds.clear();
        }
    }

    private static String safeName(String name) {
        return name == null || name.isBlank() ? "transaction" : name.length() > 200 ? name.substring(0, 200) : name;
    }

    private static final class State {
        private final String traceId;
        private final boolean ownsTraceId;
        private final String name;
        private final boolean readOnly;
        private ScopedSpan span;

        private State(String traceId, boolean ownsTraceId, String name, boolean readOnly) {
            this.traceId = traceId;
            this.ownsTraceId = ownsTraceId;
            this.name = name;
            this.readOnly = readOnly;
        }
    }
}
