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

package org.typelevel.otel4s.sdk.testkit.metrics

import cats.data.NonEmptyList
import munit.FunSuite
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.Attributes
import org.typelevel.otel4s.sdk.context.TraceContext
import org.typelevel.otel4s.sdk.metrics.data.ExemplarData
import scodec.bits.ByteVector

import scala.concurrent.duration._

class ExemplarExpectationSuite extends FunSuite {

  test("numeric matches long exemplars with all configured expectations") {
    val expectation =
      ExemplarExpectation
        .numeric(42L)
        .filteredAttributesSubset(Attribute("region", "eu"))
        .timestamp(5.seconds)
        .traceContext(traceContext)
        .where("value must be even")(_.value % 2 == 0)
        .clue("counter exemplar")

    val exemplar = ExemplarData.long(
      filteredAttributes = Attributes(Attribute("region", "eu"), Attribute("host", "a")),
      timestamp = 5.seconds,
      traceContext = Some(traceContext),
      value = 42L
    )

    assertEquals(expectation.check(exemplar), Right(()))
    assert(expectation.matches(exemplar))
    assertEquals(expectation.clue, Some("counter exemplar"))
  }

  test("check reports value and filtered attributes mismatches together") {
    val expectation =
      ExemplarExpectation
        .numeric(42L)
        .filteredAttributesExact(Attribute("region", "eu"))

    val exemplar = ExemplarData.long(
      filteredAttributes = Attributes(Attribute("region", "us"), Attribute("host", "a")),
      timestamp = 1.second,
      traceContext = None,
      value = 41L
    )

    assertEquals(
      expectation.check(exemplar),
      Left(
        NonEmptyList.of(
          ExemplarExpectation.Mismatch.valueMismatch("42", "41"),
          ExemplarExpectation.Mismatch.filteredAttributesMismatch(
            NonEmptyList.of(
              org.typelevel.otel4s.sdk.testkit.AttributesExpectation.Mismatch.attributeValueMismatch(
                Attribute("region", "eu"),
                Attribute("region", "us")
              ),
              org.typelevel.otel4s.sdk.testkit.AttributesExpectation.Mismatch.unexpectedAttribute(
                Attribute("host", "a")
              )
            )
          )
        )
      )
    )
  }

  test("check reports type mismatches for the wrong exemplar numeric kind") {
    val expectation = ExemplarExpectation.numeric(42L)
    val exemplar = ExemplarData.double(Attributes.empty, 1.second, None, 42.0)

    assertEquals(
      expectation.check(exemplar),
      Left(NonEmptyList.one(ExemplarExpectation.Mismatch.typeMismatch("LongExemplar", "DoubleExemplar")))
    )
    assert(!expectation.matches(exemplar))
  }

  test("traceContextAbsent and timestamp report dedicated mismatches") {
    val expectation =
      ExemplarExpectation
        .numeric(42L)
        .timestamp(5.seconds)
        .traceContextAbsent

    val exemplar = ExemplarData.long(
      filteredAttributes = Attributes.empty,
      timestamp = 4.seconds,
      traceContext = Some(traceContext),
      value = 42L
    )

    assertEquals(
      expectation.check(exemplar),
      Left(
        NonEmptyList.of(
          ExemplarExpectation.Mismatch.timestampMismatch(5.seconds, 4.seconds),
          ExemplarExpectation.Mismatch.traceContextMismatch(None, Some(traceContext))
        )
      )
    )
  }

  test("filteredAttributesEmpty accepts only empty attributes") {
    val expectation = ExemplarExpectation.numeric(42L).filteredAttributesEmpty

    assertEquals(
      expectation.check(ExemplarData.long(Attributes.empty, Duration.Zero, None, 42L)),
      Right(())
    )
    assertEquals(
      expectation.check(
        ExemplarData.long(Attributes(Attribute("region", "eu")), Duration.Zero, None, 42L)
      ),
      Left(
        NonEmptyList.one(
          ExemplarExpectation.Mismatch.filteredAttributesMismatch(
            NonEmptyList.one(
              org.typelevel.otel4s.sdk.testkit.AttributesExpectation.Mismatch.unexpectedAttribute(
                Attribute("region", "eu")
              )
            )
          )
        )
      )
    )
  }

  test("where uses the default and explicit predicate clues") {
    val expectation =
      ExemplarExpectation
        .numeric(42L)
        .where(_.value < 0)
        .where("timestamp must be positive")(_.timestamp > Duration.Zero)

    val exemplar = ExemplarData.long(Attributes.empty, Duration.Zero, None, 42L)

    assertEquals(
      expectation.check(exemplar),
      Left(
        NonEmptyList.of(
          ExemplarExpectation.Mismatch.predicateMismatch("exemplar predicate returned false"),
          ExemplarExpectation.Mismatch.predicateMismatch("timestamp must be positive")
        )
      )
    )
  }

  private val traceContext =
    TraceContext(
      traceId = ByteVector.fromValidHex("0af7651916cd43dd8448eb211c80319c"),
      spanId = ByteVector.fromValidHex("b7ad6b7169203331"),
      sampled = true
    )
}
