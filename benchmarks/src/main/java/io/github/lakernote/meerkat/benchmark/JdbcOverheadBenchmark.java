package io.github.lakernote.meerkat.benchmark;

import io.github.lakernote.meerkat.integration.jdbc.internal.JdbcTraceQueryExecutionListener;
import io.github.lakernote.meerkat.metrics.internal.OperationStats;
import io.github.lakernote.meerkat.trace.SpanKind;
import io.github.lakernote.meerkat.trace.internal.TraceContext;
import io.github.lakernote.meerkat.trace.internal.TraceIds;
import net.ttddyy.dsproxy.ExecutionInfo;
import net.ttddyy.dsproxy.QueryInfo;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Cost the JDBC listener adds to one query: SQL normalization, the statistics name, a span when a Trace is active
 * and recording the call. The JDBC driver itself is not part of the measurement.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(2)
public class JdbcOverheadBenchmark {
    /** A typical prepared ORM query; {@code IN} lists and literals exercise the statistics-name rewrites. */
    private static final String SQL = "select o.id, o.status, o.total_amount, o.created_at from orders o "
            + "join customers c on c.id = o.customer_id where o.customer_id = ? and o.status in (?, ?, ?) "
            + "and o.created_at > ? order by o.created_at desc limit 20";

    static final int QUERIES = 10;

    private JdbcTraceQueryExecutionListener listener;
    private List<QueryInfo> queries;

    @Setup
    public void setUp() {
        listener = new JdbcTraceQueryExecutionListener(new OperationStats(1_000), () -> false);
        queries = List.of(new QueryInfo(SQL));
    }

    @TearDown(Level.Iteration)
    public void clearThreadState() {
        TraceContext.clear();
        TraceIds.clear();
    }

    /** Ten queries outside any Trace: only call statistics are recorded. */
    @Benchmark
    public void tenQueriesWithoutTrace() {
        for (int i = 0; i < QUERIES; i++) query();
    }

    /** An entry Trace with ten SQL spans; subtract {@link #emptyTrace} and divide by ten for the per-query cost. */
    @Benchmark
    public void tenQueriesInsideTrace() {
        TraceIds.putTraceId("0123456789abcdef0123456789abcdef");
        TraceContext.startRoot("GET /orders", SpanKind.HTTP);
        for (int i = 0; i < QUERIES; i++) query();
        TraceContext.stopRoot(Long.MAX_VALUE);
    }

    @Benchmark
    public void emptyTrace() {
        TraceIds.putTraceId("0123456789abcdef0123456789abcdef");
        TraceContext.startRoot("GET /orders", SpanKind.HTTP);
        TraceContext.stopRoot(Long.MAX_VALUE);
    }

    private void query() {
        ExecutionInfo info = new ExecutionInfo();
        listener.beforeQuery(info, queries);
        listener.afterQuery(info, queries);
    }
}
