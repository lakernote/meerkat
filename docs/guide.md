# Configuration Guide

English | [简体中文](guide.zh-CN.md)

Add the starter that matches your Spring Boot major version and Meerkat registers trace collection, runtime metrics and the embedded dashboard. Usually no configuration is needed. The starter depends on `spring-boot-starter-actuator` and reuses the Micrometer meters Spring Boot already binds. It only adds one registry that accepts a fixed set of meters; it does not replace your `MeterRegistry` or `ObservationRegistry` and does not affect existing metric exports.

## Requirements

- Java 17 or later. Use the Spring Boot 3 starter for Spring Boot 3.5+ and the Spring Boot 4 starter for Spring Boot 4.0+ (CI also builds and tests against the minimum versions 3.5.0 and 4.0.0).
- Web applications must use the Servlet stack (Spring MVC). The trace context is bound to the thread, which matches the Servlet model of one thread per request.
- **Reactive applications such as WebFlux are not supported.** The application still starts with the starter, but reactive HTTP requests do not create entry traces and the dashboard and API are not registered; calls that switch threads in a reactive pipeline cannot be linked to the same trace.
- Non-web applications (for example Kafka consumers or scheduled jobs only) collect traces and runtime metrics, but there is no dashboard to view them.
- GraalVM Native Image is not supported yet; there are no AOT runtime hints and it has not been verified.

### Compatibility verification

CI runs the following combinations on every commit. Middleware tests start real services with Testcontainers and are skipped automatically when Docker is not available locally.

| Area | Verified |
| --- | --- |
| JDK | 17, 21, 25 |
| Spring Boot | 3.5.0 (minimum), latest 3.5.x; 4.0.0 (minimum), latest 4.1.x |
| HTTP clients | `RestTemplate`, `RestClient` and `OkHttpClient` beans: client spans and trace ID propagation |
| Kafka | Real Kafka (`apache/kafka` 4.1): producer span, consumer entry trace with the producer's trace ID |
| Redis | Real Redis 7.4 (Lettuce): command spans under the request trace; Redisson has its own compatibility tests |
| JDBC | H2 + HikariCP: SQL spans, literal redaction, concrete data source type preserved |
| Micrometer Tracing | Brave: root requests share the Micrometer trace ID; upstream `traceparent` is accepted |

## What is collected automatically

