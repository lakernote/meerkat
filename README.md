<p align="center">
  <img src="docs/images/meerkat-icon.png" alt="Meerkat" width="112">
</p>

<h1 align="center">Meerkat</h1>

<p align="center">
  <b>Embedded trace diagnostics and runtime metrics for Spring Boot 3/4.</b><br>
  Add one dependency. No agent, no collector, no code changes.
</p>

<p align="center">
  <a href="https://github.com/lakernote/meerkat/actions/workflows/ci.yml"><img src="https://github.com/lakernote/meerkat/actions/workflows/ci.yml/badge.svg?branch=main" alt="CI"></a>
  <a href="https://central.sonatype.com/artifact/io.github.lakernote/meerkat-spring-boot-3-starter"><img src="https://img.shields.io/maven-central/v/io.github.lakernote/meerkat-spring-boot-3-starter?label=Boot%203%20Starter&logo=apachemaven" alt="Boot 3 Starter"></a>
  <a href="https://central.sonatype.com/artifact/io.github.lakernote/meerkat-spring-boot-4-starter"><img src="https://img.shields.io/maven-central/v/io.github.lakernote/meerkat-spring-boot-4-starter?label=Boot%204%20Starter&logo=apachemaven" alt="Boot 4 Starter"></a>
  <img src="https://img.shields.io/badge/Java-17%2B-blue?logo=openjdk" alt="Java 17+">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-Apache%202.0-green" alt="Apache 2.0"></a>
</p>

<p align="center">English | <a href="README.zh-CN.md">简体中文</a></p>

Meerkat answers two everyday questions from inside your application:

- **Why was this request slow, and where did the error start?** Follow a timeline through SQL, Redis, HTTP calls, Kafka and async threads.
- **What changed recently?** See which endpoints, SQL statements and dependencies are slowest, busiest or failing, and how JVM, connection pools and executors behave over time.

No separate observability platform is needed to get started, and it works alongside the one you already have.

Designed to stay in the application for everyday problem detection and diagnosis: metrics and diagnostic traces run continuously; bodies, SQL parameters and result data are collected only during a traffic capture explicitly started by an authorized user.

![Trace detail timeline](docs/images/en-trace-detail-timeline.png)

## Features

