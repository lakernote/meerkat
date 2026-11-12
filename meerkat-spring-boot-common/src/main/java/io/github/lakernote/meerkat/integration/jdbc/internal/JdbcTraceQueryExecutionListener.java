package io.github.lakernote.meerkat.integration.jdbc.internal;

import io.github.lakernote.meerkat.metrics.internal.OperationStats;
import io.github.lakernote.meerkat.trace.SpanKind;
import io.github.lakernote.meerkat.trace.internal.CaptureTarget;
import io.github.lakernote.meerkat.trace.internal.CaptureThreadState;
import io.github.lakernote.meerkat.trace.internal.ScopedSpan;
import io.github.lakernote.meerkat.trace.internal.TraceContext;
import net.ttddyy.dsproxy.ExecutionInfo;
import net.ttddyy.dsproxy.QueryInfo;
import net.ttddyy.dsproxy.listener.ChainListener;
import net.ttddyy.dsproxy.listener.QueryExecutionListener;
import net.ttddyy.dsproxy.proxy.ProxyConfig;
import net.ttddyy.dsproxy.proxy.ProxyJdbcObject;

import java.lang.ref.WeakReference;
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
    /**
     * Keyed by the SQL's own (cached) hash code and length, and confirmed by a 64-bit fingerprint, so the raw SQL,
     * which may contain literal secrets, is never strongly retained.
     */
    private final ConcurrentHashMap<Long, CachedNames> sqlNames = new ConcurrentHashMap<>();

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
            if (recordedByInnerProxy(executionInfo.getStatement())) return;

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

    /**
     * A routing or delegating DataSource bean wraps another DataSource bean, so both get a proxy reporting here.
     * The outer proxy then executes on the inner proxy's statement; only the inner one records the query.
     */
    private boolean recordedByInnerProxy(Object statement) {
        if (!(statement instanceof ProxyJdbcObject proxy)) return false;
        ProxyConfig config = proxy.getProxyConfig();
        return config != null && registeredIn(config.getQueryListener());
    }

    /** Whether this listener is {@code listener} or one of the listeners it chains. */
    public boolean registeredIn(QueryExecutionListener listener) {
        if (listener == this) return true;
        if (!(listener instanceof ChainListener chain)) return false;
        for (QueryExecutionListener chained : chain.getListeners()) {
            if (registeredIn(chained)) return true;
        }
        return false;
    }

    private SqlNames sqlNames(List<QueryInfo> queries) {
        QueryInfo single = queries != null && queries.size() == 1 ? queries.get(0) : null;
        String raw = single == null ? null : single.getQuery();
        if (raw == null || raw.length() > MAX_CACHED_SQL_LENGTH) return names(JdbcTraceSql.describe(queries), queries);
        Long key = ((long) raw.hashCode() << 32) | raw.length();
        CachedNames cached = sqlNames.get(key);
        if (cached != null) {
            // ORMs reuse the same String for a statement, which then needs no scan at all.
            if (cached.lastSeen.get() != raw) {
                if (cached.fingerprint != fingerprint(raw)) return names(JdbcTraceSql.describe(queries), queries);
                cached.lastSeen = new WeakReference<>(raw);
            }
            if (!cached.used) cached.used = true;
            return cached.names;
        }
        SqlNames names = names(JdbcTraceSql.describe(queries), queries);
        if (sqlNames.size() >= MAX_CACHED_SQL) sweep();
        if (sqlNames.size() < MAX_CACHED_SQL) sqlNames.putIfAbsent(key, new CachedNames(names, raw));
        return names;
    }

    /**
     * 64-bit FNV-1a over four interleaved lanes (independent multiply chains run in parallel), then a finalizer.
     * A collision would only lend one statement another's name, and also needs equal hash codes and lengths.
     */
    static long fingerprint(String sql) {
        final long prime = 0x100000001b3L;
        long a = 0xcbf29ce484222325L;
        long b = a ^ 1;
        long c = a ^ 2;
        long d = a ^ 3;
        int n = sql.length();
        int i = 0;
        for (; i + 3 < n; i += 4) {
            a = (a ^ sql.charAt(i)) * prime;
            b = (b ^ sql.charAt(i + 1)) * prime;
            c = (c ^ sql.charAt(i + 2)) * prime;
            d = (d ^ sql.charAt(i + 3)) * prime;
        }
        for (; i < n; i++) a = (a ^ sql.charAt(i)) * prime;
        long h = a ^ Long.rotateLeft(b, 16) ^ Long.rotateLeft(c, 32) ^ Long.rotateLeft(d, 48) ^ n;
        h = (h ^ (h >>> 33)) * 0xff51afd7ed558ccdL;
        h = (h ^ (h >>> 33)) * 0xc4ceb9fe1a85ec53L;
        return h ^ (h >>> 33);
    }

    private SqlNames names(String sql, List<QueryInfo> queries) {
        return new SqlNames(sql, "SQL " + JdbcTraceSql.commandOf(sql),
                operationStats == null ? null : JdbcTraceSql.statsName(queries, sql));
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
        final long fingerprint;
        volatile WeakReference<String> lastSeen;
        volatile boolean used;

        CachedNames(SqlNames names, String raw) {
            this.names = names;
            this.fingerprint = fingerprint(raw);
            this.lastSeen = new WeakReference<>(raw);
        }
    }

    boolean cached(String raw) {
        CachedNames cached = sqlNames.get(((long) raw.hashCode() << 32) | raw.length());
        return cached != null && cached.fingerprint == fingerprint(raw);
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
