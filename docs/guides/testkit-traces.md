# Testkit | Traces

The traces testkit provides a structural expectation API for SDK `SpanData`.

This is useful in tests because span data contains more than just names and parent-child relationships:
timestamps, attributes, status, events, links, instrumentation scope, resource metadata, and more.

The expectation API lets you assert the trace tree directly, while still matching only the span fields that matter
for a test.

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
2. Collect finished spans as `SpanData`
3. Build `TraceExpectation` values
4. Check them with `TraceExpectations.check`

```scala mdoc:silent:reset
import cats.effect.IO
import org.typelevel.otel4s.sdk.testkit.OpenTelemetrySdkTestkit
import org.typelevel.otel4s.sdk.testkit.trace._
import org.typelevel.otel4s.sdk.trace.data.SpanData
import org.typelevel.otel4s.trace.TracerProvider
import scala.concurrent.duration._

def program(tracerProvider: TracerProvider[IO]): IO[Unit] =
  for {
    tracer <- tracerProvider.get("service")
    _ <- tracer.span("app.span").surround {
      tracer.span("app.nested.1").surround(IO.sleep(200.millis)) >>
      tracer.span("app.nested.2").surround(IO.sleep(300.millis))
    }
  } yield ()

def assertExpected(spans: List[SpanData], expected: TraceForestExpectation): Unit =
  TraceExpectations.check(spans, expected) match {
    case Right(_) =>
      ()
    case Left(mismatches) =>
      sys.error(TraceExpectations.format(mismatches))
  }

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
```

## Partial matching

`SpanExpectation` values are partial.

This means:

- unspecified span fields are ignored
- you can assert only the relevant properties for the current test
- you can still add more detail when you need it

For example:

```scala mdoc:silent
SpanExpectation.name("app.span")
```

matches any span named `app.span`, regardless of timing, attributes, events, links, scope, or resource.

```scala mdoc:silent
SpanExpectation
  .name("app.span")
  .startTimestamp(1.second)
  .endTimestamp(1500.millis)
```

adds exact timing checks on top of the name match.

The same partial-matching principle applies recursively:

- `TraceExpectation` only checks the subtree shape you describe
- `SpanExpectation` only checks the span fields you set
- `EventExpectation` and `LinkExpectation` only check the fields you set
- `EventSetExpectation` and `LinkSetExpectation` only check the collection properties you set

## Trees and forests

The trace expectation API has two layers:

- `TraceExpectation` describes one span subtree
- `TraceForestExpectation` describes the full exported forest

Use:

- `TraceExpectation.leaf(...)` for a span with no expected children
- `TraceExpectation.ordered(...)` for a subtree whose direct children must appear in order
- `TraceExpectation.unordered(...)` for a subtree whose direct children may appear in any order

At the forest level:

- `TraceForestExpectation.ordered(...)` requires roots in order
- `TraceForestExpectation.unordered(...)` ignores root order
- `TraceForestExpectation.empty` requires no finished root spans

```scala mdoc:silent
TraceExpectation.leaf(SpanExpectation.name("db.query"))

TraceExpectation.ordered(
  SpanExpectation.name("request").noParentSpanContext,
  TraceExpectation.leaf(SpanExpectation.name("decode")),
  TraceExpectation.leaf(SpanExpectation.name("db.query"))
)

TraceExpectation.unordered(
  SpanExpectation.name("request").noParentSpanContext,
  TraceExpectation.leaf(SpanExpectation.name("cache")),
  TraceExpectation.leaf(SpanExpectation.name("db.query"))
)
```

## Ordered vs unordered matching

Both ordered and unordered modes still require the exact number of direct children or roots.
What changes is whether relative order matters.

`ordered(...)` compares children positionally:

```scala mdoc:silent
TraceExpectation.ordered(
  SpanExpectation.name("request"),
  TraceExpectation.leaf(SpanExpectation.name("validate")),
  TraceExpectation.leaf(SpanExpectation.name("persist"))
)
```

`unordered(...)` still requires two matching children, but they can appear in either order:

```scala mdoc:silent
TraceExpectation.unordered(
  SpanExpectation.name("request"),
  TraceExpectation.leaf(SpanExpectation.name("cache")),
  TraceExpectation.leaf(SpanExpectation.name("db.query"))
)
```

This is especially useful when sibling spans can finish in a nondeterministic order.

## Span expectations

