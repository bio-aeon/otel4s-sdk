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

import cats.FlatMap
import cats.data.NonEmptyVector
import cats.effect.Concurrent
import cats.effect.Ref
import cats.syntax.flatMap._
import cats.syntax.functor._
import org.typelevel.otel4s.Attributes
import org.typelevel.otel4s.metrics.MeasurementValue
import org.typelevel.otel4s.sdk.TelemetryResource
import org.typelevel.otel4s.sdk.common.InstrumentationScope
import org.typelevel.otel4s.sdk.context.Context
import org.typelevel.otel4s.sdk.metrics.data._
import org.typelevel.otel4s.sdk.metrics.exemplar.ExemplarReservoir
import org.typelevel.otel4s.sdk.metrics.exemplar.Reservoirs
import org.typelevel.otel4s.sdk.metrics.internal.MetricDescriptor

import scala.annotation.tailrec

/** The exponential histogram aggregation that aggregates values into exponentially-defined buckets.
  *
  * @see
  *   [[https://opentelemetry.io/docs/specs/otel/metrics/sdk/#base2-exponential-bucket-histogram-aggregation]]
  *
  * @tparam F
  *   the higher-kinded type of a polymorphic effect
  *
  * @tparam A
  *   the type of the values to record
  */
private final class ExponentialHistogramAggregator[
    F[_]: Concurrent,
    A: MeasurementValue
](
    reservoirs: Reservoirs[F],
    maxBuckets: Int,
    maxScale: Int,
    recordMinMax: Boolean
) extends Aggregator.Synchronous[F, A] {
  import ExponentialHistogramAggregator._

  type Point = PointData.ExponentialHistogram

  def createAccumulator: F[Aggregator.Accumulator[F, A, PointData.ExponentialHistogram]] =
    for {
      state <- Concurrent[F].ref(emptyState(maxScale))
      reservoir <- reservoirs.fixedSize[A](Runtime.getRuntime.availableProcessors)
    } yield new Accumulator(state, maxBuckets, maxScale, recordMinMax, reservoir)

  def toMetricData(
      resource: TelemetryResource,
      scope: InstrumentationScope,
      descriptor: MetricDescriptor,
      points: NonEmptyVector[PointData.ExponentialHistogram],
      temporality: AggregationTemporality
  ): F[MetricData] =
    Concurrent[F].pure(
      MetricData(
        resource,
        scope,
        descriptor.name.toString,
        descriptor.description,
        descriptor.sourceInstrument.unit,
        MetricPoints.exponentialHistogram(points, temporality)
      )
    )
}

private object ExponentialHistogramAggregator {

  /** Creates an exponential histogram aggregation with the given values.
    *
    * @param reservoirs
    *   the allocator of exemplar reservoirs
    *
    * @param maxBuckets
    *   the maximum number of buckets for positive or negative ranges
    *
    * @param maxScale
    *   the maximum resolution scale
    *
    * @param recordMinMax
    *   whether to record min and max values
    *
    * @tparam F
    *   the higher-kinded type of a polymorphic effect
    *
    * @tparam A
    *   the type of the values to record
    */
  def apply[F[_]: Concurrent, A: MeasurementValue](
      reservoirs: Reservoirs[F],
      maxBuckets: Int,
      maxScale: Int,
      recordMinMax: Boolean
  ): Aggregator.Synchronous[F, A] =
    new ExponentialHistogramAggregator[F, A](reservoirs, maxBuckets, maxScale, recordMinMax)

  private final case class BucketCounts(
      backing: Map[Int, Long],
      indexStart: Int,
      indexEnd: Int,
      totalCount: Long
  )

  private object BucketCounts {
    val empty: BucketCounts = BucketCounts(Map.empty, 0, 0, 0L)
  }

  private final case class State(
      sum: Double,
      min: Double,
      max: Double,
      count: Long,
      zeroCount: Long,
      scale: Int,
      positive: BucketCounts,
      negative: BucketCounts
  )

  private def emptyState(maxScale: Int): State =
    State(0.0, Double.MaxValue, Double.MinValue, 0L, 0L, maxScale, BucketCounts.empty, BucketCounts.empty)

  /** Attempts to increment a bucket, returning the updated counts or the scale reduction needed.
    */
  private def incrementBucket(
      counts: BucketCounts,
      index: Int,
      delta: Long,
      maxBuckets: Int
  ): Either[Int, BucketCounts] = {
    if (counts.totalCount == 0L) {
      Right(BucketCounts(Map(index -> delta), index, index, delta))
    } else {
      val newStart = math.min(counts.indexStart, index)
      val newEnd = math.max(counts.indexEnd, index)
      val newLength = newEnd - newStart + 1

      if (newLength > maxBuckets) {
        Left(computeScaleReduction(newStart, newEnd, maxBuckets))
      } else {
        val newBacking = counts.backing.updated(index, counts.backing.getOrElse(index, 0L) + delta)
        Right(BucketCounts(newBacking, newStart, newEnd, counts.totalCount + delta))
      }
    }
  }

  /** Downscales bucket counts by merging `2^by` adjacent buckets via right-shifting indices.
    */
  private def downscaleBuckets(counts: BucketCounts, by: Int): BucketCounts = {
    if (counts.totalCount == 0L || by <= 0) {
      return counts
    }

    val newBacking = counts.backing.foldLeft(Map.empty[Int, Long]) { case (acc, (index, count)) =>
      val newIndex = index >> by
      acc.updated(newIndex, acc.getOrElse(newIndex, 0L) + count)
    }

    val newStart = newBacking.keysIterator.min
    val newEnd = newBacking.keysIterator.max
    BucketCounts(newBacking, newStart, newEnd, counts.totalCount)
  }

