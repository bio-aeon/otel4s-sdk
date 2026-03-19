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
import org.typelevel.otel4s.metrics.BucketBoundaries
import org.typelevel.otel4s.sdk.context.TraceContext
import org.typelevel.otel4s.sdk.metrics.data.ExemplarData
import org.typelevel.otel4s.sdk.metrics.data.PointData
import org.typelevel.otel4s.sdk.metrics.data.TimeWindow
import scodec.bits.ByteVector

import scala.concurrent.duration._
import scala.concurrent.duration.Duration

class PointExpectationSuite extends FunSuite {

  test("numeric expectation matches value attributes and predicates") {
    val point = longPoint(42L, Attributes(Attribute("region", "eu"), Attribute("host", "a")))

    val expectation =
      PointExpectation
        .numeric(42L)
        .attributesSubset(Attribute("region", "eu"))
        .where("value must be even")(_.value % 2 == 0)
        .clue("counter point")

    assertEquals(expectation.check(point), Right(()))
    assert(expectation.matches(point))
    assertEquals(expectation.clue, Some("counter point"))
  }

  test("numeric expectation reports value and attributes mismatches together") {
    val point = longPoint(41L, Attributes(Attribute("region", "us"), Attribute("host", "a")))

    val expectation =
      PointExpectation
        .numeric(42L)
        .attributesExact(Attribute("region", "eu"))

    assertEquals(
      expectation.check(point),
      Left(
        NonEmptyList.of(
          PointExpectation.Mismatch.valueMismatch("42", "41"),
          PointExpectation.Mismatch.attributesMismatch(
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

  test("numeric expectation reports type mismatch for wrong point kind") {
    val point = doublePoint(42.0)

    assertEquals(
      PointExpectation.numeric(42L).check(point),
      Left(NonEmptyList.one(PointExpectation.Mismatch.typeMismatch("LongNumber", "DoubleNumber")))
    )
  }

  test("histogram stats expectation matches exact stats") {
    val stats = PointData.Histogram.Stats(sum = 6.0, min = 1.0, max = 3.0, count = 3L)
    val point = histogram(stats = Some(stats))

    assert(PointExpectation.histogram.stats(stats).matches(point))
  }

  test("histogram stats expectation mismatches different stats") {
    val expected = PointData.Histogram.Stats(sum = 6.0, min = 1.0, max = 3.0, count = 3L)
    val actual = PointData.Histogram.Stats(sum = 7.0, min = 1.0, max = 3.0, count = 3L)
    val point = histogram(stats = Some(actual))

    val result = PointExpectation.histogram.stats(expected).check(point)

    assert(result.isLeft)
    assertEquals(
      result.left.toOption.map(_.head.message),
      Some(s"stats mismatch: expected ${expected.toString}, got ${actual.toString}")
    )
  }

  test("histogram withoutStats expectation matches missing stats") {
    val point = histogram(stats = None)

    assert(PointExpectation.histogram.withoutStats.matches(point))
  }

  test("histogram expectation matches sum count min max boundaries counts and attributes") {
    val stats = PointData.Histogram.Stats(sum = 6.0, min = 1.0, max = 3.0, count = 3L)
    val boundaries = BucketBoundaries(1.0, 2.0, 3.0)
    val point = histogram(
      stats = Some(stats),
      attributes = Attributes(Attribute("region", "eu")),
      boundaries = boundaries,
      counts = Vector(1L, 1L, 1L, 0L)
    )

    val expectation =
      PointExpectation.histogram
        .sum(6.0)
        .count(3L)
        .min(1.0)
        .max(3.0)
        .boundaries(boundaries)
        .counts(1L, 1L, 1L, 0L)
        .attributesExact(Attribute("region", "eu"))
        .where("must expose stats")(_.stats.nonEmpty)

    assertEquals(expectation.check(point), Right(()))
  }

  test("histogram expectation reports field mismatches together") {
    val point = histogram(
      stats = Some(PointData.Histogram.Stats(sum = 5.0, min = 2.0, max = 4.0, count = 2L)),
      boundaries = BucketBoundaries(2.0, 4.0),
      counts = Vector(2L, 0L, 0L)
    )

    val expectation =
      PointExpectation.histogram
        .sum(6.0)
        .count(3L)
        .min(1.0)
        .max(3.0)
        .boundaries(BucketBoundaries(1.0, 2.0, 3.0))
        .counts(1L, 1L, 1L, 0L)

    assertEquals(
      expectation.check(point),
      Left(
        NonEmptyList.of(
          PointExpectation.Mismatch.sumMismatch(6.0, 5.0),
          PointExpectation.Mismatch.countMismatch(3L, 2L),
          PointExpectation.Mismatch.minMismatch(Some(1.0), Some(2.0)),
          PointExpectation.Mismatch.maxMismatch(Some(3.0), Some(4.0)),
          PointExpectation.Mismatch.boundariesMismatch(BucketBoundaries(1.0, 2.0, 3.0), BucketBoundaries(2.0, 4.0)),
          PointExpectation.Mismatch.countsMismatch(List(1L, 1L, 1L, 0L), List(2L, 0L, 0L))
        )
      )
    )
  }

  test("point expectations check exemplar count and exemplar contents") {
    val exemplar = ExemplarData.long(
      filteredAttributes = Attributes(Attribute("region", "eu")),
      timestamp = 5.seconds,
      traceContext = Some(traceContext),
      value = 42L
    )

    val point =
      PointData.longNumber(
        timeWindow = TimeWindow(Duration.Zero, Duration.Zero),
        attributes = Attributes.empty,
        exemplars = Vector(exemplar),
        value = 42L
      )

    val expectation =
      PointExpectation
        .numeric(42L)
        .exemplarCount(1)
        .containsExemplars(
          ExemplarExpectation
            .numeric(42L)
            .filteredAttributesSubset(Attribute("region", "eu"))
            .timestamp(5.seconds)
            .traceContext(traceContext)
        )

    assertEquals(expectation.check(point), Right(()))
  }

  private def longPoint(value: Long, attributes: Attributes): PointData.LongNumber =
    PointData.longNumber(
      timeWindow = TimeWindow(Duration.Zero, Duration.Zero),
      attributes = attributes,
      exemplars = Vector.empty,
      value = value
    )

  private def doublePoint(value: Double): PointData.DoubleNumber =
    PointData.doubleNumber(
      timeWindow = TimeWindow(Duration.Zero, Duration.Zero),
      attributes = Attributes.empty,
      exemplars = Vector.empty,
      value = value
    )

  private def histogram(
      stats: Option[PointData.Histogram.Stats],
      attributes: Attributes = Attributes.empty,
      boundaries: BucketBoundaries = BucketBoundaries(1.0, 2.0, 3.0),
      counts: Vector[Long] = Vector(1L, 1L, 1L, 0L)
  ): PointData.Histogram =
    PointData.histogram(
      timeWindow = TimeWindow(Duration.Zero, Duration.Zero),
      attributes = attributes,
      exemplars = Vector.empty,
      stats = stats,
      boundaries = boundaries,
      counts = counts
    )

  private val traceContext =
    TraceContext(
      traceId = ByteVector.fromValidHex("0af7651916cd43dd8448eb211c80319c"),
      spanId = ByteVector.fromValidHex("b7ad6b7169203331"),
      sampled = true
    )
}
