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
import org.typelevel.otel4s.trace.TraceFlags
import org.typelevel.otel4s.trace.TraceState
import scodec.bits.ByteVector

import scala.concurrent.duration._

class SpanExpectationsSuite extends FunSuite {

  test("checkAllDistinct requires distinct matches") {
    val spans = List(
      span("db.query", SpanKind.Client),
      span("render", SpanKind.Internal)
    )

    assertEquals(
      SpanExpectations.checkAllDistinct(
        spans,
        SpanExpectation.client("db.query"),
        SpanExpectation.internal("render")
      ),
      Right(())
    )
  }

  test("checkAllDistinct reports unavailable distinct match") {
    val spans = List(span("db.query", SpanKind.Client))

    val result = SpanExpectations.checkAllDistinct(
      spans,
      List(SpanExpectation.client("db.query"), SpanExpectation.client("db.query"))
    )

    assertEquals(
      result,
      Left(
        NonEmptyList.one(
          SpanMismatch.distinctMatchUnavailable(SpanExpectation.client("db.query"), List("db.query"))
        )
      )
    )
  }

  test("checkAllDistinct reports the unmatched expectation in original order") {
    val spans = List(span("db.query", SpanKind.Client))
    val unmatched =
      SpanExpectation.client("db.query").where("extra attribute")(_.attributes.elements.nonEmpty)

    val result = SpanExpectations.checkAllDistinct(
      spans,
      List(
        SpanExpectation.client("db.query"),
        unmatched
      )
    )

    result match {
      case Left(NonEmptyList(mismatch: SpanMismatch.ClosestMismatch, Nil)) =>
        assertEquals(mismatch.expectation, unmatched)
        assertEquals(mismatch.span.name, "db.query")
        assertEquals(
          mismatch.mismatches,
          NonEmptyList.one(SpanExpectation.Mismatch.PredicateMismatch(Some("extra attribute")))
        )
      case other =>
        fail(s"unexpected result: $other")
    }
  }

  test("format renders failures") {
    val mismatches = NonEmptyList.one(
      SpanMismatch.notFound(SpanExpectation.name("missing").clue("span"), List("present"))
    )

    val rendered = SpanExpectations.format(mismatches)

    assert(rendered.contains("Span expectations failed:"))
    assert(rendered.contains("[span] no span matched the expectation"))
  }

  private def span(name: String, kind: SpanKind): SpanData =
    SpanData(
      name = name,
      spanContext = SpanContext(
        traceId = ByteVector.fromValidHex("0af7651916cd43dd8448eb211c80319c"),
        spanId = ByteVector.fromValidHex("0102030405060708"),
        traceFlags = TraceFlags.Default,
        traceState = TraceState.empty,
        remote = false
      ),
      parentSpanContext = None,
      kind = kind,
      startTimestamp = 1.second,
      endTimestamp = Some(2.seconds),
      status = StatusData.Unset,
      attributes = LimitedData.attributes(8, 32),
      events = LimitedData.vector(8),
      links = LimitedData.vector(8),
      instrumentationScope = InstrumentationScope("scope", None, None, Attributes.empty),
      resource = TelemetryResource.empty
    )
}
