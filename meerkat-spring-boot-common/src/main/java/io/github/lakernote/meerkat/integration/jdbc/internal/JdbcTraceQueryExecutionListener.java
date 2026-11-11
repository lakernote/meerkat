package io.github.lakernote.meerkat.integration.jdbc.internal;

import io.github.lakernote.meerkat.metrics.internal.OperationStats;
import io.github.lakernote.meerkat.trace.SpanKind;
import io.github.lakernote.meerkat.trace.internal.CaptureTarget;
import io.github.lakernote.meerkat.trace.internal.CaptureThreadState;
import io.github.lakernote.meerkat.trace.internal.ScopedSpan;
import io.github.lakernote.meerkat.trace.internal.TraceContext;
import net.ttddyy.dsproxy.ExecutionInfo;
import net.ttddyy.dsproxy.QueryInfo;
import net.ttddyy.dsproxy.listener.QueryExecutionListener;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/**
 * 将 JDBC 执行记录为当前 Trace 的子节点并计入 SQL 统计，不发布 Micrometer Observation。
 * <p>统计不要求活动 Trace；Trace 子节点只在活动 Trace 中创建。</p>
 */
public final class JdbcTraceQueryExecutionListener implements QueryExecutionListener {
    private static final String TRACE_STATE_KEY = JdbcTraceQueryExecutionListener.class.getName() + ".traceState";
    private final OperationStats operationStats;
    private final BooleanSupplier multipleDataSources;
    /** Resolved on the first query, once every DataSource bean is defined. */
    private volatile Boolean showDataSourceName;
    /**
     * Normalizing SQL costs microseconds while prepared statements repeat the same text, so the result is cached
     * per raw SQL. SQL with inlined literals can be unbounded, so when the cache is full the entries not used since
     * the previous sweep are dropped; hot prepared statements stay cached.
     */
    static final int MAX_CACHED_SQL = 1_024;
    static final int MAX_CACHED_SQL_LENGTH = 2_048;
    private final ConcurrentHashMap<String, CachedNames> sqlNames = new ConcurrentHashMap<>();

    public JdbcTraceQueryExecutionListener() {
        this(null, () -> true);
    }

    /**
     * @param operationStats      may be null when call statistics are disabled
     * @param multipleDataSources whether the application has more than one DataSource; SQL spans name their
     *                            DataSource only then
     */
    public JdbcTraceQueryExecutionListener(OperationStats operationStats, BooleanSupplier multipleDataSources) {
        this.operationStats = operationStats;
        this.multipleDataSources = multipleDataSources;
    }

    @Override
    public void beforeQuery(ExecutionInfo executionInfo, List<QueryInfo> queryInfoList) {
        try {
            boolean traceActive = TraceContext.active();
            if (!traceActive && operationStats == null) return;

            SqlNames names = sqlNames(queryInfoList);
            ScopedSpan span = null;
            if (traceActive) {
                String dataSource = showDataSourceName() ? executionInfo.getDataSourceName() : null;
                String detail = dataSource == null || dataSource.isBlank() ? names.sql() : dataSource + " · " + names.sql();
                span = TraceContext.startScopedSpan(names.spanName(), SpanKind.DATABASE, detail);
            }
            CaptureTarget capture = span == null ? null : span.captureTarget();
            if (capture != null) {
                JdbcCapture.before(capture, queryInfoList);
                CaptureThreadState.rememberStatement(executionInfo.getStatement(), capture);
            }
            try {
                executionInfo.addCustomValue(TRACE_STATE_KEY, new TraceState(span, names.statsName(),
                        System.nanoTime(), capture));
            } catch (RuntimeException | Error failure) {
                if (span != null) span.close();
                throw failure;
            }
        } catch (Throwable ignored) {
            // Trace 回调不能影响 JDBC 调用。
        }
    }

    private SqlNames sqlNames(List<QueryInfo> queries) {
        QueryInfo single = queries != null && queries.size() == 1 ? queries.get(0) : null;
        String raw = single == null ? null : single.getQuery();
        boolean cacheable = raw != null && raw.length() <= MAX_CACHED_SQL_LENGTH;
        if (cacheable) {
            CachedNames cached = sqlNames.get(raw);
            if (cached != null) {
                if (!cached.used) cached.used = true;
                return cached.names;
            }
        }
        String sql = JdbcTraceSql.describe(queries);
        SqlNames names = new SqlNames(sql, "SQL " + JdbcTraceSql.commandOf(sql),
                operationStats == null ? null : JdbcTraceSql.statsName(queries, sql));
        if (cacheable) {
            if (sqlNames.size() >= MAX_CACHED_SQL) sweep();
            if (sqlNames.size() < MAX_CACHED_SQL) sqlNames.putIfAbsent(raw, new CachedNames(names));
        }
        return names;
    }

    private synchronized void sweep() {
        if (sqlNames.size() < MAX_CACHED_SQL) return;
        sqlNames.values().removeIf(cached -> {
            if (!cached.used) return true;
            cached.used = false;
            return false;
        });
    }

    private static final class CachedNames {
        final SqlNames names;
        volatile boolean used;

        CachedNames(SqlNames names) {
            this.names = names;
        }
    }

    boolean cached(String raw) {
        return sqlNames.containsKey(raw);
    }

    int cachedSqlCount() {
        return sqlNames.size();
    }

    private record SqlNames(String sql, String spanName, String statsName) { }

    private boolean showDataSourceName() {
        Boolean show = showDataSourceName;
        if (show == null) {
            show = multipleDataSources.getAsBoolean();
            showDataSourceName = show;
        }
        return show;
    }

    @Override
    public void afterQuery(ExecutionInfo executionInfo, List<QueryInfo> queryInfoList) {
        TraceState state = null;
        try {
            state = executionInfo.getCustomValue(TRACE_STATE_KEY, TraceState.class);
            if (state == null) return;

            Throwable failure = executionInfo.getThrowable();
            if (state.capture != null) JdbcCapture.after(state.capture, executionInfo.getResult(), failure);
            if (failure != null && state.span != null) {
                TraceContext.markError("SQL_ERROR", JdbcTraceSafety.captureFailure(failure));
            }
            if (state.statsName != null) {
                operationStats.record(OperationStats.Category.SQL, state.statsName,
                        System.nanoTime() - state.startedAtNanos, failure != null);
            }
        } catch (Throwable ignored) {
            // Trace 回调不能替换 JDBC 结果或原始异常。
        } finally {
            if (state != null && state.span != null) {
                try {
                    state.span.close();
                } catch (Throwable ignored) {
                    // Trace 清理失败不能传播到业务代码。
                }
            }
        }
    }

    private record TraceState(ScopedSpan span, String statsName, long startedAtNanos, CaptureTarget capture) { }
}
