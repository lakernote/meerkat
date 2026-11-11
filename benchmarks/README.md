# Benchmarks

JMH 基准，衡量 Starter 在业务线程上增加的开销。本模块不发布到 Maven Central。

```bash
mvn -pl benchmarks -am package -DskipTests
java -jar benchmarks/target/benchmarks.jar
```

只运行部分基准或缩短时间（结果仅用于快速检查）：

```bash
java -jar benchmarks/target/benchmarks.jar ObservationOverheadBenchmark -f 1 -wi 1 -i 3
```

| 基准 | 衡量内容 |
| --- | --- |
| `ObservationOverheadBenchmark` | 一次 `http.server.requests` 入口及 `dependencies` 次依赖 Observation 的完整生命周期；`baselineRequest` 使用空 Handler 作为对照 |
| `JdbcOverheadBenchmark` | JDBC 监听对一条 SQL 的开销（不含驱动本身）：10 条 SQL 在 Trace 外、在 Trace 内，以及空 Trace 对照 |
| `SpanOverheadBenchmark` | `TraceBlock` 在 Trace 内外的开销，以及 `TraceContextTaskDecorator` 装饰并执行任务的开销 |

`docs/guide.md`（Overhead）和 `docs/guide.zh-CN.md`（性能开销）记录了参考结果。修改采集路径后请重新运行并更新该表。
