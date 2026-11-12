package io.github.lakernote.meerkat.integration.jdbc.internal;

import io.github.lakernote.meerkat.trace.SpanKind;
import io.github.lakernote.meerkat.trace.internal.CaptureLimits;
import io.github.lakernote.meerkat.trace.internal.CaptureTarget;
import io.github.lakernote.meerkat.trace.internal.CaptureThreadState;
import io.github.lakernote.meerkat.trace.internal.TraceContext;
import io.github.lakernote.meerkat.trace.internal.TraceNode;
import net.ttddyy.dsproxy.ExecutionInfo;
import net.ttddyy.dsproxy.QueryInfo;
import net.ttddyy.dsproxy.proxy.ParameterSetOperation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JdbcCaptureTest {
    private final JdbcTraceQueryExecutionListener listener = new JdbcTraceQueryExecutionListener();
    private final AtomicReference<List<TraceNode>> captured = new AtomicReference<>();

    @AfterEach
    void clear() {
        TraceContext.clear();
        CaptureThreadState.clear();
    }

    private void startCapture(CaptureLimits limits) {
        TraceContext.startRoot("GET /orders", SpanKind.HTTP);
        TraceContext.currentRootTraceHandle().beginCapture((completion, nodes) -> captured.set(nodes), limits);
    }

    private void startCapture() {
        startCapture(new CaptureLimits(1024, 2, 10_000, 1_000_000));
    }

    private TraceNode finish() {
        TraceContext.stopRoot(Long.MAX_VALUE);
        return captured.get().get(0);
    }

    private static Map<String, String> sqlCaptures(TraceNode root) {
        assertThat(root.children()).hasSize(1);
        return root.children().get(0).captures();
    }

    private static ParameterSetOperation op(Method method, Object... args) {
        return new ParameterSetOperation(method, args);
    }

    private static Method method(Class<?> type, String name, Class<?>... parameters) throws NoSuchMethodException {
        return type.getMethod(name, parameters);
    }

    private void run(List<QueryInfo> queries, ExecutionInfo execution) {
        listener.beforeQuery(execution, queries);
        listener.afterQuery(execution, queries);
    }

    @Test
    void stopsBuildingParametersOnceTheValueLimitIsReached() throws Exception {
        startCapture(new CaptureLimits(1024, 2, 100, 1_000_000));
        QueryInfo query = new QueryInfo("INSERT INTO t VALUES (?)");
        Method setString = method(PreparedStatement.class, "setString", int.class, String.class);
        List<ParameterSetOperation> parameters = new java.util.ArrayList<>();
        for (int i = 1; i <= 1_000; i++) parameters.add(op(setString, i, "v".repeat(1_500)));
        query.getParametersList().add(parameters);

        run(List.of(query), new ExecutionInfo());

        String params = sqlCaptures(finish()).get("params");
        java.util.regex.Matcher built = java.util.regex.Pattern.compile("truncated, (\\d+) chars").matcher(params);
        assertThat(built.find()).isTrue();
        assertThat(Integer.parseInt(built.group(1))).isLessThan(2_500);
    }

    @Test
    void recordsSqlParametersAndUpdateCount() throws Exception {
        startCapture();
        QueryInfo query = new QueryInfo("UPDATE t SET a = ?, b = ?, c = ? WHERE d = ?");
        query.getParametersList().add(List.of(
                op(method(PreparedStatement.class, "setString", int.class, String.class), 2, "x"),
                op(method(PreparedStatement.class, "setInt", int.class, int.class), 1, 5),
                op(method(PreparedStatement.class, "setNull", int.class, int.class), 3, Types.VARCHAR),
                op(method(CallableStatement.class, "registerOutParameter", int.class, int.class), 4, Types.INTEGER),
                op(method(PreparedStatement.class, "setObject", int.class, Object.class)),
                op(method(PreparedStatement.class, "setBytes", int.class, byte[].class), "named", new byte[] {'h'})));
        ExecutionInfo execution = new ExecutionInfo();
        execution.setResult(3);

        run(List.of(query), execution);

        Map<String, String> captures = sqlCaptures(finish());
        assertThat(captures).containsEntry("sql", "UPDATE t SET a = ?, b = ?, c = ? WHERE d = ?")
                .containsEntry("params", "[1] 5, [2] 'x', [3] NULL, [4] OUT, [named] h")
                .containsEntry("result", "updated rows: 3");
    }

    @Test
    void recordsBatchesAndBatchResults() throws Exception {
        startCapture();
        Method setLong = method(PreparedStatement.class, "setLong", int.class, long.class);
        QueryInfo insert = new QueryInfo("INSERT INTO t VALUES (?)");
        for (int i = 0; i < 60; i++) insert.getParametersList().add(List.of(op(setLong, 1, (long) i)));
        insert.getParametersList().add(1, List.of());
        ExecutionInfo execution = new ExecutionInfo();
        execution.setResult(new int[] {1, 1});

        run(List.of(insert, new QueryInfo("DELETE FROM t")), execution);

        Map<String, String> captures = sqlCaptures(finish());
        assertThat(captures.get("sql")).isEqualTo("-- batch of 2 statements\nINSERT INTO t VALUES (?);\nDELETE FROM t");
        assertThat(captures.get("params")).startsWith("#1 [1] 0\n#2 [1] 1\n").contains("#50 [1] 49")
                .endsWith("\n… 61 parameter sets").doesNotContain("#51");
        assertThat(captures.get("result")).isEqualTo("batch updated rows: [1, 1]");
    }

    @Test
    void recordsLongResultAndFailures() {
        startCapture();
        ExecutionInfo updated = new ExecutionInfo();
        updated.setResult(7L);
        run(List.of(new QueryInfo("DELETE FROM a")), updated);
        ExecutionInfo failed = new ExecutionInfo();
        failed.setThrowable(new SQLException("bad sql"));
        run(List.of(new QueryInfo("DELETE FROM b")), failed);
        ExecutionInfo query = new ExecutionInfo();
        query.setResult(mock(ResultSet.class));
        run(List.of(new QueryInfo("SELECT 1")), query);

        TraceNode root = finish();
        assertThat(root.children()).extracting(TraceNode::captures).containsExactly(
                Map.of("sql", "DELETE FROM a", "result", "updated rows: 7"),
                Map.of("sql", "DELETE FROM b", "error", "java.sql.SQLException: bad sql"),
                Map.of("sql", "SELECT 1"));
    }

    @Test
    void stopsAddingQueriesOnceSqlAndParametersReachTheLimit() throws Exception {
        startCapture(new CaptureLimits(1024, 2, 64, 1_000_000));
        Method setString = method(PreparedStatement.class, "setString", int.class, String.class);
        List<QueryInfo> queries = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            QueryInfo query = new QueryInfo("SELECT '" + "q".repeat(80) + "' FROM t" + i + " WHERE a = ?");
            query.getParametersList().add(List.of(op(setString, 1, "p".repeat(80))));
            queries.add(query);
        }

        run(queries, new ExecutionInfo());

        Map<String, String> captures = sqlCaptures(finish());
        assertThat(captures.get("sql")).contains("[truncated,").doesNotContain("FROM t1");
        assertThat(captures.get("params")).contains("[truncated, 86 chars]");
    }

    @Test
    void boundsTheSqlOfLargeStatementBatchesBeforeTruncating() {
        startCapture(new CaptureLimits(1024, 2, 64, 1_000_000));
        List<QueryInfo> queries = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) queries.add(new QueryInfo("INSERT INTO t VALUES (" + i + ")"));

        run(queries, new ExecutionInfo());

        String sql = sqlCaptures(finish()).get("sql");
        assertThat(sql).startsWith("-- batch of 10000 statements\nINSERT INTO").contains("[truncated,").hasSize(64);
    }

    @Test
    void skipsCaptureWhenTraceIsNotCaptured() {
        TraceContext.startRoot("GET /orders", SpanKind.HTTP);
        ExecutionInfo execution = new ExecutionInfo();
        execution.setStatement(mock(Statement.class));

        run(List.of(new QueryInfo("SELECT 1")), execution);

        assertThat(CaptureThreadState.hasPendingStatement()).isFalse();
    }

    @Test
    void remembersStatementAndRecordsRowsTheApplicationReads() throws Exception {
        startCapture();
        Statement statement = mock(Statement.class);
        ResultSet raw = resultSet(statement);
        ExecutionInfo execution = new ExecutionInfo();
        execution.setStatement(statement);
        listener.beforeQuery(execution, List.of(new QueryInfo("SELECT id, name FROM users")));
        SpringAwareJdbcProxyFactory factory = new SpringAwareJdbcProxyFactory();

        ResultSet wrapped = factory.createResultSet(raw, null, null);
        execution.setResult(wrapped);
        listener.afterQuery(execution, List.of());

        assertThat(Proxy.isProxyClass(wrapped.getClass())).isTrue();
        List<String> names = new ArrayList<>();
        while (wrapped.next()) {
            wrapped.getLong(1);
            names.add(wrapped.getString("name"));
            wrapped.getBlob(2);
            wrapped.getObject("missing");
            wrapped.getObject(99);
        }
        assertThat(names).containsExactly("a", "b", "c");
        assertThat(wrapped.getRow()).isZero();
        assertThat(wrapped.toString()).startsWith("Captured");
        assertThat(wrapped.equals(wrapped)).isTrue();
        assertThat(wrapped.equals(raw)).isFalse();
        assertThat(wrapped.hashCode()).isEqualTo(System.identityHashCode(wrapped));
        when(raw.getString(1)).thenThrow(new SQLException("closed"));
        assertThatThrownBy(() -> wrapped.getString(1)).isInstanceOf(SQLException.class).hasMessage("closed");

        assertThat(sqlCaptures(finish())).containsEntry("rows",
                "ID | NAME\n1 | a\n2 | b\n(rows read: 3, first 2 shown; · = not read by the application)");
        assertThat(CaptureThreadState.hasPendingStatement()).as("released when the trace ends").isFalse();
    }

    @Test
    void survivesMissingMetadata() throws Exception {
        startCapture();
        ResultSet broken = mock(ResultSet.class);
        when(broken.next()).thenReturn(true, false);
        when(broken.getMetaData()).thenThrow(new SQLException("no metadata"));
        when(broken.getString(1)).thenReturn("x");

        ResultSet noMetadata = CapturingResultSet.wrap(broken, TraceContext.currentRootTraceHandle().captureTarget());
        noMetadata.next();
        noMetadata.getString(1);
        noMetadata.next();

        Map<String, String> captures = finish().captures();
        assertThat(captures.get("rows")).isEqualTo("(rows read: 1; · = not read by the application)");
    }

    @Test
    void rendersEmptyResult() throws Exception {
        startCapture();
        ResultSet empty = mock(ResultSet.class);
        ResultSet wrapped = CapturingResultSet.wrap(empty, TraceContext.currentCaptureTarget());

        assertThat(wrapped.next()).isFalse();

        assertThat(finish().captures().get("rows")).isEqualTo("(rows read: 0; · = not read by the application)");
    }

    @Test
    void rendersHeaderAndUnreadCells() throws Exception {
        startCapture();
        ResultSet wrapped = CapturingResultSet.wrap(resultSet(null), TraceContext.currentCaptureTarget());
        wrapped.next();
        wrapped.getString("NAME");

        assertThat(finish().captures().get("rows"))
                .isEqualTo("ID | NAME\n· | a\n(rows read: 1; · = not read by the application)");
    }

    @Test
    void factoryReturnsDriverResultSetUnlessItsStatementIsCaptured() throws Exception {
        SpringAwareJdbcProxyFactory factory = new SpringAwareJdbcProxyFactory();
        Statement statement = mock(Statement.class);
        ResultSet raw = mock(ResultSet.class);
        when(raw.getStatement()).thenReturn(statement);
        ResultSet failing = mock(ResultSet.class);
        when(failing.getStatement()).thenThrow(new SQLException("closed"));

        assertThat(factory.createResultSet(raw, null, null)).isSameAs(raw);

        startCapture();
        CaptureThreadState.rememberStatement(mock(Statement.class), TraceContext.currentCaptureTarget());
        assertThat(factory.createResultSet(raw, null, null)).isSameAs(raw);
        assertThat(factory.createResultSet(failing, null, null)).isSameAs(failing);
        CaptureThreadState.rememberStatement(statement, TraceContext.currentCaptureTarget());
        assertThat(factory.createResultSet(raw, null, null)).isNotSameAs(raw);
    }

    private static ResultSet resultSet(Statement statement) throws SQLException {
        ResultSet raw = mock(ResultSet.class);
        ResultSetMetaData metadata = mock(ResultSetMetaData.class);
        when(raw.getStatement()).thenReturn(statement);
        when(raw.getMetaData()).thenReturn(metadata);
        when(metadata.getColumnCount()).thenReturn(2);
        when(metadata.getColumnLabel(1)).thenReturn("ID");
        when(metadata.getColumnLabel(2)).thenReturn("NAME");
        when(raw.next()).thenReturn(true, true, true, false);
        when(raw.getLong(1)).thenReturn(1L, 2L, 3L);
        when(raw.getString("name")).thenReturn("a", "b", "c");
        when(raw.getString("NAME")).thenReturn("a", "b", "c");
        return raw;
    }
}
