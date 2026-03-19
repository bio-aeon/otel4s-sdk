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

import cats.data.NonEmptyVector
import munit.FunSuite
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.Attributes
import org.typelevel.otel4s.metrics.BucketBoundaries
import org.typelevel.otel4s.sdk.TelemetryResource
import org.typelevel.otel4s.sdk.common.InstrumentationScope
import org.typelevel.otel4s.sdk.metrics.data.AggregationTemporality
import org.typelevel.otel4s.sdk.metrics.data.MetricData
import org.typelevel.otel4s.sdk.metrics.data.MetricPoints
import org.typelevel.otel4s.sdk.metrics.data.PointData
import org.typelevel.otel4s.sdk.metrics.data.TimeWindow
import org.typelevel.otel4s.sdk.testkit.InstrumentationScopeExpectation
import org.typelevel.otel4s.sdk.testkit.TelemetryResourceExpectation

import scala.concurrent.duration._

class MetricExpectationsSuite extends FunSuite {

  private val window = TimeWindow(0.seconds, 1.second)

  test("checkAllDistinct matches numeric metrics with scope and resource expectations") {
    val resource = TelemetryResource(
      Attributes(
        Attribute("service.name", "checkout")
      ),
      Some("https://example.com/resource")
    )

    val scope = InstrumentationScope
      .builder("service")
      .withVersion("1.0.0")
      .withSchemaUrl("https://example.com/scope")
      .withAttributes(Attributes(Attribute("scope.attr", "value")))
      .build

    val metrics = List(
      longSum(
        resource = resource,
        scope = scope,
        name = "service.counter",
        description = Some("Total requests"),
        unit = Some("1"),
        value = 1L,
        attributes = Attributes(Attribute("http.method", "GET"))
      ),
      longGauge(
        resource = resource,
        scope = scope,
        name = "service.gauge",
        description = Some("Queue depth"),
        unit = Some("items"),
        value = 42L,
        attributes = Attributes.empty
      )
    )

    val actual = MetricExpectations.checkAllDistinct(
      metrics,
      MetricExpectation
        .sum[Long]("service.counter")
        .description("Total requests")
        .unit("1")
        .monotonic(true)
        .temporality(AggregationTemporality.Cumulative)
        .scope(
          InstrumentationScopeExpectation
            .name("service")
            .version("1.0.0")
            .schemaUrl("https://example.com/scope")
            .attributesSubset(Attribute("scope.attr", "value"))
        )
        .resource(
          TelemetryResourceExpectation.any
            .schemaUrl("https://example.com/resource")
            .attributesSubset(Attribute("service.name", "checkout"))
        )
        .containsPoints(
          PointExpectation.numeric(1L).attributesSubset(Attribute("http.method", "GET"))
        ),
      MetricExpectation
        .gauge[Long]("service.gauge")
        .description("Queue depth")
        .unit("items")
        .value(42L)
    )

    assertEquals(actual, Right(()))
  }

  test("histogram expectations match count sum boundaries and counts") {
    val boundaries = BucketBoundaries(1.0, 2.0, 5.0)
    val metrics = List(
      histogram(
        name = "request.duration",
        boundaries = boundaries,
        counts = Vector(0L, 1L, 2L, 0L),
        sum = 5.0,
        count = 3L,
        min = Some(1.0),
        max = Some(3.0),
        attributes = Attributes(Attribute("route", "/checkout"))
      )
    )

    val actual = MetricExpectations.checkAll(
      metrics,
      MetricExpectation
        .histogram("request.duration")
        .temporality(AggregationTemporality.Cumulative)
        .pointCount(1)
        .containsPoints(
          PointExpectation
            .histogram(sum = 5.0, count = 3L, boundaries = boundaries, counts = List(0L, 1L, 2L, 0L))
            .min(1.0)
            .max(3.0)
            .attributesSubset(Attribute("route", "/checkout"))
        )
    )

    assertEquals(actual, Right(()))
  }

  test("checkAllDistinct reports when no distinct metric remains") {
    val metrics = List(
      longSum(TelemetryResource.empty, InstrumentationScope.empty, "requests", None, None, 1L, Attributes.empty)
    )

    val actual = MetricExpectations.checkAllDistinct(
      metrics,
      List(
        MetricExpectation.name("requests"),
        MetricExpectation.name("requests")
      )
    )

    assert(actual.isLeft)
    val failure = actual.swap.toOption.get.head
    assert(failure.isInstanceOf[MetricMismatch.DistinctMatchUnavailable])
    assert(failure.message.contains("no distinct metric remained"))
  }

  test("format renders closest mismatch details") {
    val metrics = List(
      longSum(TelemetryResource.empty, InstrumentationScope.empty, "requests", None, None, 2L, Attributes.empty)
    )

    val actual = MetricExpectations.checkAll(
      metrics,
      MetricExpectation.sum[Long]("requests").clue("requests count").value(1L)
    )

    assert(actual.isLeft)
    val rendered = MetricExpectations.format(actual.swap.toOption.get)
    assert(rendered.contains("[requests count]"))
    assert(rendered.contains("closest metric 'requests'"))
    assert(rendered.contains("value mismatch"))
  }

  private def longSum(
      resource: TelemetryResource,
      scope: InstrumentationScope,
      name: String,
      description: Option[String],
      unit: Option[String],
      value: Long,
      attributes: Attributes
  ): MetricData =
    MetricData(
      resource,
      scope,
      name,
      description,
      unit,
      MetricPoints.sum(
        NonEmptyVector.one(PointData.longNumber(window, attributes, Vector.empty, value)),
        monotonic = true,
        AggregationTemporality.Cumulative
      )
    )

  private def longGauge(
      resource: TelemetryResource,
      scope: InstrumentationScope,
      name: String,
      description: Option[String],
      unit: Option[String],
      value: Long,
      attributes: Attributes
  ): MetricData =
    MetricData(
      resource,
      scope,
      name,
      description,
      unit,
      MetricPoints.gauge(
        NonEmptyVector.one(PointData.longNumber(window, attributes, Vector.empty, value))
      )
    )

  private def histogram(
      name: String,
      boundaries: BucketBoundaries,
      counts: Vector[Long],
      sum: Double,
      count: Long,
      min: Option[Double],
      max: Option[Double],
      attributes: Attributes
  ): MetricData =
    MetricData(
      TelemetryResource.empty,
      InstrumentationScope.empty,
      name,
      None,
      None,
      MetricPoints.histogram(
        NonEmptyVector.one(
          PointData.histogram(
            window,
            attributes,
            Vector.empty,
            Some((min, max) match {
              case (Some(min), Some(max)) => PointData.Histogram.Stats(sum, min, max, count)
              case _                      => PointData.Histogram.Stats.withoutMinMax(sum, count)
            }),
            boundaries,
            counts
          )
        ),
        AggregationTemporality.Cumulative
      )
    )
}
