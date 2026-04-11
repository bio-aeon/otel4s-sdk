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

package org.typelevel.otel4s.sdk.metrics.test

import cats.data.NonEmptyVector
import cats.syntax.foldable._
import org.typelevel.otel4s.Attributes
import org.typelevel.otel4s.metrics.BucketBoundaries
import org.typelevel.otel4s.metrics.MeasurementValue
import org.typelevel.otel4s.sdk.metrics.data.PointData
import org.typelevel.otel4s.sdk.metrics.data.TimeWindow

object PointDataUtils {

  def toNumberPoints[A: MeasurementValue](
      values: NonEmptyVector[A],
      attributes: Attributes,
      timeWindow: TimeWindow
  ): NonEmptyVector[PointData.NumberPoint] =
    MeasurementValue[A] match {
      case MeasurementValue.LongMeasurementValue(cast) =>
        values.map { a =>
          PointData.longNumber(
            timeWindow,
            attributes,
            Vector.empty,
            cast(a)
          )
        }

      case MeasurementValue.DoubleMeasurementValue(cast) =>
        values.map { a =>
          PointData.doubleNumber(
            timeWindow,
            attributes,
            Vector.empty,
            cast(a)
          )
        }
    }

  def toExponentialHistogramPoint[A](
      values: NonEmptyVector[A],
      attributes: Attributes,
      timeWindow: TimeWindow,
      scale: Int
  )(implicit N: Numeric[A]): PointData.ExponentialHistogram = {
    import N.mkNumericOps

    val doubleValues = values.toVector.map(_.toDouble)

    val stats: Option[PointData.ExponentialHistogram.Stats] =
      Some(
        PointData.ExponentialHistogram.Stats(
          sum = doubleValues.sum,
          min = doubleValues.min,
          max = doubleValues.max,
          count = doubleValues.size.toLong
        )
      )

    val positiveValues = doubleValues.filter(_ > 0.0)
    val negativeValues = doubleValues.filter(_ < 0.0)
    val zeroCount = doubleValues.count(_ == 0.0).toLong

    def computeIndex(value: Double): Int = {
      val scaleFactor = math.scalb(1.0 / math.log(2), scale)
      math.ceil(math.log(value) * scaleFactor).toInt - 1
    }

    def toBuckets(absValues: Vector[Double]): PointData.ExponentialHistogram.Buckets =
      if (absValues.isEmpty) {
        PointData.ExponentialHistogram.Buckets.empty
      } else {
        val indices = absValues.map(v => computeIndex(v))
        val minIdx = indices.min
        val maxIdx = indices.max
        val counts = indices.foldLeft(Vector.fill(maxIdx - minIdx + 1)(0L)) { (acc, idx) =>
          val pos = idx - minIdx
          acc.updated(pos, acc(pos) + 1L)
        }
        PointData.ExponentialHistogram.Buckets(minIdx, counts)
      }

    PointData.exponentialHistogram(
      timeWindow,
      attributes,
      Vector.empty,
      stats,
      scale,
      zeroCount,
      0.0,
      toBuckets(positiveValues),
      toBuckets(negativeValues.map(v => math.abs(v)))
    )
  }

  def toHistogramPoint[A](
      values: NonEmptyVector[A],
      attributes: Attributes,
      timeWindow: TimeWindow,
      boundaries: BucketBoundaries
  )(implicit N: Numeric[A]): PointData.Histogram = {
    import N.mkNumericOps

    val stats: Option[PointData.Histogram.Stats] =
      Some(
        PointData.Histogram.Stats(
          sum = values.toVector.sum.toDouble,
          min = values.toVector.min.toDouble,
          max = values.toVector.max.toDouble,
          count = values.size
        )
      )

    val counts: Vector[Long] =
      values.foldLeft(Vector.fill(boundaries.length + 1)(0L)) { case (acc, value) =>
        val i = boundaries.boundaries.indexWhere(b => value.toDouble <= b)
        val idx = if (i == -1) boundaries.length else i

        acc.updated(idx, acc(idx) + 1L)
      }

    PointData.histogram(
      timeWindow,
      attributes,
      Vector.empty,
      stats,
      boundaries,
      counts
    )
  }

}
