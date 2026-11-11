package io.github.lakernote.meerkat.benchmark;

import io.github.lakernote.meerkat.trace.SpanKind;
import io.github.lakernote.meerkat.trace.TraceBlock;
import io.github.lakernote.meerkat.trace.TraceContextTaskDecorator;
import io.github.lakernote.meerkat.trace.internal.TraceContext;
import io.github.lakernote.meerkat.trace.internal.TraceIds;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/** Cost of explicit spans and of executor task propagation, with and without an active Trace. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(2)
public class SpanOverheadBenchmark {
    private static final int SPANS_PER_TRACE = 10;

    private final TraceContextTaskDecorator decorator = new TraceContextTaskDecorator();

    @TearDown(Level.Iteration)
    public void clearThreadState() {
        TraceContext.clear();
        TraceIds.clear();
    }

    /** {@code TraceBlock} outside any Trace: the code runs without recording. */
    @Benchmark
    public void traceBlockWithoutTrace(Blackhole blackhole) {
        TraceBlock.run("work", () -> blackhole.consume(1));
    }

    /** A root Trace containing ten {@code TraceBlock} spans; divide by ten for the per-span cost. */
    @Benchmark
    public void rootWithTenTraceBlocks(Blackhole blackhole) {
        TraceIds.putTraceId("0123456789abcdef0123456789abcdef");
        TraceContext.startRoot("GET /orders", SpanKind.HTTP);
        for (int i = 0; i < SPANS_PER_TRACE; i++) {
            TraceBlock.run("work", () -> blackhole.consume(1));
        }
        TraceContext.stopRoot(Long.MAX_VALUE);
    }

    /** Decorating and running a task with no Trace to propagate. */
    @Benchmark
    public void decoratedTaskWithoutTrace(Blackhole blackhole) {
        decorator.decorate(() -> blackhole.consume(1)).run();
    }

    /** Decorating and running a task inside an active Trace, which records an executor.task span. */
    @Benchmark
    public void decoratedTaskInsideTrace(Blackhole blackhole) {
        TraceIds.putTraceId("0123456789abcdef0123456789abcdef");
        TraceContext.startRoot("GET /orders", SpanKind.HTTP);
        decorator.decorate(() -> blackhole.consume(1)).run();
        TraceContext.stopRoot(Long.MAX_VALUE);
    }
}
