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

/** Options for configuring base2 exponential bucket histogram aggregation.
  */
sealed trait Base2ExponentialHistogramOptions {

  /** The maximum number of buckets for positive or negative ranges.
    */
  def maxBuckets: Int

  /** The maximum resolution scale.
    */
  def maxScale: Int

  /** Whether min and max should be recorded for histogram points.
    */
  def recordMinMax: Boolean

  def withMaxBuckets(value: Int): Base2ExponentialHistogramOptions

  def withMaxScale(value: Int): Base2ExponentialHistogramOptions

  def withRecordMinMax(value: Boolean): Base2ExponentialHistogramOptions
}

object Base2ExponentialHistogramOptions {

  private val Default =
    Impl(
      maxBuckets = Aggregation.Defaults.ExponentialMaxBuckets,
      maxScale = Aggregation.Defaults.ExponentialMaxScale,
      recordMinMax = true
    )

  /** The default base2 exponential histogram options:
    *   - max buckets: 160
    *   - max scale: 20
    *   - min and max values are recorded
    */
  def default: Base2ExponentialHistogramOptions =
    Default

  /** Creates base2 exponential histogram options.
    */
  def apply(
      maxBuckets: Int,
      maxScale: Int,
      recordMinMax: Boolean
  ): Base2ExponentialHistogramOptions =
    Impl(maxBuckets = maxBuckets, maxScale = maxScale, recordMinMax = recordMinMax)

  private final case class Impl(
      maxBuckets: Int,
      maxScale: Int,
      recordMinMax: Boolean
  ) extends Base2ExponentialHistogramOptions {
    def withMaxBuckets(value: Int): Base2ExponentialHistogramOptions =
      copy(maxBuckets = value)

    def withMaxScale(value: Int): Base2ExponentialHistogramOptions =
      copy(maxScale = value)

    def withRecordMinMax(value: Boolean): Base2ExponentialHistogramOptions =
      copy(recordMinMax = value)
  }
}
