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

package org.typelevel.otel4s.sdk.metrics
package scalacheck

import cats.data.NonEmptyVector
import cats.kernel.Order
import org.scalacheck.Arbitrary
import org.scalacheck.Gen
import org.typelevel.ci.CIString
import org.typelevel.otel4s.metrics.BucketBoundaries
import org.typelevel.otel4s.sdk.metrics.data.AggregationTemporality
import org.typelevel.otel4s.sdk.metrics.data.ExemplarData
import org.typelevel.otel4s.sdk.metrics.data.MetricData
import org.typelevel.otel4s.sdk.metrics.data.MetricPoints
import org.typelevel.otel4s.sdk.metrics.data.PointData
import org.typelevel.otel4s.sdk.metrics.data.TimeWindow
import org.typelevel.otel4s.sdk.metrics.internal.AsynchronousMeasurement
import org.typelevel.otel4s.sdk.metrics.internal.InstrumentDescriptor
import org.typelevel.otel4s.sdk.metrics.view.InstrumentSelector

import scala.concurrent.duration._

trait Gens extends org.typelevel.otel4s.sdk.scalacheck.Gens {

  val aggregationTemporality: Gen[AggregationTemporality] =
    Gen.oneOf(AggregationTemporality.Delta, AggregationTemporality.Cumulative)

  val bucketBoundaries: Gen[BucketBoundaries] =
    for {
      size <- Gen.choose(0, 20)
      b <- Gen.containerOfN[Vector, Double](size, Gen.choose(-100.0, 100.0))
    } yield BucketBoundaries(b.distinct.sorted)

  val ciString: Gen[CIString] =
    Gens.nonEmptyString.map(CIString(_))

  val instrumentType: Gen[InstrumentType] =
    Gen.oneOf(InstrumentType.values)

  val synchronousInstrumentType: Gen[InstrumentType.Synchronous] =
    Gen.oneOf(InstrumentType.values.collect { case tpe: InstrumentType.Synchronous =>
      tpe
    })

  val asynchronousInstrumentType: Gen[InstrumentType.Asynchronous] =
    Gen.oneOf(InstrumentType.values.collect { case tpe: InstrumentType.Asynchronous =>
      tpe
    })

  val synchronousInstrumentDescriptor: Gen[InstrumentDescriptor.Synchronous] =
    for {
      tpe <- Gens.synchronousInstrumentType
      name <- Gens.ciString
      description <- Gen.option(Gen.alphaNumStr)
      unit <- Gen.option(Gen.alphaNumStr)
    } yield InstrumentDescriptor.synchronous(name, description, unit, None, tpe)

  val asynchronousInstrumentDescriptor: Gen[InstrumentDescriptor.Asynchronous] =
    for {
      tpe <- Gens.asynchronousInstrumentType
      name <- Gens.ciString
      description <- Gen.option(Gen.alphaNumStr)
      unit <- Gen.option(Gen.alphaNumStr)
    } yield InstrumentDescriptor.asynchronous(name, description, unit, tpe)

  val instrumentDescriptor: Gen[InstrumentDescriptor] =
    Gen.oneOf(synchronousInstrumentDescriptor, asynchronousInstrumentDescriptor)

  val instrumentSelector: Gen[InstrumentSelector] = {
    def withProp(
        valueOpt: Option[String],
        f: InstrumentSelector.Builder => String => InstrumentSelector.Builder
    ): InstrumentSelector.Builder => InstrumentSelector.Builder =
      valueOpt.fold((a: InstrumentSelector.Builder) => a) { value => (a: InstrumentSelector.Builder) =>
        f(a)(value)
      }

    for {
      instrumentType <- Gens.instrumentType
      instrumentName <- Gen.option(Gen.alphaNumStr)
      instrumentUnit <- Gen.option(Gen.alphaNumStr)
      meterName <- Gen.option(Gen.alphaNumStr)
      meterVersion <- Gen.option(Gen.alphaNumStr)
      meterSchemaUrl <- Gen.option(Gen.alphaNumStr)
    } yield {
      val builder =
        InstrumentSelector.builder.withInstrumentType(instrumentType)

      val f = Seq(
        withProp(instrumentName, _.withInstrumentName),
        withProp(instrumentUnit, _.withInstrumentUnit),
        withProp(meterName, _.withMeterName),
        withProp(meterVersion, _.withMeterVersion),
        withProp(meterSchemaUrl, _.withMeterSchemaUrl),
      ).reduce(_ andThen _)

      f(builder).build
    }
  }

