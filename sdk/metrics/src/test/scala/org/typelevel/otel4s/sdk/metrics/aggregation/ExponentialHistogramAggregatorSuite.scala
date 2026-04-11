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

package org.typelevel.otel4s.sdk.metrics.aggregation

import cats.effect.IO
import cats.effect.SyncIO
import cats.effect.std.Random
import cats.effect.testkit.TestControl
import cats.syntax.foldable._
import munit.CatsEffectSuite
import munit.ScalaCheckEffectSuite
import org.scalacheck.Gen
import org.scalacheck.Test
import org.scalacheck.effect.PropF
import org.typelevel.otel4s.Attributes
import org.typelevel.otel4s.sdk.context.Context
import org.typelevel.otel4s.sdk.context.TraceContext
import org.typelevel.otel4s.sdk.metrics.data.MetricData
import org.typelevel.otel4s.sdk.metrics.data.MetricPoints
import org.typelevel.otel4s.sdk.metrics.data.PointData
import org.typelevel.otel4s.sdk.metrics.data.TimeWindow
import org.typelevel.otel4s.sdk.metrics.exemplar.Reservoirs
import org.typelevel.otel4s.sdk.metrics.internal.MetricDescriptor
import org.typelevel.otel4s.sdk.metrics.scalacheck.Gens

import scala.concurrent.duration._

class ExponentialHistogramAggregatorSuite extends CatsEffectSuite with ScalaCheckEffectSuite {

  private val traceContextKey = Context.Key
    .unique[SyncIO, TraceContext]("trace-context")
    .unsafeRunSync()

  private def reservoirs(implicit R: Random[IO]): Reservoirs[IO] =
    Reservoirs.alwaysOn[IO](_.get(traceContextKey))

  private val testMaxBuckets = 160
  private val testMaxScale = 0

  test("aggregate with reset - return a snapshot and reset the state") {
    PropF.forAllF(
      Gen.listOf(Gen.choose(1.0, 100.0)),
      Gens.attributes,
      Gens.attributes,
      Gens.traceContext
    ) { (values, exemplarAttributes, attributes, traceContext) =>
      Random.scalaUtilRandom[IO].flatMap { implicit R: Random[IO] =>
        val ctx = Context.root.updated(traceContextKey, traceContext)

        val aggregator =
          ExponentialHistogramAggregator[IO, Double](reservoirs, testMaxBuckets, testMaxScale, recordMinMax = true)

        val timeWindow =
          TimeWindow(100.millis, 200.millis)

        TestControl.executeEmbed {
          for {
            accumulator <- aggregator.createAccumulator
            _ <- values.traverse_ { value =>
              accumulator.record(value, exemplarAttributes, ctx)
            }
            r1 <- accumulator.aggregate(timeWindow, attributes, reset = true)
            r2 <- accumulator.aggregate(timeWindow, attributes, reset = true)
          } yield {
            r1 match {
              case Some(point: PointData.ExponentialHistogram) =>
                assertEquals(point.stats.map(_.count), Some(values.size.toLong))
                assertEquals(point.stats.map(_.sum), Some(values.sum))
                if (values.nonEmpty) {
                  assertEquals(point.stats.flatMap(_.min), Some(values.min))
                  assertEquals(point.stats.flatMap(_.max), Some(values.max))
                }
                assertEquals(point.zeroCount, 0L)
                assertEquals(
                  point.positiveBuckets.totalCount + point.negativeBuckets.totalCount + point.zeroCount,
                  values.size.toLong
                )

              case Some(_) =>
                fail("expected ExponentialHistogram point data")

              case None =>
                assertEquals(values, Nil)
            }

            assertEquals(r2, None)
          }
        }
      }
    }
  }

  test("aggregate without reset - return a cumulative snapshot") {
    PropF.forAllF(
      Gen.listOf(Gen.choose(1.0, 100.0)),
      Gens.attributes,
      Gens.attributes,
      Gens.traceContext
    ) { (values, exemplarAttributes, attributes, traceContext) =>
      Random.scalaUtilRandom[IO].flatMap { implicit R: Random[IO] =>
        val ctx = Context.root.updated(traceContextKey, traceContext)

        val aggregator =
          ExponentialHistogramAggregator[IO, Double](reservoirs, testMaxBuckets, testMaxScale, recordMinMax = true)

        val timeWindow =
          TimeWindow(100.millis, 200.millis)

        TestControl.executeEmbed {
          for {
            accumulator <- aggregator.createAccumulator
            _ <- values.traverse_ { value =>
              accumulator.record(value, exemplarAttributes, ctx)
            }
            r1 <- accumulator.aggregate(timeWindow, attributes, reset = false)
            _ <- values.traverse_ { value =>
              accumulator.record(value, exemplarAttributes, ctx)
            }
            r2 <- accumulator.aggregate(timeWindow, attributes, reset = false)
          } yield {
            r1.foreach { p =>
              val point = p.asInstanceOf[PointData.ExponentialHistogram]
              assertEquals(point.stats.map(_.count), Some(values.size.toLong))
            }
            r2.foreach { p =>
              val point = p.asInstanceOf[PointData.ExponentialHistogram]
              assertEquals(point.stats.map(_.count), Some(values.size.toLong * 2))
              assertEquals(point.stats.map(_.sum), Some((values ++ values).sum))
            }
          }
        }
      }
    }
  }

