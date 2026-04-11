# Metrics | Runtime

`otel4s-sdk-contrib-metrics` can register runtime metrics for all supported platforms:

- JVM
- Scala.js on Node.js
- Scala Native

These metrics are not enabled by the SDK autoconfiguration automatically. You register them explicitly in your
application, and they are exported by whatever metric reader/exporter configuration your SDK already uses.

## Getting started

Add the runtime metrics module:

@:select(build-tool)

@:choice(sbt)

```scala
libraryDependencies ++= Seq(
  "org.typelevel" %%% "otel4s-sdk-contrib-metrics" % "@VERSION@" // <1>
)
```

@:choice(scala-cli)

```scala
//> using dep "org.typelevel::otel4s-sdk-contrib-metrics::@VERSION@" // <1>
```

@:@

1. Add the runtime metrics module

## Registering runtime metrics

Runtime metrics use the same `MeterProvider[F]` as the rest of your application. The returned `Resource` manages the
lifecycle of all registered observers and background tasks.

```scala mdoc:silent
import cats.effect.{IO, IOApp}
import org.typelevel.otel4s.metrics.MeterProvider
import org.typelevel.otel4s.sdk.OpenTelemetrySdk
import org.typelevel.otel4s.sdk.contrib.metrics.runtime.RuntimeMetrics

object Main extends IOApp.Simple {

  def run: IO[Unit] =
    OpenTelemetrySdk.autoConfigured[IO]().use { autoConfigured =>
      implicit val meterProvider: MeterProvider[IO] =
        autoConfigured.sdk.meterProvider

      RuntimeMetrics.register[IO].surround {
        program
      }
    }

  private def program: IO[Unit] =
    IO.never
}
```

If you already export application metrics through OTLP, Prometheus, or another configured exporter, runtime metrics are
exported the same way. Exporter and reader settings are documented in [SDK configuration](../sdk/configuration.md).

## Configuration model

Runtime metrics are configured in code via `RuntimeMetrics.Config`.

- `RuntimeMetrics.register[IO]` uses `RuntimeMetrics.Config.enabledAll`
- `RuntimeMetrics.register[IO](config)` uses the supplied platform-specific config

There are no dedicated environment variables or system properties for enabling or disabling individual runtime metric
groups. Runtime metric collection itself is controlled in code; export behavior is controlled by the SDK metric
configuration.

## Platform support

### JVM

Available config switches:

- `withClassMetricsEnabled` / `withClassMetricsDisabled`
- `withCpuMetricsEnabled` / `withCpuMetricsDisabled`
- `withGcMetricsEnabled` / `withGcMetricsDisabled`
- `withMemoryPoolMetricsEnabled` / `withMemoryPoolMetricsDisabled`
- `withThreadMetricsEnabled` / `withThreadMetricsDisabled`
- `withGcMetricsBucketBoundaries(...)`

Example:

```scala mdoc:silent:reset
import cats.effect.{IO, Resource}
import org.typelevel.otel4s.metrics.{BucketBoundaries, MeterProvider}
import org.typelevel.otel4s.sdk.contrib.metrics.runtime.RuntimeMetrics

val config =
  RuntimeMetrics.Config.disabledAll
    .withCpuMetricsEnabled
    .withMemoryPoolMetricsEnabled
    .withGcMetricsEnabled
    .withGcMetricsBucketBoundaries(BucketBoundaries(0.005, 0.01, 0.05, 0.1, 1.0))

def register(implicit provider: MeterProvider[IO]): Resource[IO, Unit] = 
  RuntimeMetrics.register[IO](config)
```

Exported metrics:

- Class metrics: `jvm.class.count`, `jvm.class.loaded`, `jvm.class.unloaded`
- CPU metrics: `jvm.cpu.count`, `jvm.cpu.recent_utilization`, `jvm.cpu.time`
- GC metrics: `jvm.gc.duration`
- Memory metrics: `jvm.memory.committed`, `jvm.memory.limit`, `jvm.memory.used`, `jvm.memory.used_after_last_gc`
- Thread metrics: `jvm.thread.count`

Notes:

- The metrics are emitted with the instrumentation scope name `org.typelevel.otel4s.sdk.runtime`.
- `jvm.gc.duration` uses the configured histogram bucket boundaries.
- GC duration reporting depends on JVM GC notifications. If `com.sun.management.GarbageCollectionNotificationInfo` is
  unavailable, GC metrics are skipped and a diagnostic error is emitted.

### Scala.js

Scala.js runtime metrics target Node.js runtimes. They depend on Node APIs such as `node:perf_hooks`, so they are not
intended for browser environments.

Available config switches:

