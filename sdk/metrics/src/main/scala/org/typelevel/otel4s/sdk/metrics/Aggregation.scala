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

import cats.Hash
import cats.Show
import cats.syntax.show._
import org.typelevel.otel4s.metrics.BucketBoundaries

/** The aggregation strategy for measurements.
  *
  * @see
  *   [[https://opentelemetry.io/docs/specs/otel/metrics/sdk/#aggregation]]
  *
  * @param supportedInstruments
  *   the set of supported instruments
  */
sealed abstract class Aggregation(
    supportedInstruments: Set[InstrumentType]
) {
  def compatibleWith(tpe: InstrumentType): Boolean =
    supportedInstruments.contains(tpe)

  override final def toString: String =
    Show[Aggregation].show(this)
}

object Aggregation {

  private[metrics] object Defaults {
    // See https://opentelemetry.io/docs/specs/otel/metrics/sdk/#explicit-bucket-histogram-aggregation
    val Boundaries: BucketBoundaries = BucketBoundaries(
      0d, 5d, 10d, 25d, 50d, 75d, 100d, 250d, 500d, 750d, 1000d, 2500d, 5000d, 7500d, 10000d
    )

    // See https://opentelemetry.io/docs/specs/otel/metrics/sdk/#base2-exponential-bucket-histogram-aggregation
    val ExponentialMaxBuckets: Int = 160
    val ExponentialMaxScale: Int = 20
  }

  /** Drops all measurements and doesn't export any metric.
    */
  def drop: Aggregation = Drop

  /** Chooses an aggregator based on the [[InstrumentType]]:
    *   - counter - [[sum]]
    *   - up down counter - [[sum]]
    *   - observable counter - [[sum]]
    *   - gauge - [[lastValue]]
    *   - observable up down counter - [[sum]]
    *   - histogram - `explicitBucketHistogram`
    *   - observable gauge - [[lastValue]]
    */
  def default: Aggregation = Default

  /** Aggregates measurements into [[org.typelevel.otel4s.sdk.metrics.data.MetricPoints.Sum MetricPoints.Sum]].
    *
    * Compatible instruments:
    *   - [[org.typelevel.otel4s.metrics.Counter Counter]]
    *   - [[org.typelevel.otel4s.metrics.UpDownCounter UpDownCounter]]
    *   - [[org.typelevel.otel4s.metrics.Histogram Histogram]]
    *   - [[org.typelevel.otel4s.metrics.ObservableGauge ObservableGauge]]
    *   - [[org.typelevel.otel4s.metrics.ObservableCounter ObservableCounter]]
    */
  def sum: Aggregation = Sum

  /** Aggregates measurements into [[org.typelevel.otel4s.sdk.metrics.data.MetricPoints.Gauge MetricPoints.Gauge]] using
    * the last seen measurement.
    *
    * Compatible instruments:
    *   - [[org.typelevel.otel4s.metrics.Gauge Gauge]]
    *   - [[org.typelevel.otel4s.metrics.ObservableGauge ObservableGauge]]
    */
  def lastValue: Aggregation = LastValue

  /** Aggregates measurements into an explicit bucket
    * [[org.typelevel.otel4s.sdk.metrics.data.MetricPoints.Histogram MetricPoints.Histogram]] using the default bucket
    * boundaries.
    *
    * Compatible instruments:
    *   - [[org.typelevel.otel4s.metrics.Counter Counter]]
    *   - [[org.typelevel.otel4s.metrics.Histogram Histogram]]
    */
  def explicitBucketHistogram: Aggregation =
    explicitBucketHistogram(ExplicitBucketHistogramOptions.default)

  /** Aggregates measurements into an explicit bucket
    * [[org.typelevel.otel4s.sdk.metrics.data.MetricPoints.Histogram MetricPoints.Histogram]] using the given bucket
    * boundaries.
    *
    * Compatible instruments:
    *   - [[org.typelevel.otel4s.metrics.Counter Counter]]
    *   - [[org.typelevel.otel4s.metrics.Histogram Histogram]]
    *
    * @param boundaries
    *   the boundaries to use
    */
  @deprecated("Use the overloaded variant that takes `ExplicitBucketHistogramOptions`", "0.17.0")
  def explicitBucketHistogram(boundaries: BucketBoundaries): Aggregation =
    explicitBucketHistogram(
      ExplicitBucketHistogramOptions.default.withBucketBoundaries(boundaries)
    )

