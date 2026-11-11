# 接入与配置

[English](guide.md) | 简体中文

引入与应用 Spring Boot 主版本匹配的 Starter 后，Meerkat 会自动注册 Trace 采集、运行指标和嵌入页面。通常不需要额外配置。Starter 依赖 `spring-boot-starter-actuator`，资源指标直接复用 Spring Boot 已绑定的 Micrometer 指标；它只额外加入一个只接收固定指标的注册表，不替换应用已有的 `MeterRegistry`、`ObservationRegistry`，也不影响应用原有的指标导出。

## 运行环境

- Java 17 及以上；Spring Boot 3.5+ 与 Spring Boot 4.0+ 分别使用对应的 Starter（CI 同时以最低版本 3.5.0、4.0.0 构建测试）。
- Web 应用须为 Servlet 栈（Spring MVC）。Trace 上下文绑定在线程上，与"一个请求由一个线程处理"的 Servlet 模型匹配。
- **不支持 WebFlux 等响应式应用。** 引入 Starter 后应用仍可正常启动，但响应式 HTTP 请求不会生成入口 Trace，页面和查询 API 也不会注册；在响应式链路中切换线程的调用无法关联到同一 Trace。
- 非 Web 应用（如只消费 Kafka 或只运行定时任务）可以采集 Trace 和运行指标，但没有页面可以查看。
- 暂不支持 GraalVM Native Image；组件未提供 AOT 运行时提示，也未在 Native Image 下验证。

### 兼容性验证

每次提交都会在 CI 中跑以下组合；中间件测试使用 Testcontainers 启动真实服务，本地没有 Docker 时自动跳过。

| 维度 | 验证范围 |
| --- | --- |
| JDK | 17、21、25 |
| Spring Boot | 3.5.0（最低）、3.5.x 最新；4.0.0（最低）、4.1.x 最新 |
| HTTP 客户端 | `RestTemplate`、`RestClient`、`OkHttpClient` Bean：客户端 Span 与 Trace ID 传播 |
| Kafka | 真实 Kafka（`apache/kafka` 4.1）：生产 Span、消费入口 Trace 与生产端 Trace ID 一致 |
| Redis | 真实 Redis 7.4（Lettuce）：命令 Span 挂在请求 Trace 下；Redisson 另有专门的兼容性测试 |
| JDBC | H2 + HikariCP：SQL Span、参数脱敏、保留具体数据源类型 |
| Micrometer Tracing | Brave：根请求与 Micrometer 使用同一 Trace ID，接受上游 `traceparent` |

## 自动采集范围

