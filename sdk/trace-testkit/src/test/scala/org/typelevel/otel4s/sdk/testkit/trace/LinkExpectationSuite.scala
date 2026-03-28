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
import scodec.bits.ByteVector

class LinkExpectationSuite extends FunSuite {

  test("matches nested span context and attributes") {
    val ctx = spanContext("0af7651916cd43dd8448eb211c80319c", "b7ad6b7169203331")
    val link = LinkData(ctx, LimitedData.attributes(8, 32).appendAll(Attributes(Attribute("peer", "db"))))

    assertEquals(
      LinkExpectation.any
        .spanContext(SpanContextExpectation.any.traceIdHex(ctx.traceIdHex))
        .attributesSubset(Attribute("peer", "db"))
        .check(link),
      Right(())
    )
  }

  test("reports nested span context mismatch") {
    val ctx = spanContext("0af7651916cd43dd8448eb211c80319c", "b7ad6b7169203331")
    val link = LinkData(ctx, LimitedData.attributes(8, 32))

    assertEquals(
      LinkExpectation.any.spanContext(SpanContextExpectation.any.spanIdHex("0000000000000001")).check(link),
      Left(
        NonEmptyList.one(
          LinkExpectation.Mismatch.SpanContextMismatch(
            NonEmptyList.one(SpanContextExpectation.Mismatch.SpanIdMismatch("0000000000000001", ctx.spanIdHex))
          )
        )
      )
    )
  }

  private def spanContext(traceId: String, spanId: String): SpanContext =
    SpanContext(
      traceId = ByteVector.fromValidHex(traceId),
      spanId = ByteVector.fromValidHex(spanId),
      traceFlags = org.typelevel.otel4s.trace.TraceFlags.Default,
      traceState = org.typelevel.otel4s.trace.TraceState.empty,
      remote = false
    )
}