- **Trace diagnostics** – call trees for HTTP, scheduled-job and Kafka-consumer entries that are slow, fail, or exceed CPU-time or allocation thresholds. Repeated SQL in a loop is merged and flagged as N+1.
- **Operation ranking** – count, error rate, average/P95/max and total time for every endpoint, SQL statement, remote call, Redis command, job and Kafka topic, plus CPU and allocation per request. Last hour to 30 days; survives restarts.
- **Metric trends** – JVM memory and GC, CPU, threads, Hikari pools, executors and Tomcat, reusing the Micrometer meters Spring Boot already binds.
- **Live diagnostics** – in-flight requests and the step they are stuck in, threads ranked by CPU, deadlock detection, merged stacks, and thread dumps. Read on demand, never sampled in the background.
- **Error groups** – failed traces grouped by error type and message with counts, trend and a "new" mark, so a burst of identical failures reads as one problem.
- **Traffic capture** – on demand, record everything a matching request did: request and response bodies, SQL with parameters and results, Redis commands and replies, and outbound HTTP calls, tied together by trace ID. Available to whoever `canCapture()` allows (nobody by default); see [Traffic capture](docs/guide.md#traffic-capture).
- **Toolbox** – on-demand diagnostics without a Java agent: temporary log levels that revert by themselves, a CPU sampling flame graph, heap histograms with growth since the last one, heap dumps, where a configuration property comes from, and which jar a class is loaded from, including duplicate classes. See [Toolbox](docs/guide.md#toolbox).
- **Self-contained** – data lives in local files with retention and size limits. Your Prometheus or other Micrometer exports are untouched.

## Getting started

Add the starter that matches your Spring Boot version (Java 17+, Spring Boot 3.5+ / 4.0+, Servlet stack; WebFlux is not supported, see [Requirements](docs/guide.md#requirements)):

```xml
<!-- Spring Boot 3 -->
<dependency>
    <groupId>io.github.lakernote</groupId>
    <artifactId>meerkat-spring-boot-3-starter</artifactId>
    <version>0.1.0-alpha.4</version>
</dependency>

<!-- Spring Boot 4 -->
<dependency>
    <groupId>io.github.lakernote</groupId>
    <artifactId>meerkat-spring-boot-4-starter</artifactId>
    <version>0.1.0-alpha.4</version>
</dependency>
```

Start the application and open `http://localhost:8080/meerkat/` (the address is also logged at startup). The dashboard follows the browser language (English or Chinese) and can be switched in the top bar.

> [!IMPORTANT]
> The dashboard and API are open to anyone who can reach the application port. In production, protect `/meerkat/**` and `/api/meerkat/**` with Spring Security or your gateway, or provide an [`ObservabilityAccessPolicy`](docs/guide.md#dashboard-and-api-access).

## What is traced automatically

| Mode | When it runs | How data is collected and kept |
| --- | --- | --- |
| Trace | Continuously by default | Build bounded call trees in memory; keep entries that are slow, fail, or exceed entry-thread CPU-time or allocation thresholds |
| Metrics | Continuously by default | Aggregate counts, errors and timings from the same collection points as traces; reuse Micrometer resource meters and periodically save trends |
| Traffic capture | Started by an authorized user | Match HTTP requests by rules; additionally record bodies, SQL parameters and results, Redis arguments and replies, and outbound HTTP data, subject to capture limits |

These modes use the following integrations:

| Component | Covered without code | How it is collected |
| --- | --- | --- |
| Entries | Spring MVC requests, `@Scheduled` jobs, `@KafkaListener` | Spring Observations create entry traces and provide timing, status and errors |
| Database | JDBC via compatible `DataSource` beans (MyBatis, JPA, JdbcTemplate), transactions | SQL execution listeners on data-source proxies; Spring transaction listeners |
| Redis | Lettuce; Spring Data Redis on Redisson's connection factory | Lettuce Observations; proxies around Redisson connection-factory commands |
| HTTP clients | `RestTemplate`, `RestClient`, `@FeignClient`, `OkHttpClient` beans | Client Observations or interceptors record calls and propagate the trace ID |
| Messaging | `KafkaTemplate` send and listener receive | Spring Kafka Observations and message headers link producer and consumer traces |
| Async | `@Async`, `ThreadPoolTaskExecutor` and `SimpleAsyncTaskExecutor` beans | A `TaskDecorator` propagates trace context and records task steps, timings and errors |

Custom steps can be added with `@TraceSpan` or `TraceBlock`. Exact limits are in the [guide](docs/guide.md#what-is-collected-automatically).

**Works with Micrometer Tracing / OpenTelemetry.** When Micrometer Tracing is present, Meerkat uses the same trace ID, so a slow request found here can be looked up in Zipkin, Jaeger or Tempo. An upstream W3C `traceparent` header is honored; see [Trace ID in logs](docs/guide.md#trace-id-in-logs).

## Overhead

Time added on the business thread (JMH, Apple M4 Pro, JDK 21):

| Scenario | Added time |
| --- | --- |
| One entry (HTTP request, job, Kafka message) | ~1.7 µs |
| Each dependency call (Redis, HTTP, Kafka send, ...) | ~0.3 µs |
| SQL listener processing per statement | ~0.1–0.2 µs |

A request with 20 dependency calls adds about 7 µs: under 1% for a 1 ms request, under 0.1% for a 10 ms request. The SQL numbers cover listener processing, not the full JDBC proxy chain. Disk writes happen on a background thread. See [Overhead](docs/guide.md#overhead) for details.

## Screenshots

| Diagnostic traces | Operation ranking |
| --- | --- |
| ![Trace list](docs/images/en-trace-list.png) | ![Operation ranking](docs/images/en-stats-ranking.png) |
| **Metric trends** | **Live diagnostics** |
| ![Metric trends](docs/images/en-metrics-dashboard.png) | ![Live diagnostics](docs/images/en-live-requests.png) |

## Try it locally

The example app covers JDBC, Redis, remote HTTP, Kafka, async executors and resource-heavy scenarios:

```bash
docker compose -f examples/docker-compose.yml up -d --wait
mvn -pl examples/meerkat-example-spring-boot-3 -am clean package -DskipTests
java -jar examples/meerkat-example-spring-boot-3/target/meerkat-example-spring-boot-3-*.jar
```

Open `http://localhost:8083/` and click the scenarios. See [examples](examples/README.md) for the Boot 4 variant.

## How it compares

| | Meerkat | JavaMelody | SkyWalking / Pinpoint | Prometheus + Grafana |
|---|---|---|---|---|
| Deployment | In-app starter | In-app dependency | Agent + collector, storage, UI | Separate servers |
| Per-request call tree | Slow / failed / resource-heavy entries | No | Yes, across services | No |
| Aggregates and trends | Ranking with P95, errors, CPU, allocation; resource trends | Yes | Yes | Yes, best querying and alerting |
| Live diagnostics | In-flight step, thread CPU, deadlocks, dumps | Requests, threads, heap histogram | Some products | No |
| Best for | A monolith or a few services | Classic Java web apps | Many services, central ops | Central metrics and alerting |

Meerkat does not replace a central APM or metrics platform; it gives each service a built-in view and coexists with them.

## Documentation

- [Configuration guide](docs/guide.md): requirements, compatibility matrix, settings, storage, access control, logging, public API
- [Examples](examples/README.md)
- [Benchmarks](benchmarks/README.md)
- [Contributing](CONTRIBUTING.md) · [Security](SECURITY.md) · [Code of conduct](CODE_OF_CONDUCT.md)

## Status

Meerkat is in **alpha**. Configuration and public API may still change before 1.0; see [Public API](docs/guide.md#public-api) for what will stay stable.

## License

[Apache License 2.0](LICENSE)
