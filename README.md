# otel4s-sdk

![Typelevel Organization Project](https://img.shields.io/badge/typelevel-organization%20project-FF6169.svg)
[![otel4s-sdk Scala version support](https://index.scala-lang.org/typelevel/otel4s-sdk/otel4s-sdk/latest.svg)](https://index.scala-lang.org/typelevel/otel4s-sdk/otel4s-sdk)

`otel4s-sdk` is a pure Scala OpenTelemetry SDK for:

- JVM
- Scala.js
- Scala Native

It provides the runtime backend for telemetry pipelines in applications instrumented with
[otel4s](https://github.com/typelevel/otel4s), including:

- SDK providers for traces, metrics, and logs
- processors/readers and signal pipeline wiring
- exporters (OTLP and Prometheus via dedicated modules)
- OpenTelemetry auto-configuration support
- testkit modules for in-memory verification

The implementation is compliant with large parts of the OpenTelemetry specification, but it is still **experimental**.

## Who should use it

Use `otel4s-sdk` if you are building an application and need to run and configure telemetry pipelines.

If you are a library author, prefer [otel4s](https://github.com/typelevel/otel4s) core APIs so application users can
choose their backend, such as `otel4s-sdk` or `otel4s-oteljava`.

## Quick start

Add dependencies:

```scala
libraryDependencies ++= Seq(
  "org.typelevel" %%% "otel4s-sdk" % "@VERSION@",
  "org.typelevel" %%% "otel4s-sdk-exporter" % "@VERSION@"
)
```

Then autoconfigure:

```scala
import cats.effect.{IO, IOApp}
import org.typelevel.otel4s.sdk.OpenTelemetrySdk
import org.typelevel.otel4s.sdk.exporter.otlp.autoconfigure.OtlpExportersAutoConfigure
import org.typelevel.otel4s.trace.TracerProvider

object TelemetryApp extends IOApp.Simple {
  def run: IO[Unit] =
    OpenTelemetrySdk
      .autoConfigured[IO](
        _.addExportersConfigurer(OtlpExportersAutoConfigure[IO])
      )
      .use { configured =>
        val sdk = configured.sdk
        program(sdk.tracerProvider)
      }

  def program(tracerProvider: TracerProvider[IO]): IO[Unit] =
    tracerProvider.get("example-service").flatMap { tracer =>
      tracer.span("startup").surround(IO.println("app started"))
    }
}
```

Configuration is controlled by OpenTelemetry environment variables and system properties, for example
`OTEL_SERVICE_NAME`.

The instrumentation APIs in `program` come from [otel4s](https://github.com/typelevel/otel4s). `otel4s-sdk` provides
the runtime backend that executes and exports telemetry.

Expected result with an OTLP collector running:

```bash
export OTEL_SERVICE_NAME=telemetry-app
export OTEL_TRACES_EXPORTER=otlp
export OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317
```

After starting the app, a `startup` span is exported to your OTLP backend.

## Module overview

Main modules:

- `otel4s-sdk` - aggregate SDK module
- `otel4s-sdk-exporter` - OTLP exporters bundle
- `otel4s-sdk-exporter-prometheus` - Prometheus metrics exporter
- `otel4s-sdk-testkit` - in-memory test support for spans/metrics
- `otel4s-sdk-contrib-aws-resource` - AWS resource detectors
- `otel4s-sdk-contrib-aws-xray` - AWS X-Ray IDs
- `otel4s-sdk-contrib-aws-xray-propagator` - AWS X-Ray propagator

Repository layout:

- `sdk/` - SDK signal modules (`common`, `trace`, `metrics`, `logs`, `all`)
- `sdk-exporter/` - exporters and OTLP/proto modules
- `sdk-contrib/aws/` - AWS integrations
- `examples/` - runnable examples

## Specification compliance and limitations

Specification coverage is tracked in:

- [Compliance matrix](https://typelevel.org/otel4s-sdk/compliance/compliance-matrix.html)

Experimental status guidance:

- Treat this implementation as evolving; APIs and behavior may still change
- Confirm required features in the compliance matrix before production rollout
- Validate your target setup in staging before broad production adoption

Current known limitations include:

- No SPI-style autoloading for third-party components
- The exponential histogram aggregation is not implemented yet