  /** Converts sparse bucket counts to dense [[PointData.ExponentialHistogram.Buckets]] for export.
    */
  private def toBuckets(counts: BucketCounts): PointData.ExponentialHistogram.Buckets = {
    if (counts.totalCount == 0L) {
      PointData.ExponentialHistogram.Buckets.empty
    } else {
      val dense = (counts.indexStart to counts.indexEnd).map(i => counts.backing.getOrElse(i, 0L)).toVector
      PointData.ExponentialHistogram.Buckets(counts.indexStart, dense)
    }
  }

  @tailrec
  private def computeScaleReduction(low: Int, high: Int, maxBuckets: Int, reduction: Int = 0): Int = {
    if (high - low + 1 <= maxBuckets) {
      reduction
    } else {
      computeScaleReduction(low >> 1, high >> 1, maxBuckets, reduction + 1)
    }
  }

  /** Finds the minimum scale reduction needed to accommodate all existing data plus a new index.
    */
  private def scaleReductionForNewIndex(state: State, newIndex: Int, maxBuckets: Int): Int = {
    var low = newIndex
    var high = newIndex

    if (state.positive.totalCount > 0L) {
      low = math.min(low, state.positive.indexStart)
      high = math.max(high, state.positive.indexEnd)
    }
    if (state.negative.totalCount > 0L) {
      low = math.min(low, state.negative.indexStart)
      high = math.max(high, state.negative.indexEnd)
    }

    computeScaleReduction(low, high, maxBuckets)
  }

  private class Accumulator[F[_]: FlatMap, A: MeasurementValue](
      stateRef: Ref[F, State],
      maxBuckets: Int,
      maxScale: Int,
      recordMinMax: Boolean,
      reservoir: ExemplarReservoir[F, A]
  ) extends Aggregator.Accumulator[F, A, PointData.ExponentialHistogram] {

    private val toDouble: A => Double =
      MeasurementValue[A] match {
        case MeasurementValue.LongMeasurementValue(cast) =>
          cast.andThen(_.toDouble)
        case MeasurementValue.DoubleMeasurementValue(cast) =>
          cast
      }

    def aggregate(
        timeWindow: TimeWindow,
        attributes: Attributes,
        reset: Boolean
    ): F[Option[PointData.ExponentialHistogram]] =
      reservoir.collectAndReset(attributes).flatMap { rawExemplars =>
        stateRef.modify { state =>
          val exemplars = rawExemplars.map { e =>
            ExemplarData.double(
              e.filteredAttributes,
              e.timestamp,
              e.traceContext,
              toDouble(e.value)
            )
          }

          val result =
            Option.when(state.count > 0) {
              val stats =
                if (recordMinMax) {
                  PointData.ExponentialHistogram.Stats(state.sum, state.min, state.max, state.count)
                } else {
                  PointData.ExponentialHistogram.Stats.withoutMinMax(state.sum, state.count)
                }

              PointData.exponentialHistogram(
                timeWindow = timeWindow,
                attributes = attributes,
                exemplars = exemplars,
                stats = Some(stats),
                scale = state.scale,
                zeroCount = state.zeroCount,
                zeroThreshold = 0.0,
                positiveBuckets = toBuckets(state.positive),
                negativeBuckets = toBuckets(state.negative)
              )
            }

          val next = if (reset) { emptyState(maxScale) }
          else { state }

          (next, result)
        }
      }

    def record(value: A, attributes: Attributes, context: Context): F[Unit] = {
      val doubleValue = toDouble(value)

      reservoir.offer(value, attributes, context) >> stateRef.update { state =>
        if (!doubleValue.isFinite) {
          state
        } else {
          val updated = state.copy(
            sum = state.sum + doubleValue,
            count = state.count + 1,
            min = if (recordMinMax) { math.min(state.min, doubleValue) }
            else { state.min },
            max = if (recordMinMax) { math.max(state.max, doubleValue) }
            else { state.max }
          )

          if (doubleValue == 0.0) {
            updated.copy(zeroCount = updated.zeroCount + 1)
          } else {
            recordNonZero(updated, doubleValue)
          }
        }
      }
    }

    private def recordNonZero(state: State, value: Double): State = {
      val absValue = math.abs(value)
      val indexer = Base2ExponentialHistogramIndexer(state.scale)
      val index = indexer.computeIndex(absValue)
      val isPositive = value > 0
      val counts = if (isPositive) { state.positive }
      else { state.negative }

      incrementBucket(counts, index, 1L, maxBuckets) match {
        case Right(updated) =>
          if (isPositive) { state.copy(positive = updated) }
          else { state.copy(negative = updated) }

        case Left(_) =>
          val reduction = scaleReductionForNewIndex(state, index, maxBuckets)
          val newScale = state.scale - reduction
          val downscaledPos = downscaleBuckets(state.positive, reduction)
          val downscaledNeg = downscaleBuckets(state.negative, reduction)
          val newIndexer = Base2ExponentialHistogramIndexer(newScale)
          val newIndex = newIndexer.computeIndex(absValue)
          val target = if (isPositive) { downscaledPos }
          else { downscaledNeg }
          // after downscaling, increment must succeed
          val Right(updated) = incrementBucket(target, newIndex, 1L, maxBuckets): @unchecked

          if (isPositive) {
            state.copy(scale = newScale, positive = updated, negative = downscaledNeg)
          } else {
            state.copy(scale = newScale, positive = downscaledPos, negative = updated)
          }
      }
    }

  }

}