  val timeWindow: Gen[TimeWindow] =
    for {
      start <- Gen.chooseNum(1, Int.MaxValue)
      delta <- Gen.choose(1, 100)
    } yield TimeWindow(start.millis, start.millis + delta.seconds)

  val longExemplarData: Gen[ExemplarData.LongExemplar] =
    for {
      attributes <- Gens.attributes
      timestamp <- Gens.timestamp
      traceContext <- Gen.option(Gens.traceContext)
      value <- Gen.long
    } yield ExemplarData.long(attributes, timestamp, traceContext, value)

  val doubleExemplarData: Gen[ExemplarData.DoubleExemplar] =
    for {
      attributes <- Gens.attributes
      timestamp <- Gens.timestamp
      traceContext <- Gen.option(Gens.traceContext)
      value <- Gen.double
    } yield ExemplarData.double(attributes, timestamp, traceContext, value)

  val exemplarData: Gen[ExemplarData] =
    Gen.oneOf(longExemplarData, doubleExemplarData)

  def longNumberPointDataGen(gen: Gen[Long]): Gen[PointData.LongNumber] =
    for {
      window <- Gens.timeWindow
      attributes <- Gens.attributes
      exemplars <- Gen.listOfN(1, Gens.longExemplarData)
      value <- gen
    } yield PointData.longNumber(window, attributes, exemplars.toVector, value)

  def doubleNumberPointDataGen(gen: Gen[Double]): Gen[PointData.DoubleNumber] =
    for {
      window <- Gens.timeWindow
      attributes <- Gens.attributes
      exemplars <- Gen.listOfN(1, Gens.doubleExemplarData)
      value <- gen
    } yield PointData.doubleNumber(
      window,
      attributes,
      exemplars.toVector,
      value
    )

  val longNumberPointData: Gen[PointData.LongNumber] =
    longNumberPointDataGen(Gen.long)

  val doubleNumberPointData: Gen[PointData.DoubleNumber] =
    doubleNumberPointDataGen(Gen.double)

  val histogramPointData: Gen[PointData.Histogram] = {
    def stats(values: List[Double]): Gen[Option[PointData.Histogram.Stats]] =
      if (values.nonEmpty)
        Gen
          .oneOf(
            PointData.Histogram.Stats(
              sum = values.sum,
              min = values.min,
              max = values.max,
              count = values.size.toLong
            ),
            PointData.Histogram.Stats.withoutMinMax(
              sum = values.sum,
              count = values.size.toLong
            )
          )
          .map(Some(_))
      else Gen.const(None)

    def counts(
        values: List[Double],
        boundaries: BucketBoundaries
    ): Vector[Long] =
      values.foldLeft(Vector.fill(boundaries.length + 1)(0L)) { case (acc, value) =>
        val i = boundaries.boundaries.indexWhere(b => value <= b)
        val idx = if (i == -1) boundaries.length else i

        acc.updated(idx, acc(idx) + 1L)
      }

    // retains last exemplar for each bucket
    def alignExemplars(
        values: List[Double],
        boundaries: BucketBoundaries,
        exemplars: List[ExemplarData.DoubleExemplar]
    ): Vector[ExemplarData.DoubleExemplar] =
      values
        .foldLeft(
          Vector.fill(boundaries.length + 1)(Option.empty[Double])
        ) { case (acc, value) =>
          val i = boundaries.boundaries.indexWhere(b => value <= b)
          val idx = if (i == -1) boundaries.length else i

          acc.updated(idx, Some(value))
        }
        .collect { case Some(value) => value }
        .zip(exemplars)
        .map { case (value, exemplar) =>
          ExemplarData.double(
            exemplar.filteredAttributes,
            exemplar.timestamp,
            exemplar.traceContext,
            value
          )
        }

    for {
      window <- Gens.timeWindow
      attributes <- Gens.attributes
      boundaries <- Gens.bucketBoundaries
      exemplars <- Gen.listOfN(boundaries.length + 1, Gens.doubleExemplarData)
      values <- Gen.listOfN(boundaries.length + 1, Gen.double)
      histogramStats <- stats(values)
    } yield PointData.histogram(
      window,
      attributes,
      alignExemplars(values, boundaries, exemplars),
      histogramStats,
      boundaries,
      counts(values, boundaries)
    )
  }