  /** Aggregates measurements into an explicit bucket
    * [[org.typelevel.otel4s.sdk.metrics.data.MetricPoints.Histogram MetricPoints.Histogram]] using the options.
    *
    * Compatible instruments:
    *   - [[org.typelevel.otel4s.metrics.Counter Counter]]
    *   - [[org.typelevel.otel4s.metrics.Histogram Histogram]]
    *
    * @param options
    *   the explicit bucket histogram options to use
    */
  def explicitBucketHistogram(options: ExplicitBucketHistogramOptions): Aggregation =
    ExplicitBucketHistogram(
      options.bucketBoundaries.getOrElse(Defaults.Boundaries),
      options.recordMinMax
    )

  /** Aggregates measurements into an
    * [[org.typelevel.otel4s.sdk.metrics.data.MetricPoints.ExponentialHistogram MetricPoints.ExponentialHistogram]]
    * using exponential bucket boundaries with default configuration.
    *
    * Compatible instruments:
    *   - [[org.typelevel.otel4s.metrics.Counter Counter]]
    *   - [[org.typelevel.otel4s.metrics.Histogram Histogram]]
    *
    * @see
    *   [[https://opentelemetry.io/docs/specs/otel/metrics/sdk/#base2-exponential-bucket-histogram-aggregation]]
    */
  def base2ExponentialHistogram: Aggregation =
    base2ExponentialHistogram(Base2ExponentialHistogramOptions.default)

  /** Aggregates measurements into an
    * [[org.typelevel.otel4s.sdk.metrics.data.MetricPoints.ExponentialHistogram MetricPoints.ExponentialHistogram]]
    * using exponential bucket boundaries with the given options.
    *
    * Compatible instruments:
    *   - [[org.typelevel.otel4s.metrics.Counter Counter]]
    *   - [[org.typelevel.otel4s.metrics.Histogram Histogram]]
    *
    * @param options
    *   the base2 exponential histogram options to use
    */
  def base2ExponentialHistogram(options: Base2ExponentialHistogramOptions): Aggregation =
    Base2ExponentialHistogram(options.maxBuckets, options.maxScale, options.recordMinMax)

  implicit val aggregationHash: Hash[Aggregation] =
    Hash.fromUniversalHashCode

  implicit val aggregationShow: Show[Aggregation] =
    Show.show {
      case Drop                                              => "Aggregation.Drop"
      case Default                                           => "Aggregation.Default"
      case Sum                                               => "Aggregation.Sum"
      case LastValue                                         => "Aggregation.LastValue"
      case ExplicitBucketHistogram(boundaries, recordMinMax) =>
        show"Aggregation.ExplicitBucketHistogram{boundaries=$boundaries, recordMinMax=$recordMinMax}"
      case Base2ExponentialHistogram(maxBuckets, maxScale, recordMinMax) =>
        s"Aggregation.Base2ExponentialHistogram{maxBuckets=$maxBuckets, maxScale=$maxScale, recordMinMax=$recordMinMax}"
    }

  private[metrics] sealed trait Synchronous { self: Aggregation => }
  private[metrics] sealed trait Asynchronous { self: Aggregation => }

  private[metrics] case object Drop extends Aggregation(Compatability.Drop)

  private[metrics] case object Default extends Aggregation(Compatability.Default) with Synchronous with Asynchronous

  private[metrics] case object Sum extends Aggregation(Compatability.Sum) with Synchronous with Asynchronous

  private[metrics] case object LastValue extends Aggregation(Compatability.LastValue) with Synchronous with Asynchronous

  private[metrics] final case class ExplicitBucketHistogram(
      boundaries: BucketBoundaries,
      recordMinMax: Boolean
  ) extends Aggregation(Compatability.ExplicitBucketHistogram)
      with Synchronous

  private[metrics] final case class Base2ExponentialHistogram(
      maxBuckets: Int,
      maxScale: Int,
      recordMinMax: Boolean
  ) extends Aggregation(Compatability.Base2ExponentialHistogram)
      with Synchronous

  private object Compatability {
    val Drop: Set[InstrumentType] =
      InstrumentType.values

    val Default: Set[InstrumentType] =
      InstrumentType.values

    val Sum: Set[InstrumentType] = Set(
      InstrumentType.Counter,
      InstrumentType.UpDownCounter,
      InstrumentType.ObservableGauge,
      InstrumentType.ObservableUpDownCounter,
      InstrumentType.Histogram
    )

    val LastValue: Set[InstrumentType] =
      Set(InstrumentType.Gauge, InstrumentType.ObservableGauge)

    val ExplicitBucketHistogram: Set[InstrumentType] =
      Set(InstrumentType.Counter, InstrumentType.Histogram)

    val Base2ExponentialHistogram: Set[InstrumentType] =
      Set(InstrumentType.Counter, InstrumentType.Histogram)
  }

}