| 场景 | 采集内容 | 说明 |
| --- | --- | --- |
| HTTP 入口 | 路由、状态、耗时、错误、入口线程 CPU 时间和内存分配量 | 慢阈值分类针对入口；CPU 时间和分配量是入口线程累计值，不是进程使用率或当前堆占用，不包含子线程 |
| 定时任务、Kafka Listener | 入口执行耗时和错误 | Starter 默认为 `KafkaTemplate` 和 Listener Factory 打开 Spring Kafka Observation，生产/消费自动关联；显式配置 `spring.kafka.*.observation-enabled=false` 时保持关闭 |
| JDBC、事务、MyBatis | SQL 模板、事务范围、耗时和错误 | SQL 会清理字面量和注释，不记录绑定参数 |
| Redis | Lettuce Observation 命令 Span；Spring Data Redis 经 Redisson `RedisConnectionFactory` 执行的命令 Span | 直接调用 Redisson `RLock`、`RMap` 等 API 不属于这组连接工厂命令采集 |
| 远程 HTTP | 客户端调用 Span，并在请求头传播 Trace ID | `RestTemplate`、`RestClient`（含直接 `new RestTemplate()`、`RestClient.create()` 声明的 Bean）、`@FeignClient`、`OkHttpClient` Bean 自动接入；方法内临时 new 出来的客户端、JDK `HttpClient` 等不是 Spring Bean 的调用不会被采集 |
| 异步线程池 | 任务耗时、异常及活动 Trace 上下文传播 | Spring Boot 默认线程池及应用声明为 Bean 的 `ThreadPoolTaskExecutor`、`SimpleAsyncTaskExecutor` 自动接入；直接使用 JDK `ExecutorService` 需手动包装；请求已返回后继续运行的后台任务不会延长已完成的入口 Trace |
| 调用统计 | 每个 HTTP 接口、SQL 模板、远程调用、Redis 命令、定时任务和 Kafka Topic 的次数、错误、平均/P95/最大耗时和总耗时，及其随时间的趋势；HTTP、定时任务和 Kafka 消费入口另有平均 CPU 时间和平均内存分配量 | 不论是否慢或出错都会计入；与 Trace 共用采集点，见[运行指标](#运行指标) |
| 资源指标 | Spring Boot 已绑定的固定一组指标：JVM 内存与 GC、CPU、线程、Hikari 连接池、线程池、Tomcat/Jetty、磁盘 | 每分钟记录一次，饱和度指标每 10 秒采样，见[运行指标](#运行指标) |

自定义业务边界可使用 `TraceBlock` 或 `@TraceSpan`。组件不会扫描所有业务 Service 自动创建 Span。

Trace 详情页和慢 Trace 日志会把连续重复、正常完成的同一调用（类型、线程、名称和 SQL/标签都相同）合并为一行，显示次数、合计和最长耗时；重复 10 次以上的会高亮，便于发现 N+1 查询。调用排行展开一行后可跳转到相关的慢/错误 Trace。

JDBC 采集会使用保留原数据源具体类型的代理，因此按 `HikariDataSource` 等具体类型注入及厂商扩展方法仍可使用。若数据源类型为 `final`、包含无法安全代理的公开 `final` 方法，或运行环境拒绝创建类代理，组件会记录警告并跳过该数据源的 JDBC Trace，不会用不兼容的包装对象替换业务 Bean。应用有多个数据源时，SQL 节点前会加上数据源的 Bean 名称（如 `orderDataSource · select …`），只有一个时省略。

## 默认配置

下面列出常用配置；未列出的配置保持 Starter 默认值。所有配置均可省略。

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `meerkat.enabled` | `true` | Starter 总开关 |
| `meerkat.storage.directory` | `./data/meerkat/{spring.application.name}` | 本地存储目录，存放诊断 Trace 分段文件 `traces/` 和运行指标历史 `metrics/`；相对路径基于工作目录，未设置应用名时使用 `application` |
| `meerkat.trace.enabled` | `true` | Trace 采集及上下文传播 |
| `meerkat.trace.header-name` | `X-Trace-Id` | HTTP 与受支持消息/客户端使用的 Trace ID 名称 |
| `meerkat.trace.http-slow-threshold` | `500ms` | HTTP 入口慢阈值 |
| `meerkat.trace.job-slow-threshold` | `1s` | 定时任务入口慢阈值 |
| `meerkat.trace.kafka-consumer-slow-threshold` | `1s` | Kafka 消费入口慢阈值 |
| `meerkat.trace.cpu-threshold` | `500ms` | 入口线程 CPU 时间资源告警阈值 |
| `meerkat.trace.allocated-memory-threshold` | `50MB` | 入口线程累计内存分配量资源告警阈值 |
| `meerkat.trace.retention` | `7d` | 诊断 Trace 保留天数，整天，范围 `1d`～`365d` |
| `meerkat.trace.max-storage-size` | `128MB` | 诊断 Trace 磁盘占用上限，超出时删除最旧的分段文件 |
| `meerkat.trace.max-memory-size` | `32MB` | 内存中缓存最新 Trace 完整调用树的堆内存上限（按估算大小淘汰最旧的）；更早的记录在打开详情时从磁盘读取，存储不可用时直接丢弃 |
| `meerkat.metrics.enabled` | `true` | 运行指标（调用统计与资源指标）总开关 |
| `meerkat.metrics.max-operations` | `500` | 所有类别合计最多统计的操作数，超出后新操作按类别并入“其他”行；1 小时没有调用的操作会释放名额 |
| `meerkat.metrics.retention` | `30d` | 指标历史保留时长，须为整天数，范围 `1d` 至 `365d` |
| `meerkat.metrics.max-storage-size` | `64MB` | 指标历史文件的磁盘上限，超出后删除最早的日期 |
| `meerkat.web.enabled` | `true` | 页面和查询 API 开关 |

示例：

```yaml
meerkat:
  metrics:
    retention: 30d
    max-storage-size: 64MB
  trace:
    http-slow-threshold: 500ms
    job-slow-threshold: 1s
    kafka-consumer-slow-threshold: 1s
    cpu-threshold: 500ms
    allocated-memory-threshold: 50MB
```

时长配置使用 Spring Boot 的 `Duration` 格式（如 `500ms`、`2s`、`30d`）；阈值写纯数字时按毫秒解析，`retention` 写纯数字时按天解析。四个时长阈值设为 `0` 或负数、`allocated-memory-threshold` 设为 `0B`，可关闭对应的慢操作或资源告警触发。

存储目录由一个实例独占：启动时会在目录中创建 `.lock` 并持有操作系统文件锁，应用关闭时释放。同一台机器上第二个指向同一目录的实例（包括同一应用的多个副本）不会写入该目录，而是记录警告，并把诊断 Trace 和指标历史只保存在内存中，宿主应用照常启动。目录无法创建或不可写时同样降级为仅内存。同机部署多个副本时，请为每个实例配置不同的 `storage.directory`，例如在路径中加入实例编号。

只有慢调用、错误及 CPU 时间或内存分配量超阈值的入口（HTTP、定时任务、Kafka 消费）会被保存。诊断 Trace 以 JSON Lines 追加写入 `traces/traces-yyyyMMdd-N.jsonl`，按天滚动，单个分段达到 `max-storage-size` 的 1/8（256KB～8MB）时也会滚动。超过 `retention` 的整天分段、以及总占用超过 `max-storage-size` 时最旧的分段会被整文件删除，不改写正在使用的文件。重启后在后台线程扫描分段重建轻量索引（不含调用树，最多 5 万条、约 16MB 堆内存，超出时列表中只保留最新的），重建期间页面可用，旧记录逐步出现；打开详情时再从磁盘读取完整记录。单条记录编码后超过一个分段大小时只保留在内存中。

后台序列化和文件 I/O 不持有业务记录入口的锁，写入队列满时丢弃该条持久化而不阻塞请求、Kafka 消费或任务线程。若运行时存储 I/O 失败，Trace 存储会降级到仅内存模式直到应用重启，`/api/meerkat/traces/storage` 的 `persistent` 会返回 `false`。

Trace 查询 API（均为 `GET`，前缀 `/api/meerkat`）：

- `/traces?kind=all|slow|error|resource&source=HTTP|JOB|KAFKA_CONSUMER&q=&before=&limit=50`：按完成时间倒序分页，返回 `items`（不含调用树）、`nextCursor`（作为下一页的 `before`）、`total` 和 `scanLimited`。`q` 匹配入口名称、Trace ID、错误信息、线程名以及调用树中的 SQL、远程地址等节点；文本搜索需要扫描磁盘，每次请求最多读取 32MB，达到上限时 `scanLimited` 为 `true`，用 `nextCursor` 继续搜索，此时 `total` 为 `-1`。`limit` 最大 200。
- `/traces/{traceId}?source=`：完整调用树，不存在时返回 404。
- `/traces/storage`：`persistent`、`storageBytes`、`maxStorageBytes`、`retentionDays`、`traceCount`、`indexing`。
- `/errors?window=1h|24h|7d&source=&limit=50`：把时间范围内失败的 Trace 按异常类型和消息归并，按次数倒序（最多 200 组）。每组包含 `errorType`、`message`（数字、ID、引号内的值已被替换）、`sampleMessage`、`count`、`firstSeen`、`lastSeen`、`newInWindow`（时间范围之前没有保留过同类失败）、出现最多的入口 `entries`、`latestTraceId` 和 24 段的分布 `trend`。

Trace 页面的 **异常聚合** 面板展示同样的数据：同类失败合成一行并给出次数，时间范围内才开始出现的（例如发布后新出现的）标为"新出现"。没有异常对象的失败（例如只有 5xx 状态码）按入口分别归并。分组在请求时基于 Trace 索引计算，不增加业务请求开销，重启后依然可用；范围是 `retention` 和 `max-storage-size` 仍保留的失败记录。

`retention`、`max-storage-size` 等无效配置会阻止应用启动，以便尽早暴露配置错误。运行时因目录权限、只读文件系统、磁盘 I/O 导致指标历史写入失败时，组件会记录警告并改为只保存在内存，不会中断宿主应用；页面会显示“存储降级”。修复存储问题后需要重启应用以恢复历史保存。

## 运行指标

运行指标回答“这个实例整体怎么样、哪个操作最慢最多”，Trace 诊断回答“这一次请求慢在哪里”。两者共用采集点，但各自独立：指标覆盖全部调用，Trace 只保留慢、错或资源超阈值的请求。运行指标有两个页面：

- **调用排行**（`/meerkat/stats/`）：按类别列出每个操作的调用次数、错误数和错误率、平均耗时、P95、最大耗时、总耗时及其在本类别中的占比，点击列头排序；点击一行展开该操作的调用次数和耗时趋势。HTTP、定时任务和 Kafka 消费入口还显示每次调用平均消耗的入口线程 CPU 时间和内存分配量，用来找出“耗 CPU 的接口”和“分配大量对象、引起频繁 GC 的接口”；这两个值复用入口本来就采集的数据，不增加开销，转为异步处理的请求不计入。适合回答“哪个接口最耗时”“哪条 SQL 调用最多”“哪个远程服务最常出错”“哪个接口最吃 CPU 或内存”。
- **指标趋势**（`/meerkat/metrics/`）：先是服务概览（HTTP 请求量、错误率、耗时和依赖调用耗时，复用调用统计），再是 CPU、内存、GC、线程与线程池、Hikari 连接池、Web 连接与流量、磁盘和运行时长等资源曲线，便于从现象追到原因；单个操作的调用趋势在调用排行中点开查看。

### 调用统计

| 类别 | 统计名称 | 计为错误 |
| --- | --- | --- |
| HTTP 接口 | 路由模板 · 方法，例如 `/orders/{id} · GET` | 抛出异常或状态码 ≥ 500 |
| SQL | 去除字面量后的 SQL；`IN (?, ?, ...)` 合并为 `IN (?…)`，多行 `VALUES` 合并为首行 | JDBC 抛出异常 |
| 远程调用 | 方法 + 目标主机 + URI 模板 | 抛出异常或状态码 ≥ 500 |
| Redis | 命令名 | 抛出异常或命令返回错误 |
| 定时任务 / Kafka 消费 / Kafka 发送 | 任务名 / Topic · Listener ID / 目标 Topic | 抛出异常 |

- 排行时间范围可选“最近 1 小时”“今天”“最近 7 天”和“最近 30 天”，日期按 JVM 默认时区划分。趋势的分辨率：最近 1 小时每分钟一个点，24 小时每 10 分钟，7 天和 30 天每小时。
- P95 由对数分桶直方图估算，误差约 ±20%，且不会超过最大值；分钟级趋势不提供 P95。
- SQL 统计不依赖活动 Trace，启动阶段和后台线程执行的 SQL 也会计入；Spring 的 `spring.jdbc` Observation 不重复计数。关闭 `trace.enabled` 后除 SQL 外不再统计，页面会提示。
- 统计只用 Micrometer 定义的低基数字段和 SQL 模板命名，Kafka offset、原始 URL 等每次不同的值不会产生新行；远程调用没有 URI 模板时，统计名会去掉 URL 中的 query string 和 `#` 片段。操作数达到 `metrics.max-operations` 后，新操作并入该类别的“（其他：超出统计数量上限）”行；1 小时没有调用的操作会从内存移除并释放名额，已保存的历史不受影响。
- 每次调用的统计开销约 50 ns，无锁实现；内存中保留最近约 1 小时，每个操作约 9 KB，默认 500 个操作全部占满时约 4.5 MB。

### 资源指标

资源指标来自 Spring Boot 已经绑定的 Micrometer 指标，组件不自己读取 JMX，也不重复埋点。Starter 注册一个只接收下列固定指标的注册表（不可配置），每分钟记录一次：

| 指标 | 曲线拆分 |
| --- | --- |
| `jvm.memory.used/committed/max` | 按 `heap` / `nonheap` |
| `jvm.buffer.memory.used` | 按缓冲池（`direct` / `mapped`） |
| `jvm.gc.pause`（次数、总耗时和最长单次停顿） | 按 GC 动作（Young / Old/Full，其他收集器显示原名） |
| `jvm.gc.live.data.size/max.data.size`、`jvm.memory.usage.after.gc`、`jvm.gc.overhead`、`jvm.gc.memory.allocated/promoted` | 合计 |
| `jvm.threads.live/daemon/peak`、`jvm.classes.loaded`、`process.uptime`、`process.files.open/max` | 合计 |
| `process.cpu.usage`、`system.cpu.usage`、`system.cpu.count`、`system.load.average.1m` | 合计 |
| `disk.free/total` | 按路径 |
| `hikaricp.connections.active/idle/pending/max/timeout`、`hikaricp.connections.acquire/usage`（平均和最大） | 按连接池 |
| `executor.active/queued/queue.remaining/pool.size/pool.core/pool.max/completed` | 按线程池 |
| `meerkat.executor.rejected`（`ThreadPoolTaskExecutor` Bean 拒绝的任务数，含 CallerRuns 等策略在调用方执行的任务；不改变拒绝策略，关闭后的拒绝不计入） | 按线程池 |
| `http.server.requests`（复用 Spring Boot 自带指标，排除 Meerkat 自身页面） | 按状态类别：2xx/3xx/4xx/429/5xx |
| `tomcat.threads.*`、`tomcat.connections.current/keepalive.current/config.max`、`tomcat.global.received/sent`、`tomcat.sessions.active.current`、`jetty.threads.*`、`jetty.connections.current/bytes.in/bytes.out` | 合计 |

- 饱和度指标（CPU 使用率、忙碌线程、活跃和等待连接、线程池活跃与排队、当前 HTTP 连接）每 10 秒读取一次，每分钟保存平均值和峰值，短时尖峰不会漏掉；其余 Gauge 每分钟读取一次。
- 计数器和计时器记录每分钟的增量；计时器同时记录这一分钟内最长的单次耗时。
- 较长时间范围和压缩后的历史按含义合并：次数、耗时和流量求和（图表显示每个点内的量），峰值取最大，上限取最新值，其余 Gauge 取平均。
- 每个指标最多按 8 个连接池、线程池或路径拆分，其余合并为“其他”曲线。指标清单固定，曲线数有较小的静态上限，不会静默丢弃。
- 没有上限的线程池、队列和资源（上限为 `-1` 或 `Integer.MAX_VALUE`）不绘制上限或容量曲线。
- HTTP 错误率统计异常和 5xx 响应。4xx 不算错误，但会显示在“HTTP 响应状态”图中，其中 429 单独列出，通常表示被限流。该图依赖 Spring Boot Actuator 默认开启的 `http.server.requests`。
- Spring Boot 只有在 Tomcat 注册 MBean 时才绑定 Tomcat 线程指标，因此 Starter 默认把 `server.tomcat.mbeanregistry.enabled` 设为 `true`（优先级最低，应用显式配置时以应用为准；关闭 `metrics.enabled` 时不设置）。开启后 Tomcat 会多占用少量内存。

### 存储

调用统计每 10 分钟一片、资源指标每分钟一个样本，追加写入存储目录下的 `metrics/operations-yyyyMMdd.tsv` 和 `metrics/resources-yyyyMMdd.tsv`。应用正常关闭时会写入当前未满的分片，重启后各时间范围都保留：启动时读回最近 1 小时的分片，分钟曲线上重启前的部分按 10 分钟均摊；进程被强制杀死时最多丢失最近约 10 分钟的调用统计。

- 两天前的文件会压缩一次：调用统计合并为每小时一行，资源指标按上述规则合并为每 10 分钟一个点，因此 30 天的数据通常只有几 MB。
- 超过 `metrics.retention` 的日期会被删除；总大小超过 `metrics.max-storage-size` 时按天删除最早的数据。
- 存储目录不可用（被其他实例占用或不可写）时只保存在内存，长范围只包含最近约 1 小时，页面会提示。
- 每个实例各自保存，不跨实例汇总。`canClear()` 允许时调用排行页显示“重置统计”，会清空内存中和已保存的调用统计，资源指标历史保留。

查询 API（均为 `GET`，前缀 `/api/meerkat/metrics`）：`/operations?window=1h|today|7d|30d`、`/operations/trend?category=HTTP&name=...&period=1h|24h|7d|30d`、`/operations/trends?period=...`、`/resources?period=...`、`/storage`；重置为 `POST /operations/reset`。

## 实时诊断

页面 `/meerkat/live/` 回答“现在正在发生什么”，适合接口卡住、CPU 突然升高、线程池被占满时排查。数据在打开页面或点击刷新时读取，不在后台采样，也不写入磁盘。

- **进行中请求：** 列出正在执行的 HTTP、定时任务和 Kafka 消费入口，按已执行时间排序，显示入口线程已消耗的 CPU 时间、当前正在执行的最内层步骤（例如某条 SQL 或远程调用及其已执行时间）和线程状态；展开一行查看该线程此刻的堆栈和正在等待的锁。默认每 5 秒刷新。请求转为异步处理、入口线程已返回后，只显示耗时和当前步骤，不显示线程堆栈。最多显示 200 个，诊断页面自身的请求不计入。
- **线程：** 对全部平台线程采样 1 秒，按 CPU 使用率（100% 表示占满一个核心）排序，显示状态、累计 CPU、等待的锁及持有者，展开查看堆栈；检测到死锁时在顶部列出相关线程。勾选“合并相同堆栈”可以把状态和堆栈相同的线程合为一行，便于发现大量线程卡在同一处。可下载 jstack 格式的线程 Dump 文本。虚拟线程不在列表中。

每个入口只在开始和结束时各做一次并发集合的增删，不影响请求耗时；堆栈只在页面请求时读取。线程采样同一时间只允许一个，正在采样时再次请求返回 429。

查询 API（均为 `GET`，前缀 `/api/meerkat/live`）：`/requests`、`/connections`、`/threads?sampleMs=0..5000`（默认 1000）、`/threads/dump`（`text/plain` 附件；同一时间只生成一份，且两次间隔至少 5 秒，否则返回 429）。

## 流量抓取

当某个请求出问题、只看 Trace 树还看不出原因时，可以用流量抓取记录它的完整现场：入口请求和响应（请求头和请求体）、每条 SQL 及绑定参数和查询结果或更新行数、Redis 命令及返回值、调用下游 HTTP（RestTemplate、RestClient、Feign）的请求和响应，全部用 Trace ID 串成一条记录。

没有单独的开关：谁能抓取由 `ObservabilityAccessPolicy#canCapture()` 决定，内置策略拒绝，所以需要在自定义策略中授权后才能使用（见[页面和 API 访问](#页面和-api-访问)）。在页面开始抓取之前不记录任何数据。可选的限制：

```yaml
meerkat:
  capture:
    max-body-size: 64KB    # 每个请求体/响应体
    max-result-rows: 20    # 每条查询保留的结果行数
    max-value-chars: 16384 # 每个记录值
    max-trace-chars: 1000000 # 每个被抓取的请求
    max-records: 100       # 内存中保留的抓取记录数
    max-stored-size: 32MB  # 所有抓取记录占用的堆上限，超出先丢最旧的
    retention: 2h
    max-duration: 1h       # 单次抓取最长时间
```

打开 `/meerkat/capture/`，填写规则：HTTP 方法、Ant 风格路径（如 `/api/orders/**`），以及可选的请求头和值（例如反馈问题的用户 ID），再选择抓取次数和最长时间。达到次数或时间后自动停止。同时最多 10 个抓取任务；开始和停止都会以 WARN 级别记录操作人。

> [!WARNING]
> 抓取的数据**不做脱敏**，可能包含密码、令牌和个人信息。只把 `canCapture()` 授权给可以查看这些数据的人，并且每次抓取尽量短。

注意事项：

- 只抓取入口为 HTTP 的请求。请求体和响应体只记录应用实际读写的内容，最多 `max-body-size`，不会提前读取；文件上传和下载（图片、octet-stream 等二进制类型，`multipart/*`，或带 `Content-Disposition: attachment` 的响应）不复制内容，只记录一个标记，例如 `[file upload, multipart/form-data, 10485760 bytes; content not recorded]`。入口请求和 `RestTemplate`/`RestClient`/OpenFeign 出站调用都按这个规则处理。通过 `getParameter` 读取的表单提交记录为参数。
- SQL 结果只记录前 `max-result-rows` 行中应用读取过的列，LOB 和流类型的列不记录；`·` 表示应用没有读取该列。
- 抓取记录只保存在内存中，不写磁盘，也不会出现在 Trace 列表和 Trace API 中。被抓取的请求如果本身慢或失败，仍会像其他请求一样出现在 Trace 列表中。
- 没有进行中的抓取时，每个请求只多一次 volatile 读；抓取期间只有匹配的请求需要复制数据。Meerkat 会启用 datasource-proxy 的结果集代理（未被抓取的查询仍直接返回驱动的结果集），并包装 `RestTemplate`/`RestClient` Bean 的请求工厂而不是添加拦截器，所以请求体仍然流式发送，上传不会被缓冲到内存。不在抓取中的调用拿到的是原始请求。启动后再替换请求工厂的 `RestClient.Builder` 不会被抓取。

API（前缀 `/api/meerkat/capture`）：`GET /status`、`POST /sessions`（JSON 请求体 `{"method","pathPattern","headerName","headerValue","maxCount","durationSeconds"}`）、`POST /sessions/{id}/stop`、`GET /records?sessionId=`、`GET /records/{id}`、`POST /records/clear`。除 `/status` 外都需要同时通过 `canView()` 和 `canCapture()`。

## 工具箱

工具箱页面 `/meerkat/tools/` 按需提供几个常用诊断工具，不使用 Java Agent，也不做字节码增强。没有人启动工具时不会在后台运行任何东西；每个工具都限制了时间和内存，同类重操作同一时间只能运行一个（再次请求返回 429）。

| 工具 | 作用 | 权限 |
| --- | --- | --- |
| 日志级别 | 列出 Logger 的配置级别和生效级别（通过 Spring Boot 的 `LoggingSystem`，Logback 和 Log4j2 都支持），临时修改级别 1–60 分钟，到期自动恢复为之前的配置级别，也可以点“立即恢复”。同时最多 50 个临时级别；修改会以 WARN 级别记录操作人 | 查看：`canView()`；修改：`canOperate()` |
| CPU 火焰图 | 每 10–100 毫秒（默认 20 毫秒）采样 RUNNABLE 线程的调用栈，最长 60 秒（默认 10 秒），生成可交互的火焰图（点击放大，悬停显示样本数和占比）和按自身耗时排序的热点方法。调用树最多 20,000 个节点、每个栈 128 帧；可以跳过空闲的原生等待（socket accept、epoll 等），也可以包含阻塞/等待中的线程。可下载 collapsed 格式，供 flamegraph.pl 和 speedscope 使用 | `canView()` |
| 堆直方图 | 执行 `GC.class_histogram`（只统计存活对象，**会触发一次 Full GC 停顿**），按占用字节显示前 N 个类；再次执行会显示与上一次相比的增长，便于排查内存泄漏。只保留上一次结果（最多 500 行），两次间隔至少 10 秒 | `canOperate()` |
| 堆转储 | 检查磁盘剩余空间后，把存活对象的堆转储（`HotSpotDiagnosticMXBean.dumpHeap`）写到临时目录，以下载方式返回并删除文件；如果进程在下载中途退出，残留文件超过一天后会在下一次堆转储时删除。**会暂停应用**，文件大小可能接近堆大小 | `canOperate()` |
| 配置来源 | 输入属性名（或按名称搜索，最多 200 条），显示解析占位符后的生效值，以及按优先级排列的所有定义了该属性的配置来源，并标出生效的那个。值**不做脱敏** | `canOperate()` |
| 类来源 | 输入类名，显示应用类加载器能看到的所有副本、实际加载的类来自哪里以及类加载器链。“扫描重复类”会读取类路径上的 jar 和目录（类加载器暴露时也包括 Spring Boot 可执行 jar 中的嵌套 jar），列出出现在多个位置的类，并按 jar 对汇总；发现 500 个重复类或运行 20 秒后停止 | `canView()` |

```yaml
meerkat:
  tools:
    enabled: true                  # false 时关闭整个工具箱 API（返回 404）
    log-level-max-duration: 1h     # 临时日志级别的最长时间，最多 1 小时
    heap-histogram-interval: 10s   # 两次堆直方图的最短间隔
```

> [!WARNING]
> `canOperate()` 默认拒绝，内置策略也不开放：堆转储和配置值会暴露密钥，堆直方图和堆转储会暂停应用，修改日志级别会改变运行时行为。只授予可信的运维人员。

注意事项：

- CPU 采样只能看到 `ThreadMXBean` 返回的调用栈，因此存在安全点偏差，也看不到原生帧，适合定位热点而不是精确计算耗时。采样本身也消耗 CPU，结果中会显示采样耗时。
- 下载堆转储时文件会先保存在浏览器内存中；堆很大时建议在主机上执行 `jcmd <pid> GC.heap_dump`。
- 扫描重复类只读取类路径条目，不加载类；无法打开的条目会列为“无法读取”，不会导致扫描失败。

API（前缀 `/api/meerkat/tools`）：`GET /status`；`GET /loggers?q=&limit=`、`POST /loggers/overrides`（JSON `{"logger","level","durationSeconds"}`）、`POST /loggers/overrides/revert?logger=`；`POST /profiler`（JSON `{"durationSeconds","intervalMs","includeBlocked","excludeIdle","groupByThread"}`）、`GET /profiler`、`GET /profiler/{id}`、`POST /profiler/{id}/stop`、`GET /profiler/{id}/collapsed`；`POST /heap/histogram?limit=`、`POST /heap/dump`；`GET /config?name=`、`GET /config/search?q=`；`GET /classes?name=`、`POST /classes/duplicates`。

## 页面和 API 访问

页面路径为 `/meerkat/`，调用排行页面为 `/meerkat/stats/`，指标趋势页面为 `/meerkat/metrics/`，实时诊断页面为 `/meerkat/live/`，流量抓取页面为 `/meerkat/capture/`，工具箱页面为 `/meerkat/tools/`，查询 API 位于 `/api/meerkat/`。页面按浏览器语言显示中文或英文，可在顶部栏切换，选择保存在浏览器中。组件不提供用户名、密码或登录系统，认证由应用现有的 Spring Security、Servlet 容器或网关负责。

API 授权由 `ObservabilityAccessPolicy` 决定，应用定义该类型的 Bean 即可替换默认实现：

| 方法 | 控制范围 | 默认 |
| --- | --- | --- |
| `canView()` | 查询 Trace、Metrics、存储状态和当前权限 | `true`，允许所有请求 |
| `canClear()` | 清空 Trace 历史（`POST /api/meerkat/traces/clear`）和重置调用统计（`POST /api/meerkat/metrics/operations/reset`），并且需要同时通过 `canView()` | `false`，拒绝所有请求；页面隐藏“清空历史”和“重置统计”按钮 |
| `canCapture()` | 流量抓取：开始、停止抓取和读取抓取记录（`/api/meerkat/capture/**`），并且需要同时通过 `canView()` | 默认拒绝（内置策略同样拒绝），覆盖 `canCapture()` 后允许；这是流量抓取唯一的开关 |
| `canOperate()` | 工具箱中的敏感操作：修改日志级别、堆直方图（Full GC）、下载堆转储和读取配置值（`/api/meerkat/tools/**`），并且需要同时通过 `canView()` | `false`，拒绝所有请求，内置策略也不开放；工具箱页面禁用这些操作 |

`canClear()` 是默认方法，只需要限制查看时可以直接用 lambda，清空历史仍保持关闭：

```java
@Bean
ObservabilityAccessPolicy observabilityAccessPolicy() {
    return () -> hasAnyRole("ROLE_DEVELOPER", "ROLE_OPS");
}
```

**默认情况下任何能访问应用端口的人都可以查看 Trace 和 Metrics。** 生产环境务必由应用的 Security Filter Chain 或网关保护 `/meerkat/**` 和 `/api/meerkat/**`，或自定义 `canView()`。两个方法都在请求线程上调用，可以读取 Spring Security 上下文或当前请求。例如只允许开发和运维角色查看、只允许运维角色清空：

```java
@Bean
ObservabilityAccessPolicy observabilityAccessPolicy() {
    return new ObservabilityAccessPolicy() {
        @Override
        public boolean canView() {
            return hasAnyRole("ROLE_DEVELOPER", "ROLE_OPS");
        }

        @Override
        public boolean canClear() {
            return hasAnyRole("ROLE_OPS");
        }
    };
}

private static boolean hasAnyRole(String... roles) {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    return auth != null && auth.getAuthorities().stream()
            .anyMatch(authority -> List.of(roles).contains(authority.getAuthority()));
}
```

如果应用启用了 Spring Security 的 CSRF 防护，清空历史的 POST 请求需要 CSRF 令牌：使用 `CookieCsrfTokenRepository` 时页面会自动读取 `XSRF-TOKEN` Cookie 并回传 `X-XSRF-TOKEN` 请求头，否则需要对该路径单独配置 CSRF 策略。

## 日志中的 Trace ID

当前 Trace ID 写在 SLF4J MDC 的 `meerkatTraceId` 键中，可在日志格式中用 `%X{meerkatTraceId}` 输出。Spring Boot 可直接配置：

```yaml
logging:
  pattern:
    correlation: "[%X{meerkatTraceId:-}] "
```

MDC 键名与 Micrometer Tracing 的 `traceId`、`spanId` 不同，但取值会对齐：应用同时接入 Micrometer Tracing（OpenTelemetry 或 Brave）时，根请求、消息消费和定时任务直接沿用 Micrometer Tracing 的 Trace ID，`%X{traceId}` 与 `%X{meerkatTraceId}` 输出相同的值，在 Zipkin、Jaeger、Tempo 中可用同一个 ID 检索。代码中读取当前 ID 请使用 `TraceIdContext.currentTraceId()`。

入站 Trace ID 的优先级依次为：

1. `meerkat.trace.header-name` 指定的请求头（默认 `X-Trace-Id`）；
2. W3C `traceparent` 请求头中的 trace-id；
3. Micrometer Tracing 当前 Span 的 Trace ID；
4. 以上都没有时，新生成一个。

出站只写 `X-Trace-Id`。`traceparent`、B3 等标准头由 Micrometer Tracing 负责写入，Meerkat 不重复写，以免改动下游服务的采样决策。SkyWalking 的 `sw8` 头目前不识别。

## Kafka 与线程池

Starter 会为 Spring 容器中的 `KafkaTemplate` 和 Listener Factory 打开 Observation，无需配置 `spring.kafka.template/listener.observation-enabled`；显式设为 `false` 时尊重应用配置。默认 Listener Factory 会自动接入 Trace ID 拦截器；应用使用自定义 `RecordInterceptor` 时，需要按应用现有配置组合 `TraceIdRecordInterceptor`。批量 Listener、自定义消费者、Kafka Streams 不在自动覆盖范围内。

Spring Boot 默认线程池（`applicationTaskExecutor`，`@Async` 默认使用）以及应用声明为 Bean 的 `ThreadPoolTaskExecutor`、`SimpleAsyncTaskExecutor` 会自动应用 `TraceContextTaskDecorator`；线程池已设置自己的 `TaskDecorator` 时，两者以 `CompositeTaskDecorator` 组合，原装饰器先执行。调度器（`ThreadPoolTaskScheduler`）不做装饰，`@Scheduled` 任务由定时任务入口记录。

不是 Spring Bean 的线程池（如 `Executors.newFixedThreadPool`）需要手动包装，直接创建实例即可，不依赖 Starter 是否启用：

```java
TraceContextTaskDecorator decorator = new TraceContextTaskDecorator();
executorService.submit(decorator.decorate(task));
```

装饰器在线程池执行任务时传播活动 Trace 上下文并记录任务边界；无父 Trace 时不会创建 Trace。若 HTTP 请求先结束，Trace 时间线会在入口结束处停止，仍在运行的子任务标记为未完成，之后发生的调用不再补写到该 Trace。

## 性能开销

以下数字来自 `benchmarks` 模块中的 JMH 基准（Apple M4 Pro，JDK 21，单线程，平均耗时）。只代表量级，不同硬件和 JVM 会有差异，可按 [benchmarks/README.md](../benchmarks/README.md) 在自己的环境中复现。

| 场景 | 未接入 | 接入后 | 说明 |
| --- | --- | --- | --- |
| 一次未超阈值的入口请求，无依赖调用 | 0.06 µs | 1.7 µs | 主要是入口线程 CPU 时间与内存分配量的采样，以及登记到进行中请求列表 |
| 同上，含 5 次依赖调用 | 0.16 µs | 2.9 µs | 每次依赖调用（Redis、远程 HTTP 等 Observation）约 0.3 µs |
| 同上，含 20 次依赖调用 | 0.45 µs | 6.2 µs | |
| 同上，含 20 次依赖调用并启用调用统计（默认） | 0.45 µs | 7.2 µs | 调用统计每次调用约 50 ns；`metrics.enabled=false` 可关闭 |
| 一条 SQL（JDBC 监听），不在 Trace 中 | — | 0.11 µs | 只计入调用统计；SQL 归一化结果按语句缓存 |
| 一条 SQL，在 Trace 中 | — | 0.17 µs | 额外创建一个 SQL Span |
| `TraceBlock`，不在 Trace 中 | — | 18 ns | 直接执行业务代码，不记录 |
| 一个入口 Trace 内 10 个 `TraceBlock` | — | 1.0 µs | 每个 Span 约 0.1 µs |
| `TraceContextTaskDecorator` 装饰并执行一个任务 | — | 0.27 µs（无 Trace）/ 0.37 µs（Trace 内） | 无父 Trace 时会为任务生成新的 Trace ID |

对于耗时 1 ms 以上的请求，这部分开销低于 1%；10 ms 的请求低于 0.1%。慢请求、错误和资源超阈值的入口还会被写入内存列表并在后台线程异步归档，归档 I/O 不在业务线程上执行。

## 公共 API

以下类型是公开 API，正式发布后保持兼容：

| 类型 | 用途 |
|---|---|
| `trace.TraceSpan`、`trace.TraceDisabled` | 声明或排除方法级 Span |
| `trace.TraceBlock`、`trace.SpanKind` | 在代码中包裹自定义 Span |
| `trace.TraceIdContext` | 读取当前 Trace ID（`currentTraceId()`、`MDC_KEY`） |
| `trace.TraceContextTaskDecorator` | 线程池传播 Trace 上下文 |
| `web.ObservabilityAccessPolicy` | 页面和 API 授权扩展点 |
| `integration.observation.ObservationTraceIdResolver` | 从其他框架的 Observation 上下文提取已传播的 Trace ID，定义该类型的 Bean 即可生效 |
| `integration.feign.TraceIdRequestInterceptor`、`integration.kafka.TraceIdRecordInterceptor` | 通过 `Feign.builder()` 手动组装客户端或自定义 Kafka `RecordInterceptor` 时注册 |
| `config.MeerkatProperties` | `meerkat.*` 配置项 |

包名均以 `io.github.lakernote.meerkat` 开头。自动配置类（`autoconfigure` 包）只用于按名称排除。

包路径中含 `internal` 的类型是内部实现，例如 Trace 存储、指标存储、Controller 和 Filter。它们即使是 `public`，也可能在任何版本中调整，请不要直接依赖。这些内部组件对应的 Bean 也不支持用同类型 Bean 替换；需要定制时请使用上表中的扩展点或配置项。