`SpanExpectation` is the main building block for each trace node.

Start with one of the entry points:

- `SpanExpectation.any`
- `SpanExpectation.name(...)`
- `SpanExpectation.internal(...)`
- `SpanExpectation.server(...)`
- `SpanExpectation.client(...)`
- `SpanExpectation.producer(...)`
- `SpanExpectation.consumer(...)`

```scala mdoc:silent
import org.typelevel.otel4s.trace.SpanKind

SpanExpectation.any

SpanExpectation.name("service.call")

SpanExpectation.internal("cache.lookup")

SpanExpectation.name("db.query").kind(SpanKind.Client)
```

### Timing and lifecycle

You can assert timing and end-state directly:

```scala mdoc:silent
SpanExpectation
  .name("request")
  .startTimestamp(1.second)
  .endTimestamp(1500.millis)
  .hasEnded

SpanExpectation
  .name("still-open")
  .endTimestamp(None)
  .hasNotEnded
```

### Attributes

Span attributes follow the same conventions as the metrics testkit:

- `attributesExact(...)`
- `attributesSubset(...)`
- `attributes(AttributesExpectation...)`
- `attributesEmpty`

```scala mdoc:silent
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.sdk.testkit.AttributesExpectation

SpanExpectation
  .name("request")
  .attributesExact(
    Attribute("http.method", "GET"),
    Attribute("http.route", "/users")
  )

SpanExpectation
  .name("request")
  .attributesSubset(Attribute("http.method", "GET"))

SpanExpectation
  .name("request")
  .attributes(
    AttributesExpectation.where("must contain trace attribute")(_.nonEmpty)
  )
```

### Status

Status matching is available through `StatusExpectation`:

```scala mdoc:silent
import org.typelevel.otel4s.trace.StatusCode

SpanExpectation
  .name("request")
  .status(StatusExpectation.ok)

SpanExpectation
  .name("request")
  .status(StatusExpectation.error.description("boom"))

SpanExpectation
  .name("request")
  .status(StatusExpectation.code(StatusCode.Error).description(None))
```

### Span context and parent context

You can match the span context itself, its parent, or just selected context fields.

```scala mdoc:silent
SpanExpectation
  .name("child")
  .parentSpanContext(
    SpanContextExpectation
      .any
      .traceIdHex("0af7651916cd43dd8448eb211c80319c")
      .sampled(true)
  )

SpanExpectation
  .name("root")
  .noParentSpanContext
```

If you already have a concrete `SpanContext`, use exact matching:

```scala mdoc:silent
import org.typelevel.otel4s.trace.{TraceFlags, TraceState}
import scodec.bits.ByteVector
import org.typelevel.otel4s.trace.SpanContext

val spanContext =
  SpanContext(
    traceId = ByteVector.fromValidHex("0af7651916cd43dd8448eb211c80319c"),
    spanId = ByteVector.fromValidHex("0102030405060708"),
    traceFlags = TraceFlags.Default,
    traceState = TraceState.empty,
    remote = false
  )

SpanExpectation.name("request").spanContextExact(spanContext)
```

### Scope and resource

Instrumentation scope and telemetry resource are matched the same way as in the metrics guide:

```scala mdoc:silent
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.sdk.testkit.{InstrumentationScopeExpectation, TelemetryResourceExpectation}

SpanExpectation
  .name("request")
  .scope(
    InstrumentationScopeExpectation
      .name("service")
      .version("1.0")
      .attributesEmpty
  )
  .resource(
    TelemetryResourceExpectation.any
      .attributesSubset(Attribute("service.name", "user-service"))
  )
```

### Custom predicates and clues

Every level of the trace API supports custom predicates and optional clues.

```scala mdoc:silent
SpanExpectation
  .name("request")
  .where("duration must be positive") { span =>
    span.endTimestamp.exists(_ >= span.startTimestamp)
  }
  .clue("request span")
```

## Matching more than names

Because each tree node is built from a `SpanExpectation`, you can match any supported span fields:

- timestamps
- parent span context
- attributes
- status
- events and links
- instrumentation scope
- telemetry resource
- custom predicates

