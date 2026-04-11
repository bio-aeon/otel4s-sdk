/*
 * Copyright 2024 Typelevel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.typelevel.otel4s.sdk.testkit.trace

import cats.data.NonEmptyList
import munit.FunSuite
import org.typelevel.otel4s.Attributes
import org.typelevel.otel4s.sdk.TelemetryResource
import org.typelevel.otel4s.sdk.common.InstrumentationScope
import org.typelevel.otel4s.sdk.data.LimitedData
import org.typelevel.otel4s.sdk.trace.data.SpanData
import org.typelevel.otel4s.sdk.trace.data.StatusData
import org.typelevel.otel4s.trace.SpanContext
import org.typelevel.otel4s.trace.SpanKind
import scodec.bits.ByteVector

import scala.concurrent.duration._

class TraceExpectationsSuite extends FunSuite {

  test("check matches an unordered forest") {
    val rootContext = spanContext("0102030405060708")

    val spans = List(
      span("body-2", spanContext("0102030405060711"), Some(rootContext), 1250.millis, 1300.millis),
      span("outer", rootContext, None, 1.second, 1300.millis),
      span("body-1", spanContext("0102030405060710"), Some(rootContext), 1025.millis, 1125.millis)
    )

    val expectation = TraceForestExpectation.unordered(
      TraceExpectation.unordered(
        SpanExpectation.name("outer").startTimestamp(1.second).endTimestamp(1300.millis).noParentSpanContext,
        TraceExpectation.leaf(
          SpanExpectation.name("body-1").startTimestamp(1025.millis).endTimestamp(1125.millis)
        ),
        TraceExpectation.leaf(
          SpanExpectation.name("body-2").startTimestamp(1250.millis).endTimestamp(1300.millis)
        )
      )
    )

    assertEquals(TraceExpectations.check(spans, expectation), Right(()))
  }

  test("check fails when a child is attached to the wrong parent") {
    val rootContext = spanContext("0102030405060708")
    val wrongParent = spanContext("0102030405060709")

    val spans = List(
      span("outer", rootContext, None, 1.second, 2.seconds),
      span("other-root", wrongParent, None, 1.second, 2.seconds),
      span("body", spanContext("0102030405060710"), Some(wrongParent), 1.second, 2.seconds)
    )

    val expectation = TraceForestExpectation.unordered(
      TraceExpectation.unordered(
        SpanExpectation.name("outer").noParentSpanContext,
        TraceExpectation.leaf(SpanExpectation.name("body"))
      ),
      TraceExpectation.leaf(SpanExpectation.name("other-root").noParentSpanContext)
    )

    val result = TraceExpectations.check(spans, expectation)

    assert(result.isLeft)
    assert(TraceExpectations.format(result.swap.toOption.get).contains("trace mismatch"))
  }

  test("check reports root count mismatches") {
    val spans = List(
      span("one", spanContext("0102030405060708"), None, 1.second, 2.seconds),
      span("two", spanContext("0102030405060709"), None, 1.second, 2.seconds)
    )

    val result = TraceExpectations.check(
      spans,
      TraceForestExpectation.unordered(
        TraceExpectation.leaf(SpanExpectation.name("one").noParentSpanContext)
      )
    )

    assertEquals(
      result,
      Left(
        NonEmptyList.one(
          TraceForestExpectation.Mismatch.RootCountMismatch(1, 2, List("one", "two"))
        )
      )
    )
  }

  test("check matches an empty forest") {
    assertEquals(
      TraceExpectations.check(Nil, TraceForestExpectation.empty),
      Right(())
    )
  }

  test("check reports root count mismatch for non-empty forest against empty expectation") {
    val spans = List(
      span("one", spanContext("0102030405060708"), None, 1.second, 2.seconds)
    )

    assertEquals(
      TraceExpectations.check(spans, TraceForestExpectation.empty),
      Left(
        NonEmptyList.one(
          TraceForestExpectation.Mismatch.RootCountMismatch(0, 1, List("one"))
        )
      )
    )
  }

  test("check ordered children require matching order") {
    val rootContext = spanContext("0102030405060708")

    val spans = List(
      span("second", spanContext("0102030405060711"), Some(rootContext), 1250.millis, 1300.millis),
      span("root", rootContext, None, 1.second, 1300.millis),
      span("first", spanContext("0102030405060710"), Some(rootContext), 1025.millis, 1125.millis)
    )

    val result = TraceExpectations.check(
      spans,
      TraceForestExpectation.unordered(
        TraceExpectation.ordered(
          SpanExpectation.name("root").noParentSpanContext,
          TraceExpectation.leaf(SpanExpectation.name("first")),
          TraceExpectation.leaf(SpanExpectation.name("second"))
        )
      )
    )

    assert(result.isLeft)
    assert(TraceExpectations.format(result.swap.toOption.get).contains("trace subtree mismatch"))
  }

  test("check ordered roots require matching order") {
    val spans = List(
      span("one", spanContext("0102030405060708"), None, 1.second, 2.seconds),
      span("two", spanContext("0102030405060709"), None, 1.second, 2.seconds)
    )

    val result = TraceExpectations.check(
      spans,
      TraceForestExpectation.ordered(
        TraceExpectation.leaf(SpanExpectation.name("two").noParentSpanContext),
        TraceExpectation.leaf(SpanExpectation.name("one").noParentSpanContext)
      )
    )

    assert(result.isLeft)
    assert(TraceExpectations.format(result.swap.toOption.get).contains("trace mismatch for root 'one'"))
  }

  test("check includes trace expectation clue in subtree failures") {
    val rootContext = spanContext("0102030405060708")

    val spans = List(
      span("outer", rootContext, None, 1.second, 2.seconds)
    )

    val result = TraceExpectations.check(
      spans,
      TraceForestExpectation.unordered(
        TraceExpectation
          .unordered(
            SpanExpectation.name("outer").noParentSpanContext,
            TraceExpectation.leaf(SpanExpectation.name("body"))
          )
          .clue("outer subtree")
      )
    )

    assert(result.isLeft)
    assert(TraceExpectations.format(result.swap.toOption.get).contains("trace expectation mismatch [outer subtree]"))
  }

  test("check includes traces expectation clue in root failures") {
    val spans = List(
      span("one", spanContext("0102030405060708"), None, 1.second, 2.seconds)
    )

    val result = TraceExpectations.check(
      spans,
      TraceForestExpectation
        .unordered(
          TraceExpectation.leaf(SpanExpectation.name("missing").noParentSpanContext)
        )
        .clue("entire export")
    )

    assert(result.isLeft)
    assert(TraceExpectations.format(result.swap.toOption.get).contains("trace expectations mismatch [entire export]"))
  }

  private def span(
      name: String,
      spanContext: SpanContext,
      parentSpanContext: Option[SpanContext],
      start: FiniteDuration,
      end: FiniteDuration
  ): SpanData =
    SpanData(
      name = name,
      spanContext = spanContext,
      parentSpanContext = parentSpanContext,
      kind = SpanKind.Internal,
      startTimestamp = start,
      endTimestamp = Some(end),
      status = StatusData.Unset,
      attributes = LimitedData.attributes(8, 32),
      events = LimitedData.vector(8),
      links = LimitedData.vector(8),
      instrumentationScope = InstrumentationScope("scope", None, None, Attributes.empty),
      resource = TelemetryResource.empty
    )

  private def spanContext(spanIdHex: String): SpanContext =
    SpanContext(
      traceId = ByteVector.fromValidHex("0af7651916cd43dd8448eb211c80319c"),
      spanId = ByteVector.fromValidHex(spanIdHex),
      traceFlags = org.typelevel.otel4s.trace.TraceFlags.Default,
      traceState = org.typelevel.otel4s.trace.TraceState.empty,
      remote = false
    )
}
