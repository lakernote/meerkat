package io.github.lakernote.meerkat.integration.jdbc.internal;

import io.github.lakernote.meerkat.trace.SpanKind;
import io.github.lakernote.meerkat.trace.internal.TraceCompletion;
import io.github.lakernote.meerkat.trace.internal.TraceContext;
import net.ttddyy.dsproxy.ExecutionInfo;
import net.ttddyy.dsproxy.QueryInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcTraceQueryExecutionListenerTest {
    private final JdbcTraceQueryExecutionListener listener = new JdbcTraceQueryExecutionListener();

    @AfterEach
    void clearTraceContext() {
        TraceContext.clear();
    }

    @Test
    void countsSqlStatisticsWithAndWithoutAnActiveTrace() {
        var stats = new io.github.lakernote.meerkat.metrics.internal.OperationStats(10);
        var counting = new JdbcTraceQueryExecutionListener(stats, () -> false);
        List<QueryInfo> queries = List.of(new QueryInfo("SELECT * FROM orders WHERE id IN (1, 2)"));

        ExecutionInfo outsideTrace = new ExecutionInfo();
        counting.beforeQuery(outsideTrace, queries);
        counting.afterQuery(outsideTrace, queries);
        ExecutionInfo failed = new ExecutionInfo();
        failed.setThrowable(new SQLException("boom"));
        TraceContext.startRoot("jdbc-request", SpanKind.HTTP);
        counting.beforeQuery(failed, queries);
        counting.afterQuery(failed, queries);
        TraceContext.clear();

        var sql = stats.snapshot(io.github.lakernote.meerkat.metrics.internal.OperationStats.Window.TODAY)
                .categories().stream()
                .filter(c -> c.category() == io.github.lakernote.meerkat.metrics.internal.OperationStats.Category.SQL)
                .findFirst().orElseThrow();
        assertThat(sql.rows()).singleElement().satisfies(row -> {
            assertThat(row.name()).isEqualTo("SELECT * FROM orders WHERE id IN (?…)");
            assertThat(row.count()).isEqualTo(2);
            assertThat(row.errors()).isEqualTo(1);
        });
    }

    @Test
    void cachesNormalizedSqlPerStatementAndStaysBounded() {
        var stats = new io.github.lakernote.meerkat.metrics.internal.OperationStats(10);
        var counting = new JdbcTraceQueryExecutionListener(stats, () -> false);
        List<QueryInfo> repeated = List.of(new QueryInfo("SELECT * FROM orders WHERE id IN (?, ?)"));
        for (int i = 0; i < 3; i++) run(counting, repeated);
        run(counting, List.of(new QueryInfo("SELECT 1"), new QueryInfo("SELECT 2")));
        assertThat(counting.cachedSqlCount()).isEqualTo(1);
        assertThat(stats.snapshot(io.github.lakernote.meerkat.metrics.internal.OperationStats.Window.HOUR)
                .categories().stream().filter(category -> category.category()
                        == io.github.lakernote.meerkat.metrics.internal.OperationStats.Category.SQL)
                .findFirst().orElseThrow().rows())
                .extracting(row -> row.name(), row -> row.count())
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple("SELECT * FROM orders WHERE id IN (?…)", 3L),
                        org.assertj.core.groups.Tuple.tuple("SELECT ? [batch]", 1L));

        // Many distinct statements flood the cache; the statement still in use survives every sweep.
        for (int i = 0; i <= 5 * JdbcTraceQueryExecutionListener.MAX_CACHED_SQL; i++) {
            run(counting, List.of(new QueryInfo("SELECT c" + i + " FROM t")));
            if (i % 100 == 0) run(counting, repeated);
        }
        assertThat(counting.cachedSqlCount()).isBetween(1, JdbcTraceQueryExecutionListener.MAX_CACHED_SQL);
        assertThat(counting.cached(repeated.get(0).getQuery())).isTrue();

        // SQL with inlined secrets is cached too, but only under a fingerprint: the raw text is never kept.
        String secret = "UPDATE users SET password = 'hunter2' WHERE ssn = 123456789 /* token=abc */";
        run(counting, List.of(new QueryInfo(secret)));
        assertThat(counting.cached(secret)).isTrue();
        assertThat(JdbcTraceQueryExecutionListener.fingerprint(secret))
                .isEqualTo(JdbcTraceQueryExecutionListener.fingerprint(new String(secret.toCharArray())))
                .isNotEqualTo(JdbcTraceQueryExecutionListener.fingerprint(secret.replace("hunter2", "hunter3")))
                .isNotEqualTo(JdbcTraceQueryExecutionListener.fingerprint(secret + " "));
        // "Aa" and "BB" share String.hashCode and length; the fingerprint keeps them apart.
        assertThat("SELECT Aa FROM t".hashCode()).isEqualTo("SELECT BB FROM t".hashCode());
        run(counting, List.of(new QueryInfo("SELECT Aa FROM t")));
        assertThat(counting.cached("SELECT Aa FROM t")).isTrue();
        assertThat(counting.cached("SELECT BB FROM t")).isFalse();
    }

    private static void run(JdbcTraceQueryExecutionListener target, List<QueryInfo> queries) {
        ExecutionInfo execution = new ExecutionInfo();
        target.beforeQuery(execution, queries);
        target.afterQuery(execution, queries);
    }

    @Test
    void recordsOnlyTheRedactedSqlTemplate() {
        ExecutionInfo execution = new ExecutionInfo();
        execution.setDataSourceName("primary");
        List<QueryInfo> queries = List.of(new QueryInfo("SELECT 'literal-secret--tail'"));
        AtomicReference<TraceCompletion> completion = new AtomicReference<>();

        TraceContext.startRoot("jdbc-request", SpanKind.HTTP);
        listener.beforeQuery(execution, queries);
        listener.afterQuery(execution, queries);
        TraceContext.currentRootTraceHandle().requireTree();
        TraceContext.stopRoot(0, null, completion::set);

        assertThat(completion.get().tree())
                .contains("SQL SELECT", "primary · SELECT ?")
                .doesNotContain("literal-secret", "tail");
        assertThat(completion.get().error()).isFalse();
    }

    @Test
    void namesTheDataSourceOnlyWhenTheApplicationHasSeveral() {
        var single = new JdbcTraceQueryExecutionListener(null, () -> false);
        ExecutionInfo execution = new ExecutionInfo();
        execution.setDataSourceName("dataSource");
        List<QueryInfo> queries = List.of(new QueryInfo("SELECT 1"));
        AtomicReference<TraceCompletion> completion = new AtomicReference<>();

        TraceContext.startRoot("jdbc-request", SpanKind.HTTP);
        single.beforeQuery(execution, queries);
        single.afterQuery(execution, queries);
        TraceContext.currentRootTraceHandle().requireTree();
        TraceContext.stopRoot(0, null, completion::set);

        assertThat(completion.get().tree()).contains("tag=\"SELECT ?\"").doesNotContain("dataSource");
    }

    @Test
    void recordsSafeFailureEvidenceWithoutDriverOrCauseMessages() {
        String sqlSecret = "driver-secret-c91e";
        String causeSecret = "bind-secret-4d2e";
        SQLException failure = new SQLException(
                "Syntax error in SQL SELECT '" + sqlSecret + "'", new IllegalArgumentException(causeSecret));
        ExecutionInfo execution = new ExecutionInfo();
        execution.setThrowable(failure);
        List<QueryInfo> queries = List.of(new QueryInfo("SELECT '" + sqlSecret + "' FROM missing_table"));
        AtomicReference<TraceCompletion> completion = new AtomicReference<>();

        TraceContext.startRoot("jdbc-request", SpanKind.HTTP);
        listener.beforeQuery(execution, queries);
        listener.afterQuery(execution, queries);
        TraceContext.currentRootTraceHandle().requireTree();
        TraceContext.stopRoot(0, null, completion::set);

        TraceCompletion result = completion.get();
        assertThat(result.error()).isTrue();
        assertThat(result.reasons()).containsExactly("SQL_ERROR");
        assertThat(result.errorType()).isEqualTo(SQLException.class.getName());
        assertThat(result.errorMessage()).isEqualTo("JDBC query failed");
        assertThat(result.stackTrace())
                .contains(SQLException.class.getName(), IllegalArgumentException.class.getName())
                .doesNotContain(sqlSecret, causeSecret, "Syntax error in SQL", "secret");
        assertThat(result.tree())
                .contains("SQL SELECT", "SELECT ? FROM missing_table")
                .doesNotContain(sqlSecret, causeSecret, "secret");
    }

    @Test
    void fallsBackToSafeNonEmptyEvidenceWhenThrowableStackCannotBeRead() {
        SQLException failure = new UnreadableStackSqlException("driver-secret");
        ExecutionInfo execution = new ExecutionInfo();
        execution.setThrowable(failure);
        List<QueryInfo> queries = List.of(new QueryInfo("SELECT 'driver-secret'"));
        AtomicReference<TraceCompletion> completion = new AtomicReference<>();

        TraceContext.startRoot("jdbc-request", SpanKind.HTTP);
        listener.beforeQuery(execution, queries);
        listener.afterQuery(execution, queries);
        TraceContext.currentRootTraceHandle().requireTree();
        TraceContext.stopRoot(0, failure, completion::set);

        assertThat(completion.get().error()).isTrue();
        assertThat(completion.get().errorMessage()).isEqualTo("JDBC query failed");
        assertThat(completion.get().stackTrace())
                .isEqualTo(UnreadableStackSqlException.class.getName())
                .doesNotContain("driver-secret");
    }

    private static final class UnreadableStackSqlException extends SQLException {
        private UnreadableStackSqlException(String message) {
            super(message);
        }

        @Override
        public StackTraceElement[] getStackTrace() {
            throw new IllegalStateException("stack-secret");
        }
    }
}
