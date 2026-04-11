# Testing with testkit

The `otel4s-sdk-testkit` provides in-memory implementations of metric, trace, and log exporters.
In-memory data can be used to test the structure of the spans, the names of instruments, and many more.

The testkit is framework-agnostic, so it can be used with any test framework, such as weaver, munit, or scalatest.

## Getting started

@:select(build-tool)

@:choice(sbt)

Add settings to the `build.sbt`:

```scala
libraryDependencies += "org.typelevel" %%% "otel4s-sdk-testkit" % "@VERSION@" % Test // <1>
```

@:choice(scala-cli)

Add directives to the `*.scala` file:

```scala
//> using test.dep "org.typelevel::otel4s-sdk-testkit::@VERSION@" // <1>
```

@:@

1. Add the `otel4s-sdk-testkit` library

## Testing metrics

The testkit provides a dedicated expectation API built on top of the `MetricData`.
This allows tests to match only the parts that matter: metric name, kind, values, point attributes,
instrumentation scope, telemetry resource, summaries, histograms, and more.

For the metrics expectation API, see the dedicated [Metrics testkit guide](testkit-metrics.md).

____

Let's assume we have a program that increments a counter by one and sets the gauge's value to 42. 
Here is how we can test this program:

```scala mdoc:silent:reset
import cats.effect.IO
import org.typelevel.otel4s.metrics.MeterProvider
import org.typelevel.otel4s.sdk.metrics.data.{MetricData, MetricPoints, PointData}
import org.typelevel.otel4s.sdk.testkit.OpenTelemetrySdkTestkit

// the program that we want to test 
def program(meterProvider: MeterProvider[IO]): IO[Unit] =
  for {
    meter <- meterProvider.get("service")
    
    counter <- meter.counter[Long]("service.counter").create
    _ <- counter.inc()

    gauge <- meter.gauge[Long]("service.gauge").create
    _ <- gauge.record(42L)
  } yield ()

// the test
def test: IO[Unit] = 
  OpenTelemetrySdkTestkit.inMemory[IO]().use { testkit =>
    // the list of expected metrics
    val expected = List(
      TelemetryMetric.SumLong("service.counter", Vector(1L)),
      TelemetryMetric.GaugeLong("service.gauge", Vector(42L))
    )
    
    for {
      // invoke the program
      _ <- program(testkit.meterProvider)
      // collect the metrics
      metrics <- testkit.collectMetrics
      // verify the collected metrics
      _ <- assertMetrics(metrics, expected)
    } yield ()
  }
  
// here you can use an assertion mechanism from your favorite testing framework
def assertMetrics(metrics: List[MetricData], expected: List[TelemetryMetric]): IO[Unit] =
  IO {
    assert(metrics.sortBy(_.name).map(TelemetryMetric.fromMetricData) == expected)
  }
  
// a minimized representation of the MetricData to simplify testing
sealed trait TelemetryMetric
object TelemetryMetric {
  case class SumLong(name: String, values: Vector[Long]) extends TelemetryMetric
  case class SumDouble(name: String, values: Vector[Double]) extends TelemetryMetric

  case class GaugeLong(name: String, values: Vector[Long]) extends TelemetryMetric
  case class GaugeDouble(name: String, values: Vector[Double]) extends TelemetryMetric

  case class Histogram(name: String, values: Vector[Double]) extends TelemetryMetric

  def fromMetricData(metric: MetricData): TelemetryMetric =
    metric.data match {
      case sum: MetricPoints.Sum =>
        val (doubles, longs) = split(sum.points.toVector)
        if (doubles.nonEmpty) SumDouble(metric.name, doubles)
        else SumLong(metric.name, longs)

      case gauge: MetricPoints.Gauge =>
        val (doubles, longs) = split(gauge.points.toVector)
        if (doubles.nonEmpty) GaugeDouble(metric.name, doubles)
        else GaugeLong(metric.name, longs)

      case histogram: MetricPoints.Histogram =>
        Histogram(
          metric.name,
          histogram.points.toVector.flatMap(_.stats.map(_.sum))
        )

      case exponentialHistogram: MetricPoints.ExponentialHistogram =>
        Histogram(
          metric.name,
          exponentialHistogram.points.toVector.flatMap(_.stats.map(_.sum))
        )
    }

  private def split(points: Vector[PointData.NumberPoint]): (Vector[Double], Vector[Long]) =
    points.partitionMap {
      case point: PointData.LongNumber    => Right(point.value)
      case double: PointData.DoubleNumber => Left(double.value)
    }
}
```

`MetricData` contains far more than most tests need:
metric metadata, resource and scope details, data points, attributes, collection windows, exemplars, and more.

In tests, it is often more maintainable to assert on a focused projection of the signal.
In this example, `TelemetryMetric` keeps only the pieces we care about and ignores noisy fields.

```scala mdoc:invisible
// we silently run the test to ensure it's actually correct
import cats.effect.unsafe.implicits.global
test.unsafeRunSync()
```

## Testing spans

The testkit also provides a dedicated structural expectation API for traces.
It can match the full exported forest, nested subtrees, ordered or unordered children,
timestamps, attributes, events, links, scope, resource, and more.

For the traces expectation API, see the dedicated [Traces testkit guide](testkit-traces.md).

____

Let's assume we want to test the structure of created spans:

```scala mdoc:reset
import cats.effect.IO
import org.typelevel.otel4s.sdk.testkit.OpenTelemetrySdkTestkit
import org.typelevel.otel4s.sdk.testkit.trace._
import org.typelevel.otel4s.trace.TracerProvider
import scala.concurrent.duration._

// the program that we want to test 
def program(tracerProvider: TracerProvider[IO]): IO[Unit] =
  for {
    tracer <- tracerProvider.get("service")
    _ <- tracer.span("app.span").surround {
      tracer.span("app.nested.1").surround(IO.sleep(200.millis)) >>
      tracer.span("app.nested.2").surround(IO.sleep(300.millis))
    }
  } yield ()

// the test
def test: IO[Unit] =
  OpenTelemetrySdkTestkit.inMemory[IO]().use { testkit =>
    val expected =
      TraceForestExpectation.unordered(
        TraceExpectation.unordered(
          SpanExpectation.name("app.span").noParentSpanContext,
          TraceExpectation.leaf(SpanExpectation.name("app.nested.1")),
          TraceExpectation.leaf(SpanExpectation.name("app.nested.2"))
        )
      )

    for {
      _ <- program(testkit.tracerProvider)
      spans <- testkit.finishedSpans
    } yield assertExpected(spans, expected)
  }

def assertExpected(spans: List[org.typelevel.otel4s.sdk.trace.data.SpanData], expected: TraceForestExpectation): Unit =
  TraceExpectations.check(spans, expected) match {
    case Right(_) =>
      ()
    case Left(mismatches) =>
      sys.error(TraceExpectations.format(mismatches))
  }
```

The trace expectation API removes the need to build a custom tree model in most tests.
You can still keep expectations focused by matching only span names, or gradually add more detail:
timestamps, attributes, events, links, scope, and resource.

```scala mdoc:invisible
import cats.effect.unsafe.implicits.global
test.unsafeRunSync()
```
