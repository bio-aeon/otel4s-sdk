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

## Testing spans

The testkit also provides a dedicated structural expectation API for traces.
It can match the full exported forest, nested subtrees, ordered or unordered children,
timestamps, attributes, events, links, scope, resource, and more.

For the traces expectation API, see the dedicated [Traces testkit guide](testkit-traces.md).