  test("record zero values - increment zeroCount") {
    Random.scalaUtilRandom[IO].flatMap { implicit R: Random[IO] =>
      val aggregator =
        ExponentialHistogramAggregator[IO, Double](reservoirs, testMaxBuckets, testMaxScale, recordMinMax = true)
      val timeWindow = TimeWindow(100.millis, 200.millis)

      TestControl.executeEmbed {
        for {
          accumulator <- aggregator.createAccumulator
          _ <- accumulator.record(0.0, Attributes.empty, Context.root)
          _ <- accumulator.record(0.0, Attributes.empty, Context.root)
          _ <- accumulator.record(0.0, Attributes.empty, Context.root)
          point <- accumulator.aggregate(timeWindow, Attributes.empty, reset = true)
        } yield {
          assert(point.isDefined)
          val histogram =
            point.get.asInstanceOf[PointData.ExponentialHistogram]
          assertEquals(histogram.zeroCount, 3L)
          assertEquals(histogram.stats.map(_.count), Some(3L))
          assertEquals(histogram.stats.map(_.sum), Some(0.0))
          assertEquals(histogram.positiveBuckets.totalCount, 0L)
          assertEquals(histogram.negativeBuckets.totalCount, 0L)
        }
      }
    }
  }

  test("record positive and negative values - populate both bucket sides") {
    Random.scalaUtilRandom[IO].flatMap { implicit R: Random[IO] =>
      val aggregator =
        ExponentialHistogramAggregator[IO, Double](reservoirs, testMaxBuckets, testMaxScale, recordMinMax = true)
      val timeWindow = TimeWindow(100.millis, 200.millis)

      TestControl.executeEmbed {
        for {
          accumulator <- aggregator.createAccumulator
          _ <- accumulator.record(1.5, Attributes.empty, Context.root)
          _ <- accumulator.record(3.0, Attributes.empty, Context.root)
          _ <- accumulator.record(-2.5, Attributes.empty, Context.root)
          _ <- accumulator.record(0.0, Attributes.empty, Context.root)
          point <- accumulator.aggregate(timeWindow, Attributes.empty, reset = true)
        } yield {
          assert(point.isDefined)
          val histogram = point.get.asInstanceOf[PointData.ExponentialHistogram]
          assertEquals(histogram.stats.map(_.count), Some(4L))
          assertEquals(histogram.stats.map(_.sum), Some(1.5 + 3.0 + (-2.5) + 0.0))
          assertEquals(histogram.stats.flatMap(_.min), Some(-2.5))
          assertEquals(histogram.stats.flatMap(_.max), Some(3.0))
          assertEquals(histogram.positiveBuckets.totalCount, 2L)
          assertEquals(histogram.negativeBuckets.totalCount, 1L)
          assertEquals(histogram.zeroCount, 1L)
        }
      }
    }
  }

  test("record values between 0 and 1 - place into positive buckets with negative indices") {
    Random.scalaUtilRandom[IO].flatMap { implicit R: Random[IO] =>
      val aggregator =
        ExponentialHistogramAggregator[IO, Double](reservoirs, testMaxBuckets, testMaxScale, recordMinMax = true)
      val timeWindow = TimeWindow(100.millis, 200.millis)

      TestControl.executeEmbed {
        for {
          accumulator <- aggregator.createAccumulator
          _ <- accumulator.record(0.5, Attributes.empty, Context.root)
          _ <- accumulator.record(0.25, Attributes.empty, Context.root)
          _ <- accumulator.record(0.75, Attributes.empty, Context.root)
          point <- accumulator.aggregate(timeWindow, Attributes.empty, reset = true)
        } yield {
          assert(point.isDefined)
          val histogram = point.get.asInstanceOf[PointData.ExponentialHistogram]
          assertEquals(histogram.stats.map(_.count), Some(3L))
          assertEquals(histogram.stats.map(_.sum), Some(0.5 + 0.25 + 0.75))
          assertEquals(histogram.positiveBuckets.totalCount, 3L)
          assertEquals(histogram.negativeBuckets.totalCount, 0L)
          assertEquals(histogram.zeroCount, 0L)
          assert(histogram.positiveBuckets.offset < 0, s"offset ${histogram.positiveBuckets.offset} should be negative")
        }
      }
    }
  }