- `withProcessCpuMetricsEnabled` / `withProcessCpuMetricsDisabled`
- `withProcessMemoryMetricsEnabled` / `withProcessMemoryMetricsDisabled`
- `withNodeGcMetricsEnabled` / `withNodeGcMetricsDisabled`
- `withNodeEventLoopMetricsEnabled` / `withNodeEventLoopMetricsDisabled`
- `withNodeMemoryMetricsEnabled` / `withNodeMemoryMetricsDisabled`
- `withEventLoopMonitoringPrecision(...)`

Example:

```scala
import cats.effect.{IO, Resource}
import org.typelevel.otel4s.metrics.MeterProvider
import org.typelevel.otel4s.sdk.contrib.metrics.runtime.RuntimeMetrics

import scala.concurrent.duration._

val config =
  RuntimeMetrics.Config.enabledAll
    .withEventLoopMonitoringPrecision(20.millis)
    .withNodeGcMetricsDisabled

def register(implicit provider: MeterProvider[IO]): Resource[IO, Unit] = 
  RuntimeMetrics.register[IO](config)
```

Exported metrics:

- Process CPU metrics: `process.cpu.time`, `process.cpu.utilization`
- Process memory metrics: `process.memory.usage`
- V8 GC metrics: `v8js.gc.duration`
- Node.js event loop metrics:
  `nodejs.eventloop.time`,
  `nodejs.eventloop.utilization`,
  `nodejs.eventloop.delay.min`,
  `nodejs.eventloop.delay.max`,
  `nodejs.eventloop.delay.mean`,
  `nodejs.eventloop.delay.stddev`,
  `nodejs.eventloop.delay.p50`,
  `nodejs.eventloop.delay.p90`,
  `nodejs.eventloop.delay.p99`
- V8 memory metrics:
  `v8js.memory.heap.limit`,
  `v8js.memory.heap.used`,
  `v8js.heap.space.available_size`,
  `v8js.heap.space.physical_size`

Notes:

- `eventLoopMonitoringPrecision` controls the resolution used by the Node event loop delay monitor.
- `nodejs.eventloop.time` exports one time series per `nodejs.eventloop.state` with `active` and `idle` values.

### Scala Native

Available config switches:

- `withCpuMetricsEnabled` / `withCpuMetricsDisabled`
- `withGcMetricsEnabled` / `withGcMetricsDisabled`
- `withMemoryPoolMetricsEnabled` / `withMemoryPoolMetricsDisabled`
- `withThreadMetricsEnabled` / `withThreadMetricsDisabled`
- `withGcMetricsRefreshRate(...)`
- `withGcMetricsBucketBoundaries(...)`

Example:

```scala
import cats.effect.{IO, Resource}
import org.typelevel.otel4s.metrics.{BucketBoundaries, MeterProvider}
import org.typelevel.otel4s.sdk.contrib.metrics.runtime.RuntimeMetrics

import scala.concurrent.duration._

val config =
  RuntimeMetrics.Config.enabledAll
    .withGcMetricsRefreshRate(1.second)
    .withGcMetricsBucketBoundaries(BucketBoundaries(0.01, 0.1, 1.0, 5.0))

def register(implicit provider: MeterProvider[IO]): Resource[IO, Unit] = 
  RuntimeMetrics.register[IO](config)
```

Exported metrics:

- CPU metrics: `scalanative.cpu.count`, `scalanative.cpu.time`
- GC metrics: `scalanative.gc.duration`
- Memory metrics: `scalanative.memory.committed`, `scalanative.memory.limit`, `scalanative.memory.used`
- Thread metrics: `scalanative.thread.count`

Notes:

- The metric set follows JVM runtime metric semantics where practical, but uses the `scalanative.` prefix.
- `gcMetricsRefreshRate` controls how often GC state is sampled.
- `scalanative.gc.duration` uses the configured histogram bucket boundaries.

## What gets exported

Runtime metrics are regular SDK metrics:

- they use your application's `MeterProvider`
- they are attached to the same telemetry resource as the rest of your SDK metrics
- they are exported by the configured metric reader/exporter pipeline

If you use `OpenTelemetrySdk.autoConfigured`, runtime metrics inherit:

- resource attributes such as `service.name`
- configured metric exporters such as OTLP, Prometheus, or console
- reader settings such as `otel.metric.export.interval`

The instrumentation scope used by these metrics is:

```text
org.typelevel.otel4s.sdk.runtime
```

If your resource detectors are enabled, exported metrics will also include process and runtime resource attributes such
as `process.runtime.name`, `process.runtime.version`, and `process.runtime.description` where supported.
See [SDK configuration](../sdk/configuration.md#telemetry-resource) for resource detector settings.
