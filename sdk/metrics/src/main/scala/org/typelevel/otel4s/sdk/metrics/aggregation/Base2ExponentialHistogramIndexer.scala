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

import java.lang.Double.doubleToRawLongBits
import java.lang.Long.numberOfLeadingZeros

/** Maps a double value to a bucket index for a given scale.
  *
  * The bucket at index `i` covers the range `(base^i, base^(i+1)]` where `base = 2^(2^(-scale))`. The caller is
  * responsible for passing the absolute value of the measurement — negative measurements are mapped by their absolute
  * value into the negative bucket range by the aggregator.
  *
  * @see
  *   [[https://opentelemetry.io/docs/specs/otel/metrics/data-model/#exponentialhistogram]]
  */
private[aggregation] sealed trait Base2ExponentialHistogramIndexer {

  /** Computes the bucket index for the given value.
    *
    * @param value
    *   a finite double value greater than zero
    */
  def computeIndex(value: Double): Int
}

private[aggregation] object Base2ExponentialHistogramIndexer {

  // IEEE 754 double-precision layout constants
  private val SignificandWidth: Int = 52
  private val ExponentWidth: Int = java.lang.Long.SIZE - 1 - SignificandWidth
  private val ExponentBias: Int = (1 << (ExponentWidth - 1)) - 1
  private val ExponentMask: Long = ((1L << ExponentWidth) - 1L) << SignificandWidth
  private val SignificandMask: Long = (1L << SignificandWidth) - 1L

  /** Creates an indexer for the given scale.
    *
    * @param scale
    *   the resolution of the histogram
    */
  def apply(scale: Int): Base2ExponentialHistogramIndexer = {
    if (scale > 0) {
      new LogarithmicIndexer(scale)
    } else if (scale == 0) {
      ScaleZeroIndexer
    } else {
      new NegativeScaleIndexer(scale)
    }
  }

  /** For positive scales, uses the logarithmic mapping formula: `index = ceil(log(value) * scaleFactor) - 1` where
    * `scaleFactor = 2^scale / ln(2)`.
    */
  private final class LogarithmicIndexer(scale: Int) extends Base2ExponentialHistogramIndexer {
    private val scaleFactor: Double = math.scalb(1.0 / math.log(2), scale)

    def computeIndex(value: Double): Int =
      math.ceil(math.log(value) * scaleFactor).toInt - 1
  }

  /** For scale zero, uses exact IEEE 754 bit extraction to avoid floating-point error.
    *
    * Cross-platform safe: `doubleToRawLongBits` and `numberOfLeadingZeros` are supported as compiler intrinsics on
    * Scala.js (via DataView / Math.clz32) and Scala Native (via LLVM intrinsics).
    */
  private object ScaleZeroIndexer extends Base2ExponentialHistogramIndexer {
    def computeIndex(value: Double): Int = {
      val raw = doubleToRawLongBits(value)
      val rawExponent = ((raw & ExponentMask) >> SignificandWidth).toInt
      val rawSignificand = raw & SignificandMask

      if (rawExponent == 0) {
        // subnormal: compute from leading zeros in the significand
        val leadingZeros = numberOfLeadingZeros(rawSignificand - 1)
        -(ExponentBias + SignificandWidth - 1) + (java.lang.Long.SIZE - 1 - leadingZeros)
      } else {
        val ieeeExponent = rawExponent - ExponentBias
        if (rawSignificand == 0) {
          ieeeExponent - 1 // exact power of 2
        } else {
          ieeeExponent
        }
      }
    }
  }

  /** For negative scales, computes the scale-zero index and right-shifts by `-scale`.
    */
  private final class NegativeScaleIndexer(scale: Int) extends Base2ExponentialHistogramIndexer {
    def computeIndex(value: Double): Int =
      ScaleZeroIndexer.computeIndex(value) >> (-scale)
  }

}
