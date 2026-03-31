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
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.Attributes
import org.typelevel.otel4s.sdk.data.LimitedData
import org.typelevel.otel4s.sdk.trace.data.LinkData
import org.typelevel.otel4s.trace.SpanContext
import org.typelevel.otel4s.trace.TraceFlags
import org.typelevel.otel4s.trace.TraceState
import scodec.bits.ByteVector

class LinkSetExpectationSuite extends FunSuite {

  test("exists succeeds when one link matches") {
    val links = List(
      link("0af7651916cd43dd8448eb211c80319c", "b7ad6b7169203331", Attribute("peer", "db")),
      link("0af7651916cd43dd8448eb211c80319c", "0000000000000001", Attribute("peer", "cache"))
    )

    assertEquals(
      LinkSetExpectation.exists(LinkExpectation.any.attributesSubset(Attribute("peer", "cache"))).check(links),
      Right(())
    )
  }

  test("exactly reports unexpected links") {
    val links = List(
      link("0af7651916cd43dd8448eb211c80319c", "b7ad6b7169203331"),
      link("0af7651916cd43dd8448eb211c80319c", "0000000000000001")
    )

    assertEquals(
      LinkSetExpectation
        .exactly(
          LinkExpectation.any.spanContext(SpanContextExpectation.any.spanIdHex("b7ad6b7169203331"))
        )
        .check(links),
      Left(NonEmptyList.one(LinkSetExpectation.Mismatch.UnexpectedLink(1)))
    )
  }

  test("contains reports the unmatched expectation in original order") {
    val links = List(
      link("0af7651916cd43dd8448eb211c80319c", "b7ad6b7169203331")
    )

    assertEquals(
      LinkSetExpectation
        .contains(
          LinkExpectation.any.spanContext(SpanContextExpectation.any.spanIdHex("b7ad6b7169203331")),
          LinkExpectation.any.spanContext(SpanContextExpectation.any.spanIdHex("0000000000000001"))
        )
        .check(links),
      Left(
        NonEmptyList.one(
          LinkSetExpectation.Mismatch.MissingExpectedLink(
            None,
            NonEmptyList.one(
              LinkExpectation.Mismatch.SpanContextMismatch(
                NonEmptyList.one(
                  SpanContextExpectation.Mismatch.SpanIdMismatch("0000000000000001", "b7ad6b7169203331")
                )
              )
            )
          )
        )
      )
    )
  }

  private def link(traceId: String, spanId: String, attributes: Attribute[_]*): LinkData =
    LinkData(
      SpanContext(
        traceId = ByteVector.fromValidHex(traceId),
        spanId = ByteVector.fromValidHex(spanId),
        traceFlags = TraceFlags.Default,
        traceState = TraceState.empty,
        remote = false
      ),
      LimitedData.attributes(8, 32).appendAll(Attributes(attributes: _*))
    )
}
