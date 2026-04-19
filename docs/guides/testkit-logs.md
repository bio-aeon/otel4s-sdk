# Testkit | Logs

The logs testkit provides a partial-matching expectation API for SDK `LogRecordData`.

This is useful in tests because log record data contains much more than the fields you usually care about:
timestamps, trace correlation, instrumentation scope, telemetry resource, structured body values, and more.

The expectation API lets you assert only the relevant parts of a log record while still preserving useful mismatch
details.

## Getting started

@:select(build-tool)

@:choice(sbt)

Add settings to the `build.sbt`:

```scala
libraryDependencies += "org.typelevel" %%% "otel4s-sdk-testkit" % "@VERSION@" % Test
```

@:choice(scala-cli)

Add directives to the `*.scala` file:

```scala
//> using test.dep "org.typelevel::otel4s-sdk-testkit::@VERSION@"
```

@:@

## Basic flow

The usual flow is:

1. Run your program against `OpenTelemetrySdkTestkit`
2. Collect logs as `LogRecordData`
3. Build `LogRecordExpectation` values
4. Check them with `LogRecordExpectations.checkAll`

```scala mdoc:silent:reset
import cats.effect.IO
import org.typelevel.otel4s.logs.LoggerProvider
import org.typelevel.otel4s.AnyValue
import org.typelevel.otel4s.sdk.context.Context
import org.typelevel.otel4s.sdk.logs.data.LogRecordData
import org.typelevel.otel4s.sdk.testkit.OpenTelemetrySdkTestkit
import org.typelevel.otel4s.sdk.testkit.logs._

def program(loggerProvider: LoggerProvider[IO, Context]): IO[Unit] =
  for {
    logger <- loggerProvider.get("service")
    _ <- logger.logRecordBuilder
      .withBody(AnyValue.string("request failed"))
      .emit
  } yield ()

def assertExpected(logs: List[LogRecordData], expected: LogRecordExpectation*): Unit =
  LogRecordExpectations.checkAll(logs, expected.toList) match {
    case Right(_) =>
      ()
    case Left(mismatches) =>
      sys.error(LogRecordExpectations.format(mismatches))
  }

def test: IO[Unit] =
  OpenTelemetrySdkTestkit.inMemory[IO]().use { testkit =>
    for {
      _ <- program(testkit.loggerProvider)
      logs <- testkit.collectLogs
    } yield assertExpected(
      logs,
      LogRecordExpectation.message("request failed")
    )
  }
```

`checkAll(...)` is non-consuming: each expectation is checked independently against the full collected log list.
If you need to ensure that repeated expectations match different collected log records, use
`LogRecordExpectations.checkAllDistinct(...)` instead.

## Partial matching

All expectations are partial.

This means:

- unspecified properties are ignored
- you can assert only the parts that matter for a test
- you can still add detail when needed

For example:

```scala mdoc:silent
LogRecordExpectation.message("request failed")
```

matches any collected log record whose body is exactly the string `"request failed"`, regardless of severity,
attributes, scope, or resource.

## Body, severity, and event name

Use `message(...)` for string bodies and `body(...)` for arbitrary `AnyValue`.

```scala mdoc:silent
LogRecordExpectation.message("request failed")

LogRecordExpectation
  .body(AnyValue.map(Map("status" -> AnyValue.string("failed"))))
  .severity(org.typelevel.otel4s.logs.Severity.error)
  .eventName("log.failure")
```

## Trace correlation

Log expectations can also match trace correlation:

```scala mdoc:silent
LogRecordExpectation
  .message("request failed")
  .traceId("0af7651916cd43dd8448eb211c80319c")
  .spanId("b7ad6b7169203331")
```

Use `untraced` to require that no trace context is attached:

```scala mdoc:silent
LogRecordExpectation.message("background job started").untraced
```

## Timestamps

Timestamp matching uses SDK-native `FiniteDuration` values:

```scala mdoc:silent
import scala.concurrent.duration._

LogRecordExpectation
  .message("request failed")
  .timestamp(1.second)
  .observedTimestamp(1500.millis)
```

You can also use predicates:

```scala mdoc:silent
LogRecordExpectation
  .message("request failed")
  .timestampWhere(_.exists(_ > 0.seconds))
  .observedTimestampWhere(_ >= 1.second)
```

## Attributes, scope, and resource

Log expectations reuse the shared attribute, scope, and resource expectation types:

```scala mdoc:silent
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.sdk.testkit.{InstrumentationScopeExpectation, TelemetryResourceExpectation}

LogRecordExpectation
  .message("request failed")
  .attributesSubset(Attribute("http.route", "/users"))
  .scope(
    InstrumentationScopeExpectation
      .name("service")
      .version("1.0.0")
  )
  .resource(
    TelemetryResourceExpectation.any
      .attributesSubset(Attribute("service.name", "auth-service"))
  )
```

## Distinct matching

Repeated expectations in `checkAll(...)` can match the same collected log record.
Use `checkAllDistinct(...)` when each expectation must match a different record.

```scala mdoc:silent
def checkAllDistinct(logs: List[LogRecordData]) =
  LogRecordExpectations.checkAllDistinct(
    logs,
    LogRecordExpectation.message("request failed"),
    LogRecordExpectation.message("request failed")
  )
```
