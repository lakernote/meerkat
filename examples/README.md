# 示例应用

- `meerkat-example-spring-boot-3`：Spring Boot 3，默认 `http://localhost:8083/`
- `meerkat-example-spring-boot-4`：Spring Boot 4，默认 `http://localhost:8084/`

两个示例代码相同。主页按“在哪个页面看效果”分组列出全部场景，点击即可触发真实调用。

## 启动

H2 是内嵌内存数据库，无需额外服务。Redis 和 Kafka 场景需要先启动本地依赖：

```bash
docker compose -f examples/docker-compose.yml up -d --wait
```

然后在仓库根目录选择一个 Spring Boot 版本启动：

```bash
mvn -pl examples/meerkat-example-spring-boot-3 -am clean package -DskipTests
java -jar examples/meerkat-example-spring-boot-3/target/meerkat-example-spring-boot-3-*.jar

mvn -pl examples/meerkat-example-spring-boot-4 -am clean package -DskipTests
java -jar examples/meerkat-example-spring-boot-4/target/meerkat-example-spring-boot-4-*.jar
```

页面入口：Trace 诊断 `/meerkat/`、调用排行 `/meerkat/stats/`、指标趋势 `/meerkat/metrics/`、实时诊断 `/meerkat/live/`、流量抓取 `/meerkat/capture/`、工具箱 `/meerkat/tools/`。

示例阈值：HTTP 和定时任务慢阈值 250 ms，入口线程 CPU 时间 100 ms，内存分配量 8 MB（见 `application.yaml`）。

页面和 API 默认允许访问；示例通过 `DemoAccessConfiguration` 允许所有人清空历史，仅用于本机体验，不要暴露到不可信网络。

## Trace：依赖调用（`/api/demo/trace`）

| 场景 | URL | 看什么 |
| --- | --- | --- |
| SQL 正常 / 慢 / 错误 | `/db`、`/db?mode=slow`、`/db?mode=error` | 慢场景是真实执行 900 ms 的 SQL；错误场景是主键冲突，详情含 SQL 异常堆栈 |
| 事务回滚 | `/transaction-rollback` | 事务内 INSERT 后抛异常，调用树含 SQL，详情含根因 |
| N+1 查询 | `/n-plus-one` | 20 次 INSERT + 20 次相同 SELECT（每次 15 ms）；慢 Trace 日志合并为 `count=20` |
| 自定义业务步骤 | `/custom-steps` | `@TraceSpan` 方法下的 `TraceBlock` 步骤：加载、计算（CPU）、渲染 |
| Redis 正常 / 慢 / 错误 | `/redis`、`/redis?mode=slow`、`/redis?mode=error` | 慢场景是 1 秒 BLPOP；错误场景是 `WRONGTYPE` |
| 远程 HTTP 正常 / 慢 / 错误 | `/remote`、`/remote?mode=slow`、`/remote?mode=error` | 调用 httpbingo 的 `/anything`、`/delay/1`、`/status/500` |
| Kafka 正常 / 慢 / 错误 | `/kafka`、`/kafka?mode=slow`、`/kafka?mode=error` | 发送并等待消费；consumer 是独立的 Kafka 入口 Trace。未消费完可查 `/kafka/status` |
| 完整链路 | `/full-flow` | 一个请求依次经过 H2、Redis、远程 HTTP、Kafka |

远程 HTTP 需要访问 httpbingo，可用 `MEERKAT_DEMO_HTTPBINGO_BASE_URL` 换成兼容服务。示例通过 Spring 管理的 `RestClient.Builder` 创建客户端，`RestClient.create()` 创建的客户端不会被观测。

## Trace：跨线程（`/api/demo/async`）

| 场景 | URL | 看什么 |
| --- | --- | --- |
| 等待子线程 | `/child-thread` | 650 ms 的子线程任务挂在请求调用树下，时间线用 T1/T2 区分线程 |
| 请求返回后子线程仍运行 | `/fire-and-forget` | 子线程节点标为“请求结束时仍在运行”，日志带 `[RUNNING_AT_END]` |
| 带传播的线程池 | `/executor?mode=slow`、`/executor?mode=error` | 线程池配置 `TraceContextTaskDecorator` 后，任务以 `executor.task` 节点记录耗时和异常 |
| 不带传播的线程池 | `/executor?mode=slow&propagation=unwrapped` | 对照组：响应 `propagated=false`，任务不出现在调用树中 |

## Trace：资源超阈值（`/api/demo/resource`）

| 场景 | URL | 看什么 |
| --- | --- | --- |
| 高 CPU | `/cpu` | 约 200 ms 计算，不慢也不报错，但超过 CPU 阈值被记录 |
| 高内存分配 | `/allocation` | 累计分配约 24 MiB 临时数组，超过分配量阈值被记录 |

CPU 时间不是 CPU 使用率；内存分配量是累计创建对象的大小，不是堆占用；两者只统计入口线程。

## 实时诊断（`/api/demo/live`）

这些请求会持续一段时间（默认 30 秒，`?seconds=N` 可调，最长 120 秒）。在新标签页打开，然后切到实时诊断页观察。

| 场景 | URL | 在实时诊断页看到 |
| --- | --- | --- |
| 卡在业务步骤 | `/slow-step` | 进行中请求的当前步骤为 `ReportService.export`，线程 `TIMED_WAITING` |
| 卡在 SQL | `/slow-sql` | 当前步骤为 `SQL SELECT`，栈停在 JDBC 驱动里 |
| 卡在远程调用 | `/slow-remote` | 当前步骤为远程 HTTP 调用（httpbingo `/delay`，最长 10 秒） |
| 占满 CPU | `/cpu` | 请求 CPU 时间持续增长，线程在 CPU 排行中接近 100% |
| 锁竞争 | `/lock` | 3 个 `demo-lock-waiter-*` 线程 `BLOCKED`，锁为 `InventoryLock`，持有者是请求线程 |
| 死锁 | `/deadlock` | 检测到 `demo-deadlock-orders` 与 `demo-deadlock-accounts` 互相等待；默认 60 秒后自动解除 |
| 异步请求 | `/async` | 请求标为异步，入口线程已释放，因此没有线程栈和 CPU |

## 调用排行与指标趋势（`/api/demo/stats`）

- `/traffic?requests=60`：用 4 个线程批量调用本机示例接口（最多 500 次），混合快、慢、出错和高资源请求，然后到调用排行页查看次数、错误、P95、平均 CPU 和内存分配。
- 定时任务每 30 秒运行一次：`orderReport`（H2，约 300 ms，每次都是慢任务）和 `redisHeartbeat`（Redis 未启动时每次失败），计入“定时任务”分类。

完成后可关闭本地依赖：

```bash
docker compose -f examples/docker-compose.yml down
```