  val exponentialHistogramBuckets: Gen[PointData.ExponentialHistogram.Buckets] =
    for {
      offset <- Gen.choose(-10, 10)
      size <- Gen.choose(0, 20)
      counts <- Gen.containerOfN[Vector, Long](size, Gen.choose(0L, 100L))
    } yield PointData.ExponentialHistogram.Buckets(offset, counts)

  val exponentialHistogramMaxBuckets: Gen[Int] =
    Gen.choose(1, Aggregation.Defaults.ExponentialMaxBuckets)

  val exponentialHistogramScale: Gen[Int] =
    Gen.choose(0, 8)

  val exponentialHistogramPointData: Gen[PointData.ExponentialHistogram] = {
    // Invariants maintained by this generator:
    // 1. stats.count == positiveBuckets.totalCount + negativeBuckets.totalCount + zeroCount
    // 2. stats.sum/min/max are computed from the actual positive, negative, and zero values
    // 3. exemplar values correspond to actual observed non-zero values
    // 4. bucket indices are computed from absolute values using the exponential mapping formula

    def stats(values: List[Double]): Gen[Option[PointData.ExponentialHistogram.Stats]] =
      if (values.nonEmpty)
        Gen
          .oneOf(
            PointData.ExponentialHistogram.Stats(
              sum = values.sum,
              min = values.min,
              max = values.max,
              count = values.size.toLong
            ),
            PointData.ExponentialHistogram.Stats.withoutMinMax(
              sum = values.sum,
              count = values.size.toLong
            )
          )
          .map(Some(_))
      else Gen.const(None)

    // maps a positive double to a bucket index at the given scale
    def computeIndex(value: Double, scale: Int): Int = {
      val scaleFactor = math.scalb(1.0 / math.log(2), scale)
      math.ceil(math.log(value) * scaleFactor).toInt - 1
    }

    // builds Buckets from a list of positive values at the given scale
    def toBuckets(absValues: List[Double], scale: Int): PointData.ExponentialHistogram.Buckets =
      if (absValues.isEmpty) PointData.ExponentialHistogram.Buckets.empty
      else {
        val indices = absValues.map(v => computeIndex(v, scale))
        val minIdx = indices.min
        val maxIdx = indices.max
        val counts = indices.foldLeft(Vector.fill(maxIdx - minIdx + 1)(0L)) { (acc, idx) =>
          val pos = idx - minIdx
          acc.updated(pos, acc(pos) + 1L)
        }
        PointData.ExponentialHistogram.Buckets(minIdx, counts)
      }

    for {
      window <- Gens.timeWindow
      attributes <- Gens.attributes
      scale <- Gens.exponentialHistogramScale
      boundaries <- Gens.bucketBoundaries
      size = boundaries.length + 1
      // values in [0.01, 100.0] to cover both sub-unit and above-unit ranges
      // while keeping bucket index spans manageable at all scales
      posAbsValues <- Gen.listOfN(size, Gen.choose(0.01, 100.0))
      negAbsValues <- Gen.listOfN(size, Gen.choose(0.01, 100.0))
      zeroCount <- Gen.choose(0L, size.toLong)
      allValues = posAbsValues ++ negAbsValues.map(-_) ++ List.fill(zeroCount.toInt)(0.0)
      nonZeroValues = posAbsValues ++ negAbsValues.map(-_)
      exemplars <- Gen.listOfN(nonZeroValues.size, Gens.doubleExemplarData)
      histogramStats <- stats(allValues)
    } yield PointData.exponentialHistogram(
      window,
      attributes,
      nonZeroValues
        .zip(exemplars)
        .map { case (value, exemplar) =>
          ExemplarData.double(exemplar.filteredAttributes, exemplar.timestamp, exemplar.traceContext, value)
        }
        .toVector,
      histogramStats,
      scale,
      zeroCount,
      0.0,
      toBuckets(posAbsValues, scale),
      toBuckets(negAbsValues, scale)
    )
  }

