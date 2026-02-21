# Export telemetry with OTLP

OTLP ([OpenTelemetry Protocol](https://opentelemetry.io/docs/specs/otlp/)) is the default way to export telemetry
from OpenTelemetry SDKs.
In `otel4s`, OTLP exporters can send traces, metrics, and logs to OTLP-compatible backends such
as [OpenTelemetry Collector](https://opentelemetry.io/docs/collector/), [Grafana LGTM](https://grafana.com/docs/opentelemetry/docker-lgtm/),
[Grafana Tempo](https://grafana.com/oss/tempo/), [Jaeger](https://www.jaegertracing.io/).

Use OTLP when you want one transport/protocol family for all telemetry signals, consistent configuration, and broad
backend compatibility.

`otel4s-sdk` OTLP implementation supports `grpc/protobuf`, `http/protobuf`, and `http/json`. 
By default, OTLP exporter uses `http/protobuf` and sends telemetry to `http://localhost:4318/`.

## Getting Started

@:select(build-tool)

@:choice(sbt)

Add settings to `build.sbt`:

```scala
libraryDependencies ++= Seq(
  "org.typelevel" %%% "otel4s-sdk" % "@VERSION@", // <1>
  "org.typelevel" %%% "otel4s-sdk-exporter" % "@VERSION@" // <2>
)
```

@:choice(scala-cli)

Add directives to the `*.scala` file:

```scala
//> using dep "org.typelevel::otel4s-sdk::@VERSION@" // <1>
//> using dep "org.typelevel::otel4s-sdk-exporter::@VERSION@" // <2>
```

@:@

1. Add the `otel4s-sdk` library.
2. Add the `otel4s-sdk-exporter` library (includes OTLP exporters autoconfiguration).

## Configuration

`OpenTelemetrySdk.autoConfigured(...)` reads standard OpenTelemetry environment variables and system properties.
See [SDK configuration settings](../sdk/configuration.md) for the full list of options.

@:select(sdk-options-source)

@:choice(sbt)

Add settings to `build.sbt`:

```scala
javaOptions ++= Seq(
  "-Dotel.service.name=orders-api",
  "-Dotel.exporter.otlp.endpoint=http://localhost:4318",
  "-Dotel.exporter.otlp.protocol=http/protobuf"
)
```

@:choice(scala-cli)

Add directives to the `*.scala` file:

```scala
//> using javaOpt -Dotel.service.name=orders-api
//> using javaOpt -Dotel.exporter.otlp.endpoint=http://localhost:4318
//> using javaOpt -Dotel.exporter.otlp.protocol=http/protobuf
```

@:choice(shell)

```shell
$ export OTEL_SERVICE_NAME=orders-api
$ export OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318
$ export OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf
```

@:@

You can configure OTLP globally (`otel.exporter.otlp.*`) or per signal (`otel.exporter.otlp.traces.*`,
`otel.exporter.otlp.metrics.*`, `otel.exporter.otlp.logs.*`).
Per-signal settings take precedence over global settings.

You can also mix exporters by signal. For example, use OTLP for traces and Prometheus for metrics
(`otel.traces.exporter=otlp`, `otel.metrics.exporter=prometheus`).
If you mix them, also register the Prometheus configurer in code, for example:
`_.addMetricExporterConfigurer(PrometheusMetricExporterAutoConfigure[IO])`.
See the [Prometheus guide](prometheus-exporter.md) for more details.

## Autoconfigured OTLP Exporters

```scala mdoc:silent:reset
import cats.effect.{IO, IOApp}
import org.typelevel.otel4s.sdk.OpenTelemetrySdk
import org.typelevel.otel4s.sdk.exporter.otlp.autoconfigure.OtlpExportersAutoConfigure

object TelemetryApp extends IOApp.Simple {
  def run: IO[Unit] =
    OpenTelemetrySdk
      .autoConfigured[IO](
        // register OTLP exporters for traces, metrics, and logs
        _.addExportersConfigurer(OtlpExportersAutoConfigure[IO])
      )
      .use { configured =>
        val sdk = configured.sdk
        for {
          tracer <- sdk.tracerProvider.get("orders-api")
          meter <- sdk.meterProvider.meter("orders-api").get
          counter <- meter.counter[Long]("orders.requests").create
          _ <- tracer.span("orders.request").surround(counter.inc())
        } yield ()
      }
}
```

## Run the app

@:select(build-tool)

@:choice(sbt)

```shell
sbt "runMain TelemetryApp"
```

@:choice(scala-cli)

```shell
scala-cli run .
```

@:@

## Verify

1. Check your backend for telemetry from `orders-api`.
2. Confirm at least one span `orders.request` and one metric `orders.requests`.
3. If export fails, verify protocol and port match:
   `4318` for HTTP (`http/protobuf`, `http/json`), `4317` for `grpc`.

## Troubleshooting

- No data in backend: ensure `OTEL_SERVICE_NAME` is set and backend endpoint is reachable.
- HTTP protocol but gRPC port: switch endpoint to `:4318` or protocol to `grpc`.
- Unexpected paths: when using global `otel.exporter.otlp.endpoint`, provide a base URL (the SDK appends `/v1/{signal}`
  for HTTP exporters).
- Different endpoints per signal: set `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT`, `OTEL_EXPORTER_OTLP_METRICS_ENDPOINT`,
  `OTEL_EXPORTER_OTLP_LOGS_ENDPOINT`.