```scala mdoc:silent
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.sdk.testkit.{InstrumentationScopeExpectation, TelemetryResourceExpectation}

TraceExpectation.leaf(
  SpanExpectation
    .name("http.request")
    .startTimestamp(1.second)
    .endTimestamp(1500.millis)
    .attributesSubset(
      Attribute("http.method", "GET"),
      Attribute("http.route", "/users")
    )
    .scope(
      InstrumentationScopeExpectation
        .name("service")
        .version("1.0")
        .attributesEmpty
    )
    .resource(
      TelemetryResourceExpectation.any
        .attributesSubset(Attribute("service.name", "user-service"))
    )
)
```

## Event expectations

`EventExpectation` is a partial matcher for one `EventData`.

Use:

- `EventExpectation.any`
- `EventExpectation.name(...)`
- `timestamp(...)`
- `attributesExact(...)`
- `attributesSubset(...)`
- `attributesEmpty`
- `where(...)`

```scala mdoc:silent
EventExpectation.name("started")

EventExpectation
  .name("exception")
  .timestamp(2.seconds)
  .attributesSubset(
    Attribute("exception.message", "boom")
  )

EventExpectation
  .any
  .where("must carry at least one attribute")(_.attributes.elements.nonEmpty)
```

## Event-set expectations

Event matching is collection-based through `EventSetExpectation`.

Supported shapes include:

- `any`
- `exists`
- `forall`
- `contains`
- `exactly`
- `count`
- `countAtLeast`
- `countAtMost`
- `countWhere`
- `none`
- `where`
- logical composition with `.and(...)` and `.or(...)`

```scala mdoc:silent
SpanExpectation
  .name("work")
  .events(
    EventSetExpectation
      .contains(
        EventExpectation.name("started"),
        EventExpectation.name("finished")
      )
      .and(EventSetExpectation.count(2))
  )

SpanExpectation
  .name("work")
  .events(
    EventSetExpectation.none(EventExpectation.name("exception"))
  )

SpanExpectation
  .name("work")
  .events(
    EventSetExpectation.countWhere(
      EventExpectation.name("retry"),
      expected = 2
    )
  )
```

The convenience span-level helpers are:

- `containsEvents(...)`
- `exactlyEvents(...)`
- `eventCount(...)`

```scala mdoc:silent
SpanExpectation
  .name("work")
  .containsEvents(
    EventExpectation.name("started"),
    EventExpectation.name("finished")
  )
  .eventCount(2)
```

## Events and links

`SpanExpectation` also supports collection-level expectations for events and links.

```scala mdoc:silent
TraceExpectation.leaf(
  SpanExpectation
    .name("work")
    .containsEvents(
      EventExpectation.name("started"),
      EventExpectation.name("finished")
    )
    .eventCount(2)
    .linkCount(0)
)
```

As with metric points, event and link matching is collection-based.
This lets you express presence, exact counts, exact collections, and collection-wide predicates.

## Link expectations

`LinkExpectation` is a partial matcher for one `LinkData`.

Use:

- `LinkExpectation.any`
- `spanContext(...)`
- `spanContextExact(...)`
- `attributesExact(...)`
- `attributesSubset(...)`
- `attributesEmpty`
- `where(...)`

```scala mdoc:silent
LinkExpectation.any

LinkExpectation
  .any
  .spanContext(
    SpanContextExpectation
      .any
      .sampled(true)
      .remote(false)
  )
  .attributesSubset(Attribute("link.kind", "parent"))
```

## Link-set expectations

Link collections are matched with `LinkSetExpectation`, which mirrors the event-set API:

- `any`
- `exists`
- `forall`
- `contains`
- `exactly`
- `count`
- `countAtLeast`
- `countAtMost`
- `countWhere`
- `none`
- `where`
- `.and(...)`
- `.or(...)`

```scala mdoc:silent
SpanExpectation
  .name("work")
  .links(
    LinkSetExpectation
      .exists(
        LinkExpectation.any.attributesSubset(Attribute("link.kind", "parent"))
      )
      .and(LinkSetExpectation.countAtLeast(1))
  )

SpanExpectation
  .name("work")
  .links(
    LinkSetExpectation.none(
      LinkExpectation.any.attributesSubset(Attribute("unexpected", true))
    )
  )
```

The span-level convenience helpers are:

- `containsLinks(...)`
- `exactlyLinks(...)`
- `linkCount(...)`

```scala mdoc:silent
SpanExpectation
  .name("work")
  .exactlyLinks(
    LinkExpectation.any.attributesSubset(Attribute("link.kind", "parent"))
  )
  .linkCount(1)
```

## Checking patterns