  val pointDataNumber: Gen[PointData.NumberPoint] =
    Gen.oneOf(longNumberPointData, doubleNumberPointData)

  val pointData: Gen[PointData] =
    Gen.oneOf(longNumberPointData, doubleNumberPointData, histogramPointData, exponentialHistogramPointData)

  // See https://opentelemetry.io/docs/specs/otel/metrics/data-model/#sums
  // For delta monotonic sums, this means the reader SHOULD expect non-negative values.
  // For cumulative monotonic sums, this means the reader SHOULD expect values that are not less than the previous value.
  val sumMetricPoints: Gen[MetricPoints.Sum] = {
    implicit val pointNumberOrder: Order[PointData.NumberPoint] =
      Order.by {
        case long: PointData.LongNumber     => long.value.toDouble
        case double: PointData.DoubleNumber => double.value
      }

    for {
      isMonotonic <- Arbitrary.arbitrary[Boolean]
      temporality <- Gens.aggregationTemporality
      positiveOnly = isMonotonic && temporality == AggregationTemporality.Delta
      points <- Gen.oneOf(
        Gens.nonEmptyVector(
          longNumberPointDataGen(
            if (positiveOnly) Gen.posNum[Long] else Gen.long
          )
        ),
        Gens.nonEmptyVector(
          doubleNumberPointDataGen(
            if (positiveOnly) Gen.posNum[Double] else Gen.double
          )
        )
      )
    } yield {
      val values =
        if (isMonotonic && temporality == AggregationTemporality.Cumulative) {
          NonEmptyVector.fromVectorUnsafe(
            points
              .sorted[PointData.NumberPoint]
              .toVector
              .distinctBy(_.value)
          )
        } else {
          points
        }

      MetricPoints.sum(
        values,
        isMonotonic,
        temporality
      )
    }
  }

  val gaugeMetricPoints: Gen[MetricPoints.Gauge] =
    for {
      points <- Gen.oneOf(
        Gens.nonEmptyVector(longNumberPointData),
        Gens.nonEmptyVector(doubleNumberPointData)
      )
    } yield MetricPoints.gauge(points)

  val histogramMetricPoints: Gen[MetricPoints.Histogram] =
    for {
      point <- histogramPointData
      points <- Gen.listOf(histogramPointData)
      temporality <- Gens.aggregationTemporality
    } yield MetricPoints.histogram(
      NonEmptyVector(point, points.toVector),
      temporality
    )

  val exponentialHistogramMetricPoints: Gen[MetricPoints.ExponentialHistogram] =
    for {
      point <- exponentialHistogramPointData
      points <- Gen.listOf(exponentialHistogramPointData)
      temporality <- Gens.aggregationTemporality
    } yield MetricPoints.exponentialHistogram(
      NonEmptyVector(point, points.toVector),
      temporality
    )

  val metricPoints: Gen[MetricPoints] =
    Gen.oneOf(sumMetricPoints, gaugeMetricPoints, histogramMetricPoints, exponentialHistogramMetricPoints)

  val metricData: Gen[MetricData] =
    for {
      resource <- Gens.telemetryResource
      scope <- Gens.instrumentationScope
      name <- Gens.nonEmptyString
      description <- Gen.option(Gens.nonEmptyString)
      unit <- Gen.option(Gens.nonEmptyString)
      data <- Gens.metricPoints
    } yield MetricData(resource, scope, name, description, unit, data)

  def asynchronousMeasurement[A](gen: Gen[A]): Gen[AsynchronousMeasurement[A]] =
    for {
      timeWindow <- Gens.timeWindow
      attributes <- Gens.attributes
      value <- gen
    } yield AsynchronousMeasurement(timeWindow, attributes, value)

}

object Gens extends Gens