  test("scale downscaling on overflow - reduce scale when bucket span exceeds maxBuckets") {
    Random.scalaUtilRandom[IO].flatMap { implicit R: Random[IO] =>
      val smallMaxBuckets = 4
      val aggregator =
        ExponentialHistogramAggregator[IO, Double](reservoirs, smallMaxBuckets, testMaxScale, recordMinMax = true)
      val timeWindow = TimeWindow(100.millis, 200.millis)

      // at scale 0 (base=2), these values span 6 buckets which exceeds maxBuckets=4
      val values = List(1.0, 2.0, 4.0, 8.0, 16.0, 32.0)

      TestControl.executeEmbed {
        for {
          accumulator <- aggregator.createAccumulator
          _ <- values.traverse_ { value =>
            accumulator.record(value, Attributes.empty, Context.root)
          }
          point <- accumulator.aggregate(timeWindow, Attributes.empty, reset = true)
        } yield {
          assert(point.isDefined)
          val histogram = point.get.asInstanceOf[PointData.ExponentialHistogram]
          assertEquals(histogram.stats.map(_.count), Some(6L))
          assertEquals(histogram.stats.map(_.sum), Some(values.sum))
          assert(histogram.scale < testMaxScale, s"scale ${histogram.scale} should be less than $testMaxScale")
          assertEquals(histogram.positiveBuckets.totalCount, 6L)
          assert(histogram.positiveBuckets.counts.size <= smallMaxBuckets)
        }
      }
    }
  }

  test("ignore NaN and Infinity values") {
    Random.scalaUtilRandom[IO].flatMap { implicit R: Random[IO] =>
      val aggregator =
        ExponentialHistogramAggregator[IO, Double](reservoirs, testMaxBuckets, testMaxScale, recordMinMax = true)
      val timeWindow = TimeWindow(100.millis, 200.millis)

      TestControl.executeEmbed {
        for {
          accumulator <- aggregator.createAccumulator
          _ <- accumulator.record(Double.NaN, Attributes.empty, Context.root)
          _ <- accumulator.record(Double.PositiveInfinity, Attributes.empty, Context.root)
          _ <- accumulator.record(Double.NegativeInfinity, Attributes.empty, Context.root)
          _ <- accumulator.record(5.0, Attributes.empty, Context.root)
          point <- accumulator.aggregate(timeWindow, Attributes.empty, reset = true)
        } yield {
          assert(point.isDefined)
          val histogram = point.get.asInstanceOf[PointData.ExponentialHistogram]
          assertEquals(histogram.stats.map(_.count), Some(1L))
          assertEquals(histogram.stats.map(_.sum), Some(5.0))
        }
      }
    }
  }

  test("toMetricData - produce correct MetricData from aggregated points") {
    PropF.forAllF(
      Gens.telemetryResource,
      Gens.instrumentationScope,
      Gens.instrumentDescriptor,
      Gens.nonEmptyVector(Gens.exponentialHistogramPointData),
      Gens.aggregationTemporality
    ) { (resource, scope, descriptor, points, temporality) =>
      type ExpHistAggregator = Aggregator.Synchronous[IO, Double] {
        type Point = PointData.ExponentialHistogram
      }

      val aggregator =
        ExponentialHistogramAggregator[IO, Double](
          Reservoirs.alwaysOff,
          testMaxBuckets,
          testMaxScale,
          recordMinMax = true
        ).asInstanceOf[ExpHistAggregator]

      val expected =
        MetricData(
          resource = resource,
          scope = scope,
          name = descriptor.name.toString,
          description = descriptor.description,
          unit = descriptor.unit,
          data = MetricPoints.exponentialHistogram(points, temporality)
        )

      for {
        metricData <- aggregator.toMetricData(
          resource,
          scope,
          MetricDescriptor(None, descriptor),
          points,
          temporality
        )
      } yield assertEquals(metricData, expected)
    }
  }

  test("aggregate with recordMinMax disabled - omit min and max from stats") {
    Random.scalaUtilRandom[IO].flatMap { implicit R: Random[IO] =>
      val aggregator =
        ExponentialHistogramAggregator[IO, Double](reservoirs, testMaxBuckets, testMaxScale, recordMinMax = false)
      val timeWindow = TimeWindow(100.millis, 200.millis)

      TestControl.executeEmbed {
        for {
          accumulator <- aggregator.createAccumulator
          _ <- accumulator.record(1.0, Attributes.empty, Context.root)
          _ <- accumulator.record(5.0, Attributes.empty, Context.root)
          _ <- accumulator.record(20.0, Attributes.empty, Context.root)
          point <- accumulator.aggregate(timeWindow, Attributes.empty, reset = true)
        } yield {
          assert(point.isDefined)
          val histogram = point.get.asInstanceOf[PointData.ExponentialHistogram]
          assertEquals(histogram.stats.map(_.count), Some(3L))
          assertEquals(histogram.stats.map(_.sum), Some(26.0))
          assertEquals(histogram.stats.flatMap(_.min), None)
          assertEquals(histogram.stats.flatMap(_.max), None)
        }
      }
    }
  }

  override protected def scalaCheckTestParameters: Test.Parameters =
    super.scalaCheckTestParameters
      .withMinSuccessfulTests(30)
      .withMaxSize(30)

}