`TraceExpectations.check(...)` validates the full exported forest.

Typical assertion wrappers look like this:

```scala mdoc:silent
def assertTrace(spans: List[SpanData], expected: TraceForestExpectation): Unit =
  TraceExpectations.check(spans, expected) match {
    case Right(_) =>
      ()
    case Left(mismatches) =>
      sys.error(TraceExpectations.format(mismatches))
  }
```

For reusable helpers inside a suite, it is often convenient to define local constructors:

```scala mdoc:silent
def leaf(name: String): TraceExpectation =
  TraceExpectation.leaf(SpanExpectation.name(name))

def root(name: String, children: TraceExpectation*): TraceExpectation =
  TraceExpectation.ordered(
    SpanExpectation.name(name).noParentSpanContext,
    children*
  )
```

That keeps tests compact while still using the real expectation API.

## Failure reporting

The API is framework-agnostic, so it does not provide assertions directly.
Instead, it returns structured mismatches and a formatter.

```scala mdoc:silent
def assertExpectedFormatted(spans: List[SpanData], expected: TraceForestExpectation): Unit =
  TraceExpectations.check(spans, expected) match {
    case Right(_) =>
      ()
    case Left(mismatches) =>
      sys.error(TraceExpectations.format(mismatches))
  }
```

At the forest level, the most common failure cases are:

- `RootCountMismatch`: the number of collected root spans does not match the expectation
- `MissingRoot`: no collected root looked like a candidate for one expected root
- `RootMismatch`: a likely root was found, but its subtree or span fields did not match
- `DistinctRootMatchUnavailable`: the expectation matched collected roots, but none remained available as a distinct
  match in unordered matching

Inside a subtree, the most common failure cases are:

- `SpanMismatch`: the current span fields did not match
- `ChildCountMismatch`: the number of direct children did not match
- `MissingChild`: no collected child looked like a candidate
- `ChildMismatch`: a likely child subtree was found, but it still differed from the expectation
- `DistinctChildMatchUnavailable`: the expectation matched collected children, but none remained available as a
  distinct match in unordered matching

This is especially helpful when the general trace shape is correct, but one timestamp, one attribute, one event, or
one link differs from the expected value.

## Clues

Both `TraceExpectation` and `TraceForestExpectation` support `clue(...)`.

Clues are included in rendered mismatch messages, which is useful when a test checks multiple similar trees.

```scala mdoc:silent
val expectedWithClue =
  TraceForestExpectation
    .unordered(
      TraceExpectation
        .unordered(
          SpanExpectation
            .name("outer")
            .noParentSpanContext
            .clue("outer span"),
          TraceExpectation.leaf(
            SpanExpectation
              .name("body")
              .containsEvents(
                EventExpectation
                  .name("started")
                  .clue("body start event")
              )
              .clue("body span")
          )
        )
        .clue("outer request")
    )
    .clue("full export")
```

Clues are available at multiple levels:

- `TraceForestExpectation`
- `TraceExpectation`
- `SpanExpectation`
- `EventExpectation`
- `EventSetExpectation`
- `LinkExpectation`
- `LinkSetExpectation`
- `SpanContextExpectation`

When a match fails, render the mismatches with:

```scala mdoc:silent
def renderFailure(spans: List[SpanData], expected: TraceForestExpectation): String =
  TraceExpectations.check(spans, expected) match {
    case Right(_) =>
      "ok"
    case Left(mismatches) =>
      TraceExpectations.format(mismatches)
  }
```

In practice, clues are most useful when:

- several expected roots have similar shapes
- several sibling spans differ only in one timestamp or attribute
- a collection expectation such as `containsEvents(...)` or `exists(...)` would otherwise be hard to identify
- a test covers several phases of one workflow and you want the failure output to name the phase directly

## Suggested patterns

In practice:

- use `unordered(...)` for spans that can finish in nondeterministic order
- use `ordered(...)` when order is part of the behavior you want to preserve
- use `leaf(...)` for terminal spans
- keep `SpanExpectation` partial unless the full shape is important
- use `StatusExpectation` and `SpanContextExpectation` when IDs, sampling, or status are part of the contract
- use `EventSetExpectation` and `LinkSetExpectation` for collection-level assertions instead of manual list traversal
- add `clue(...)` for complex trees so failure messages stay readable

```scala mdoc:invisible
import cats.effect.unsafe.implicits.global
test.unsafeRunSync()
```