| Scenario | Collected | Notes |
| --- | --- | --- |
| HTTP entry | Route, status, latency, error, entry-thread CPU time and memory allocation | Slow thresholds apply to entries. CPU time and allocation are cumulative for the entry thread, not process usage or current heap, and exclude child threads |
| Scheduled jobs, Kafka listeners | Entry latency and errors | The starter enables Spring Kafka observation on `KafkaTemplate` and listener factories so producer and consumer are linked automatically; an explicit `spring.kafka.*.observation-enabled=false` is respected |
| JDBC, transactions, MyBatis | SQL template, transaction scope, latency and errors | Literals and comments are removed from SQL; bind parameters are never recorded |
| Redis | Lettuce observation command spans; command spans for Spring Data Redis through Redisson's `RedisConnectionFactory` | Direct Redisson API calls such as `RLock` or `RMap` are not covered by this connection-factory instrumentation |
| Remote HTTP | Client call span, trace ID propagated in a request header | `RestTemplate`, `RestClient` (including beans created with `new RestTemplate()` or `RestClient.create()`), `@FeignClient` and `OkHttpClient` beans are instrumented automatically. Clients created inside methods, the JDK `HttpClient` and other non-bean clients are not |
| Async executors | Task latency, errors and propagation of the active trace context | The Spring Boot default executor and `ThreadPoolTaskExecutor` / `SimpleAsyncTaskExecutor` beans are instrumented automatically; a plain JDK `ExecutorService` must be wrapped by hand. Background tasks that keep running after the request returned do not extend the completed entry trace |
| Operation statistics | Count, errors, average/P95/max and total latency per HTTP endpoint, SQL template, remote call, Redis command, scheduled job and Kafka topic, with trends over time; average CPU time and allocation for HTTP, job and Kafka-consumer entries | Every call is counted, slow or not. Shares the instrumentation with tracing, see [Runtime metrics](#runtime-metrics) |
| Resource metrics | A fixed set of meters Spring Boot already binds: JVM memory and GC, CPU, threads, Hikari pools, executors, Tomcat/Jetty, disk | Recorded once a minute; saturation meters sampled every 10 seconds, see [Runtime metrics](#runtime-metrics) |

Use `TraceBlock` or `@TraceSpan` for custom business boundaries. Meerkat does not scan your services to create spans automatically.

The trace detail page and the slow-trace log merge consecutive, successful repeats of the same call (same kind, thread, name and SQL/tag) into one row with the count, total and longest time. Rows repeated 10 times or more are highlighted to make N+1 queries easy to spot. Expanding a row in the operation ranking links to related slow or failed traces.

JDBC collection uses a proxy that keeps the data source's concrete type, so injecting `HikariDataSource` or other concrete types and calling vendor extension methods still works. If the data source class is `final`, has public `final` methods that cannot be proxied safely, or the runtime refuses class proxies, Meerkat logs a warning and skips JDBC tracing for that data source instead of replacing the bean with an incompatible wrapper. With multiple data sources, SQL nodes are prefixed with the data source bean name (such as `orderDataSource · select …`); with one data source the prefix is omitted.

## Configuration

Common settings are listed below; anything not listed keeps the starter default. Every setting is optional.

| Property | Default | Description |
| --- | --- | --- |
| `meerkat.enabled` | `true` | Master switch |
| `meerkat.storage.directory` | `./data/meerkat/{spring.application.name}` | Local storage directory for diagnostic trace segments (`traces/`) and metric history (`metrics/`). Relative paths resolve against the working directory; `application` is used when no application name is set |
| `meerkat.trace.enabled` | `true` | Trace collection and context propagation |
| `meerkat.trace.header-name` | `X-Trace-Id` | Trace ID header for HTTP and supported messaging/clients |
| `meerkat.trace.http-slow-threshold` | `500ms` | Slow threshold for HTTP entries |
| `meerkat.trace.job-slow-threshold` | `1s` | Slow threshold for scheduled jobs |
| `meerkat.trace.kafka-consumer-slow-threshold` | `1s` | Slow threshold for Kafka consumer entries |
| `meerkat.trace.cpu-threshold` | `500ms` | Entry-thread CPU time that flags a resource-heavy entry |
| `meerkat.trace.allocated-memory-threshold` | `50MB` | Entry-thread cumulative allocation that flags a resource-heavy entry |
| `meerkat.trace.retention` | `7d` | Days to keep diagnostic traces, whole days from `1d` to `365d` |
| `meerkat.trace.max-storage-size` | `128MB` | Disk limit for diagnostic traces; the oldest segment files are deleted beyond it |
| `meerkat.trace.max-memory-size` | `32MB` | Heap limit for caching full call trees of the newest traces (oldest evicted by estimated size). Older records are read from disk when opened, or dropped when storage is unavailable |
| `meerkat.trace.max-per-operation-per-minute` | `60` | Traces stored per minute for each entry and outcome (error type, slow or resource); later ones that minute are only counted (`throttledCount` in `/api/meerkat/traces/storage`), so one failing endpoint cannot push out every other trace. `0` keeps all |
| `meerkat.metrics.enabled` | `true` | Master switch for runtime metrics (operation statistics and resource metrics) |
| `meerkat.metrics.max-operations` | `500` | Maximum operations tracked across all categories. Beyond it, new operations go to an "other" row per category; operations without calls for 1 hour release their slot |
| `meerkat.metrics.retention` | `30d` | Metric history retention, whole days from `1d` to `365d` |
| `meerkat.metrics.max-storage-size` | `64MB` | Disk limit for metric history; the oldest days are deleted beyond it, the newest day is always kept |
| `meerkat.web.enabled` | `true` | Dashboard and query API |

Example:

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

Durations use Spring Boot's `Duration` format (`500ms`, `2s`, `30d`). Plain numbers are milliseconds for thresholds and days for `retention`. Setting any of the four duration thresholds to `0` or a negative value, or `allocated-memory-threshold` to `0B`, turns off that slow or resource trigger.

The storage directory belongs to one instance. At startup Meerkat creates `.lock` in the directory and holds an OS file lock until shutdown. A second instance on the same machine pointing at the same directory (including another replica of the same application) does not write to it: it logs a warning and keeps diagnostic traces and metric history in memory only, and the host application starts normally. The same in-memory fallback applies when the directory cannot be created or written. When running several replicas on one machine, give each its own `storage.directory`, for example by including an instance number in the path.

Only slow, failed and resource-heavy entries (HTTP, scheduled jobs, Kafka consumers) are saved. Diagnostic traces are appended as JSON Lines to `traces/traces-yyyyMMdd-N.jsonl`, rolled daily and also when a segment reaches 1/8 of `max-storage-size` (256KB to 8MB). Whole segments older than `retention`, and the oldest segments when the total exceeds `max-storage-size`, are deleted as files; files in use are never rewritten. After a restart a background thread scans the segments to rebuild a lightweight index (no call trees, up to 50,000 entries, about 16MB of heap; only the newest are listed beyond that). The dashboard is usable during the rebuild and older records appear gradually; full records are read from disk when opened. A single record larger than a segment after encoding is kept in memory only.

Background serialization and file I/O never hold the lock used when recording an entry. When the write queue is full, that record is not persisted rather than blocking a request, Kafka consumer or job thread. If storage I/O fails at runtime, trace storage falls back to in-memory mode until the application restarts, and `persistent` in `/api/meerkat/traces/storage` returns `false`.

Trace query API (all `GET`, prefix `/api/meerkat`):

- `/traces?kind=all|slow|error|resource&source=HTTP|JOB|KAFKA_CONSUMER&q=&before=&limit=50`: paged by completion time, newest first. Returns `items` (without call trees), `nextCursor` (pass as `before` for the next page), `total` and `scanLimited`. `q` matches entry names, trace IDs, error messages, thread names and SQL, remote addresses and other nodes in the call tree. Text search scans disk and reads at most 32MB per request; when the limit is hit `scanLimited` is `true`, `total` is `-1`, and `nextCursor` continues the search. `limit` is at most 200.
- `/traces/{traceId}?source=`: full call tree, or 404.
- `/traces/storage`: `persistent`, `storageBytes`, `maxStorageBytes`, `retentionDays`, `traceCount`, `indexing`.
- `/errors?window=1h|24h|7d&source=&limit=50`: failed traces in the window grouped by error type and message, largest first (at most 200 groups). Each group has `errorType`, `message` (numbers, IDs and quoted values masked), `sampleMessage`, `count`, `firstSeen`, `lastSeen`, `newInWindow` (no failure of this group was kept before the window), the top `entries`, `latestTraceId` and a 24-bucket `trend`.

The **Error groups** panel on the Trace page shows the same data: one row per kind of failure instead of a page of identical ones, with a "New" mark for failures that only started in the window (for example after a release). Failures without an exception, such as a bare 5xx status, are grouped per entry. Groups are computed on request from the trace index, so they cost nothing per request and survive restarts; they cover the failures still kept by `retention` and `max-storage-size`.

Invalid settings such as `retention` or `max-storage-size` stop the application from starting so configuration mistakes surface early. If writing metric history fails at runtime because of directory permissions, a read-only file system or disk I/O, Meerkat logs a warning and keeps the data in memory without interrupting the host application; the dashboard shows "Storage degraded". Restart the application after fixing storage to resume saving history.

## Runtime metrics

Runtime metrics answer "how is this instance doing overall, and which operations are slowest or busiest"; trace diagnostics answer "where did this request spend its time". They share instrumentation but are independent: metrics cover every call, traces keep only slow, failed or resource-heavy requests. There are two metric pages:

- **Operations** (`/meerkat/stats/`): per category, each operation's call count, errors and error rate, average latency, P95, max latency, total latency and its share of the category; click a column header to sort and a row to expand its count and latency trend. HTTP, job and Kafka-consumer entries also show average entry-thread CPU time and allocation per call, to find CPU-hungry endpoints and endpoints that allocate heavily and cause frequent GC. These values reuse data the entries already collect, so they add no overhead; requests that switch to async processing are not counted. Good for "which endpoint takes the most time", "which SQL runs most often", "which remote service fails most", "which endpoint uses the most CPU or memory".
- **Metrics** (`/meerkat/metrics/`): a service overview (HTTP requests, error rate, latency and dependency latency, reusing the operation statistics) followed by CPU, memory, GC, threads, executors, Hikari pools, web connections and traffic, disk and uptime charts, so a symptom can be traced to its cause. Per-operation trends are in the Operations page.

### Operation statistics

| Category | Name | Counted as error |
| --- | --- | --- |
| HTTP endpoint | Route template · method, e.g. `/orders/{id} · GET` | Exception or status ≥ 500 |
| SQL | SQL with literals removed; `IN (?, ?, ...)` becomes `IN (?…)`, multi-row `VALUES` keep the first row | JDBC exception |
| Remote call | Method + target host + URI template | Exception or status ≥ 500 |
| Redis | Command name | Exception or error reply |
| Scheduled job / Kafka consumer / Kafka send | Job name / topic · listener ID / destination topic | Exception |

- Ranking windows are "Last hour", "Today", "Last 7 days" and "Last 30 days"; days follow the JVM default time zone. Trend resolution: one point per minute for the last hour, per 10 minutes for 24 hours, per hour for 7 and 30 days.
- P95 is estimated from a log-bucketed histogram, accurate to about ±20% and never above the max. Minute-level trends have no P95.
- SQL statistics do not need an active trace, so SQL run during startup or on background threads is counted too; Spring's `spring.jdbc` observation is not double-counted. With `trace.enabled` off, only SQL is counted, and the dashboard says so.
- Names only use Micrometer low-cardinality tags and SQL templates, so per-call values such as Kafka offsets or raw URLs never create new rows. When a remote call has no URI template, the query string and `#` fragment are removed from the URL. Once `metrics.max-operations` is reached, new operations go to the category's "(Other: over the tracked-operation limit)" row; operations without calls for 1 hour are removed from memory and release their slot, while saved history is kept.
- Recording costs about 50 ns per call and is lock-free. Memory holds about the last hour, about 9 KB per operation, or about 4.5 MB with all 500 default operations in use.

### Resource metrics

Resource metrics come from the Micrometer meters Spring Boot already binds; Meerkat does not read JMX itself or instrument anything twice. The starter registers a registry that only accepts the fixed meters below (the list is not configurable) and records them once a minute:

| Meter | Split by |
| --- | --- |
| `jvm.memory.used/committed/max` | `heap` / `nonheap` |
| `jvm.buffer.memory.used` | Buffer pool (`direct` / `mapped`) |
| `jvm.gc.pause` (count, total and longest pause) | GC action (Young / Old/Full, other collectors by their own name) |
| `jvm.gc.live.data.size/max.data.size`, `jvm.memory.usage.after.gc`, `jvm.gc.overhead`, `jvm.gc.memory.allocated/promoted` | Total |
| `jvm.threads.live/daemon/peak`, `jvm.classes.loaded`, `process.uptime`, `process.files.open/max` | Total |
| `process.cpu.usage`, `system.cpu.usage`, `system.cpu.count`, `system.load.average.1m` | Total |
| `disk.free/total` | Path |
| `hikaricp.connections.active/idle/pending/max/timeout`, `hikaricp.connections.acquire/usage` (average and max) | Pool |
| `executor.active/queued/queue.remaining/pool.size/pool.core/pool.max/completed` | Executor |
| `meerkat.executor.rejected` (tasks rejected by a `ThreadPoolTaskExecutor` bean, including ones its policy runs on the caller; the policy itself is unchanged and rejections after shutdown are not counted) | Executor |
| `http.server.requests` (Spring Boot's own meter, reused; Meerkat's own pages are excluded) | Status class: 2xx/3xx/4xx/429/5xx |
| `tomcat.threads.*`, `tomcat.connections.current/keepalive.current/config.max`, `tomcat.global.received/sent`, `tomcat.sessions.active.current`, `jetty.threads.*`, `jetty.connections.current/bytes.in/bytes.out` | Total |

- Saturation meters (CPU usage, busy threads, active and pending connections, executor active and queued, current HTTP connections) are read every 10 seconds; each minute keeps their average and peak, so short bursts are not lost. Other gauges are read once a minute.
- Counters and timers are recorded as the increase per minute; timers also keep the longest single event of the minute.
- Over longer ranges and in compacted history each series is combined by its meaning: counts, times and traffic are summed (charts show the amount per point), peaks take the maximum, limits take the latest value and other gauges are averaged.
- Each meter is split into at most 8 pools, executors or paths; the rest are added to an "other" line. Since the meter list is fixed, the number of lines has a small static upper bound and nothing is dropped silently.
- Unbounded pools, queues and resources (limit `-1` or `Integer.MAX_VALUE`) have no limit or capacity line.
- The HTTP error rate counts exceptions and 5xx responses. 4xx responses are not errors, but the response status chart shows them, with 429 split out because it usually means rate limiting. That chart needs `http.server.requests`, which Spring Boot Actuator records by default.
- Spring Boot binds Tomcat thread meters only when Tomcat registers MBeans, so the starter defaults `server.tomcat.mbeanregistry.enabled` to `true` (lowest precedence: an explicit application setting wins; not set when `metrics.enabled` is off). This costs Tomcat a little extra memory.

### Storage

Operation statistics are written in 10-minute slices and resource metrics once a minute, appended to `metrics/operations-yyyyMMdd.tsv` and `metrics/resources-yyyyMMdd.tsv` in the storage directory. A graceful shutdown writes the current partial slice, so every window survives restarts: at startup the last hour's slices are read back, and the pre-restart part of minute charts is spread evenly over each 10 minutes. If the process is killed, at most about 10 minutes of operation statistics are lost.

- Files older than two days are compacted once: operation statistics into one line per hour, resource metrics into 10-minute buckets combined as described above, so 30 days of data is usually only a few MB.
- Days older than `metrics.retention` are deleted; when the total exceeds `metrics.max-storage-size`, the oldest days are deleted. The newest day is always kept, so with a very small limit the archive can exceed it by that one day.
- When the storage directory is unavailable (owned by another instance or not writable), data stays in memory only, longer windows only cover about the last hour, and the dashboard says so.
- Each instance stores its own data; nothing is aggregated across instances. When `canClear()` allows it, the Operations page shows "Reset stats", which clears operation statistics in memory and on disk; resource metric history is kept.

Query API (all `GET`, prefix `/api/meerkat/metrics`): `/operations?window=1h|today|7d|30d`, `/operations/trend?category=HTTP&name=...&period=1h|24h|7d|30d`, `/operations/trends?period=...`, `/resources?period=...`, `/storage`. Reset with `POST /operations/reset`.

## Live diagnostics

The `/meerkat/live/` page answers "what is happening right now", useful when an endpoint hangs, CPU suddenly spikes or an executor is saturated. Data is read when the page opens or you click refresh; nothing is sampled in the background or written to disk.

- **In-flight requests:** HTTP, scheduled-job and Kafka-consumer entries currently running, ordered by elapsed time, with entry-thread CPU time so far, the innermost step currently executing (for example a SQL statement or remote call and how long it has been running) and the thread state. Expand a row for the thread's current stack and the lock it is waiting on. Refreshes every 5 seconds by default. Once a request switches to async processing and its entry thread has returned, only elapsed time and the current step are shown, without a stack. Shows up to 200; the dashboard's own requests are excluded.
- **Threads:** all platform threads are sampled for 1 second and sorted by CPU usage (100% means one full core), with state, cumulative CPU, the lock waited on and its owner; expand for the stack. Deadlocked threads are listed at the top when detected. "Merge identical stacks" collapses threads with the same state and stack into one row, making it easy to spot many threads stuck in the same place. A jstack-format thread dump can be downloaded. Virtual threads are not listed.

Each entry adds and removes itself from a concurrent collection once at start and once at end, which does not affect request latency; stacks are read only when the page asks. Only one thread sample runs at a time; a request while sampling returns 429.

Query API (all `GET`, prefix `/api/meerkat/live`): `/requests`, `/connections`, `/threads?sampleMs=0..5000` (default 1000), `/threads/dump` (`text/plain` attachment; only one dump at a time and at least 5 seconds apart, otherwise 429).

## Traffic capture

When a request misbehaves and the trace tree alone does not explain why, traffic capture records everything the request did: the incoming request and response (headers and bodies), every SQL statement with its bound parameters and result rows or update count, Redis commands with their replies, and outbound HTTP calls (RestTemplate, RestClient, Feign) with their requests and responses. All of it is tied together by the trace ID in one record.

There is no on/off switch: who may capture is decided by `ObservabilityAccessPolicy#canCapture()`, which the built-in policy denies, so capture is unavailable until your policy grants it (see [Dashboard and API access](#dashboard-and-api-access)). Nothing is recorded until a capture is started from the page. Optional limits:

```yaml
meerkat:
  capture:
    max-body-size: 64KB    # per request/response body
    max-result-rows: 20    # SQL result rows kept per query
    max-value-chars: 16384 # per recorded value
    max-trace-chars: 1000000 # per captured request
    max-records: 100       # captured requests kept in memory; also the most one capture may request
    max-stored-size: 32MB  # heap for all kept records; oldest dropped first
    retention: 2h
    max-duration: 1h       # longest a capture may run
```

Open `/meerkat/capture/`, enter a rule — HTTP method, an Ant-style path such as `/api/orders/**`, and optionally a header and value (for example the ID of the user reporting the problem) — and how many requests to capture and for how long. The capture stops by itself after that many requests or that time, whichever comes first. Up to 10 captures can be active at once; starting and stopping one is logged at WARN with the operator.

> [!WARNING]
> Captured data is **not redacted**: it can contain passwords, tokens and personal data. Grant `canCapture()` only to people allowed to see that data, and keep captures short.

What to know:

- Only HTTP entry requests are captured. Bodies contain only what the application actually read and wrote, up to `max-body-size`; nothing is read ahead. File uploads and downloads (binary content types such as images or octet-stream, `multipart/*`, or `Content-Disposition: attachment`) are not copied; they are recorded as a label such as `[file upload, multipart/form-data, 10485760 bytes; content not recorded]`. This applies to inbound requests and to outbound `RestTemplate`/`RestClient`/OpenFeign calls. Form posts read through `getParameter` are recorded as parameters.
- SQL results show the first `max-result-rows` rows and only the columns the application read; LOB and stream columns are not recorded. `·` marks a column the application did not read.
- Captured records are kept in memory only, never on disk, and never appear in the trace list or trace API. A captured request still appears in the trace list on its own merits (slow, failed, ...) like any other.
- When no capture is active, the cost is one volatile read per request. While capturing, only matching requests pay for copying their data. Meerkat turns on datasource-proxy's result-set proxy, which returns the driver's result set unchanged for queries that are not captured, and wraps the request factory of `RestTemplate`/`RestClient` beans rather than adding an interceptor, so request bodies keep streaming and uploads are never buffered in memory. Calls outside a captured request get the original request. A `RestClient.Builder` whose request factory is replaced after startup is not captured.

API (prefix `/api/meerkat/capture`): `GET /status`, `POST /sessions` (JSON body `{"method","pathPattern","headerName","headerValue","maxCount","durationSeconds"}`), `POST /sessions/{id}/stop`, `GET /records?sessionId=`, `GET /records/{id}`, `POST /records/clear`. Everything except `/status` requires both `canView()` and `canCapture()`.

## Toolbox

The toolbox at `/meerkat/tools/` runs a few classic diagnostics on demand, without a Java agent or bytecode instrumentation. Nothing runs in the background until someone starts a tool, every tool is bounded in time and memory, and each heavy tool runs once at a time (a second request gets 429).

| Tool | What it does | Permission |
| --- | --- | --- |
| Log levels | Lists loggers with their configured and effective levels (through Spring Boot's `LoggingSystem`, so Logback and Log4j2 both work) and sets a level for 1–60 minutes; it reverts to the previous configured level by itself, or at once with "Revert". At most 50 temporary levels at a time; changes are logged at WARN with the operator | listing: `canView()`; changing: `canOperate()` |
| CPU flame graph | Samples the stacks of RUNNABLE threads every 10–100 ms (default 20 ms) for up to 60 s (default 10 s) and draws an interactive flame graph (click to zoom, hover for samples and %) with the hottest methods by self time. The call tree is capped at 20,000 nodes and 128 frames per stack; idle native waits (socket accept, epoll, ...) can be skipped and blocked/waiting threads included. The collapsed-stack download works with flamegraph.pl and speedscope | `canOperate()` (starting, stopping and reading profiles) |
| Heap histogram | Runs `GC.class_histogram` (live objects, so it **triggers a full GC pause**) and shows the top classes by bytes; the next histogram shows the growth since the previous one, which helps find leaks. Only the previous histogram (up to 500 rows) is kept, and histograms are at least 10 s apart | `canOperate()` |
| Heap dump | Writes a dump of live objects (`HotSpotDiagnosticMXBean.dumpHeap`) to the temp directory after checking free space, streams it as a download and deletes it; dumps left over by a process that died mid-download are deleted on the next dump after a day. It **pauses the application** and can be as large as the heap | `canOperate()` |
| Configuration sources | For a property name (or a name search, at most 200 results), shows the effective value with placeholders resolved and every property source that defines it, in precedence order, marking the one that wins. Values are **not redacted** | `canOperate()` |
| Class sources | For a class name, shows every copy visible to the application class loader, where the loaded one came from and the class loader chain. "Scan duplicate classes" reads the jars and directories on the class path (including the nested jars of a Spring Boot executable jar when its class loader exposes them) and lists classes found in more than one place, grouped by jar pair; it stops after 500 duplicates or 20 s | lookup: `canView()`; duplicate scan: `canOperate()` |

```yaml
meerkat:
  tools:
    enabled: true                  # false turns the whole toolbox API off (404)
    log-level-max-duration: 1h     # longest temporary log level, at most 1h
    heap-histogram-interval: 10s   # shortest time between two heap histograms
```

> [!WARNING]
> `canOperate()` is denied by default, including by the built-in policy: heap dumps and configuration values expose secrets, heap histograms and dumps pause the application, CPU profiling and duplicate class scans run for up to a minute and expose the code's structure, and log levels change runtime behaviour. Grant it only to trusted operators.

What to know:

- The CPU profiler only sees what `ThreadMXBean` reports, so stacks are taken at safepoints (safepoint bias) and native frames are not shown. It is meant to find hot spots, not to measure exact costs. Sampling itself costs CPU; the result shows the time spent taking stacks.
- The heap dump download is held in browser memory before saving; for very large heaps use `jcmd <pid> GC.heap_dump` on the host instead.
- Duplicate scanning reads class path entries without loading classes. Entries that cannot be opened are listed as unreadable rather than failing the scan.

API (prefix `/api/meerkat/tools`): `GET /status`; `GET /loggers?q=&limit=`, `POST /loggers/overrides` (JSON `{"logger","level","durationSeconds"}`), `POST /loggers/overrides/revert?logger=`; `POST /profiler` (JSON `{"durationSeconds","intervalMs","includeBlocked","excludeIdle","groupByThread"}`), `GET /profiler`, `GET /profiler/{id}`, `POST /profiler/{id}/stop`, `GET /profiler/{id}/collapsed`; `POST /heap/histogram?limit=`, `POST /heap/dump`; `GET /config?name=`, `GET /config/search?q=`; `GET /classes?name=`, `POST /classes/duplicates`.

## Dashboard and API access

The dashboard is at `/meerkat/`, operations at `/meerkat/stats/`, metrics at `/meerkat/metrics/`, live diagnostics at `/meerkat/live/`, traffic capture at `/meerkat/capture/`, the toolbox at `/meerkat/tools/`, and the query API under `/api/meerkat/`. The dashboard follows the browser language (Chinese or English) and can be switched in the top bar; the choice is stored in the browser. Meerkat provides no usernames, passwords or login; authentication is left to your Spring Security setup, Servlet container or gateway.

API authorization is decided by `ObservabilityAccessPolicy`; define a bean of that type to replace the default:

| Method | Controls | Default |
| --- | --- | --- |
| `canView()` | Querying traces, metrics, storage status and current permissions | `true`, allows every request |
| `canClear()` | Clearing trace history (`POST /api/meerkat/traces/clear`) and resetting operation statistics (`POST /api/meerkat/metrics/operations/reset`); `canView()` must also pass | `false`, denies every request; the dashboard hides "Clear history" and "Reset stats" |
| `canCapture()` | Traffic capture: starting and stopping captures and reading captured records (`/api/meerkat/capture/**`); `canView()` must also pass | Denied, including by the built-in policy; override `canCapture()` to allow it. This is the only switch for traffic capture |
| `canOperate()` | Sensitive or heavy toolbox operations: changing log levels, CPU profiling, heap histograms (full GC), downloading heap dumps, reading configuration values and scanning for duplicate classes (`/api/meerkat/tools/**`); `canView()` must also pass | `false`, denies every request, including the built-in policy; the toolbox disables those controls |

`canClear()` is a default method, so a lambda is enough to restrict viewing while clearing stays off:

```java
@Bean
ObservabilityAccessPolicy observabilityAccessPolicy() {
    return () -> hasAnyRole("ROLE_DEVELOPER", "ROLE_OPS");
}
```

**By default anyone who can reach the application port can view traces and metrics.** In production, protect `/meerkat/**` and `/api/meerkat/**` with your security filter chain or gateway, or customize `canView()`. Both methods run on the request thread and can read the Spring Security context or the current request. For example, let developers and operators view and only operators clear:

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

Meerkat rejects state-changing API requests (anything but `GET`, `HEAD`, `OPTIONS`) that a browser sends from another site with 403: requests whose `Sec-Fetch-Site` is not `same-origin` or `none`, or, for browsers without that header, whose `Origin` does not match the request's host and port. Requests without either header, such as curl or server-to-server calls, are not affected. So a cross-origin page (including another port or subdomain of the same site) cannot call these endpoints; put the dashboard and the application on the same origin.

If Spring Security CSRF protection is on, the clear and reset POST requests need a CSRF token. With `CookieCsrfTokenRepository` the dashboard reads the `XSRF-TOKEN` cookie and sends the `X-XSRF-TOKEN` header automatically; otherwise configure a CSRF policy for those paths.

## Trace ID in logs

The current trace ID is stored under the SLF4J MDC key `meerkatTraceId`; print it with `%X{meerkatTraceId}`. With Spring Boot:

```yaml
logging:
  pattern:
    correlation: "[%X{meerkatTraceId:-}] "
```

The MDC key differs from Micrometer Tracing's `traceId` and `spanId`, but the values are aligned: when the application also uses Micrometer Tracing (OpenTelemetry or Brave), root requests, message consumers and scheduled jobs adopt the Micrometer Tracing trace ID, so `%X{traceId}` and `%X{meerkatTraceId}` print the same value and the same ID can be searched in Zipkin, Jaeger or Tempo. In code, read the current ID with `TraceIdContext.currentTraceId()`.

Inbound trace ID priority:

1. The header named by `meerkat.trace.header-name` (default `X-Trace-Id`);
2. The trace-id in a W3C `traceparent` header;
3. The trace ID of the current Micrometer Tracing span;
4. Otherwise a new ID is generated.

Outbound calls only write `X-Trace-Id`. Standard headers such as `traceparent` and B3 are written by Micrometer Tracing; Meerkat does not write them again, so it never changes the sampling decision of downstream services. SkyWalking's `sw8` header is not recognized yet.

## Kafka and executors

The starter enables observation on `KafkaTemplate` and listener factories in the Spring context, so `spring.kafka.template/listener.observation-enabled` is not needed; an explicit `false` is respected. The default listener factory gets the trace ID interceptor automatically. If the application uses its own `RecordInterceptor`, compose `TraceIdRecordInterceptor` with it in your configuration. Batch listeners, custom consumers and Kafka Streams are not covered automatically.

The Spring Boot default executor (`applicationTaskExecutor`, used by `@Async` by default) and `ThreadPoolTaskExecutor` / `SimpleAsyncTaskExecutor` beans get `TraceContextTaskDecorator` automatically. If an executor already has its own `TaskDecorator`, the two are combined with `CompositeTaskDecorator` and the original runs first. Schedulers (`ThreadPoolTaskScheduler`) are not decorated; `@Scheduled` tasks are recorded as scheduled-job entries.

Executors that are not Spring beans (such as `Executors.newFixedThreadPool`) must be wrapped by hand. Create the decorator directly; it works whether or not the starter is enabled:

```java
TraceContextTaskDecorator decorator = new TraceContextTaskDecorator();
executorService.submit(decorator.decorate(task));
```

The decorator propagates the active trace context and records the task boundary when the executor runs the task; it never creates a trace when there is no parent. If the HTTP request finishes first, the timeline stops at the end of the entry, child tasks still running are marked unfinished, and later calls are not added to that trace.

## Overhead

These numbers come from the JMH benchmarks in the `benchmarks` module (Apple M4 Pro, JDK 21, single thread, average time). They show orders of magnitude only and vary with hardware and JVM; reproduce them in your environment with [benchmarks/README.md](../benchmarks/README.md).

| Scenario | Without | With Meerkat | Notes |
| --- | --- | --- | --- |
| One entry below every threshold, no dependency calls | 0.06 µs | 1.7 µs | Mostly sampling entry-thread CPU time and allocation, and registering the in-flight request |
| Same, with 5 dependency calls | 0.16 µs | 2.9 µs | About 0.3 µs per dependency call (Redis, remote HTTP and other observations) |
| Same, with 20 dependency calls | 0.45 µs | 6.2 µs | |
| Same, with 20 dependency calls and operation statistics (default) | 0.45 µs | 7.2 µs | Operation statistics cost about 50 ns per call; turn off with `metrics.enabled=false` |
| One SQL statement (JDBC listener), outside a trace | — | 0.11 µs | Only counted in statistics; normalized SQL is cached per statement |
| One SQL statement, inside a trace | — | 0.17 µs | Also creates a SQL span |
| `TraceBlock` outside a trace | — | 18 ns | Runs the business code directly without recording |
| 10 `TraceBlock`s inside one entry trace | — | 1.0 µs | About 0.1 µs per span |
| `TraceContextTaskDecorator` decorating and running one task | — | 0.27 µs (no trace) / 0.37 µs (in a trace) | Generates a new trace ID for the task when there is no parent trace |

For requests taking 1 ms or more this is under 1%; for 10 ms requests, under 0.1%. Slow, failed and resource-heavy entries are also added to an in-memory list and archived asynchronously on a background thread; archive I/O never runs on business threads.

## Public API

The following types are public API and stay compatible after the 1.0 release:

| Type | Purpose |
|---|---|
| `trace.TraceSpan`, `trace.TraceDisabled` | Declare or exclude method-level spans |
| `trace.TraceBlock`, `trace.SpanKind` | Wrap custom spans in code |
| `trace.TraceIdContext` | Read the current trace ID (`currentTraceId()`, `MDC_KEY`) |
| `trace.TraceContextTaskDecorator` | Propagate the trace context across executors |
| `web.ObservabilityAccessPolicy` | Dashboard and API authorization extension point |
| `integration.observation.ObservationTraceIdResolver` | Extract a propagated trace ID from another framework's observation context; define a bean of this type to enable it |
| `integration.feign.TraceIdRequestInterceptor`, `integration.kafka.TraceIdRecordInterceptor` | Register when building clients with `Feign.builder()` by hand or using a custom Kafka `RecordInterceptor` |
| `config.MeerkatProperties` | `meerkat.*` settings |

All packages start with `io.github.lakernote.meerkat`. Auto-configuration classes (the `autoconfigure` package) are only meant to be excluded by name.

Types in packages containing `internal` are implementation details, such as trace storage, metric storage, controllers and filters. Even when `public`, they may change in any release; do not depend on them. Beans for these internal components cannot be replaced with beans of the same type; use the extension points or settings above instead.
