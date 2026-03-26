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

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop
import org.scalacheck.Test
import org.typelevel.otel4s.sdk.metrics.scalacheck.Gens

class Base2ExponentialHistogramIndexerSuite extends ScalaCheckSuite {

  property("map a value into the correct bucket range") {
    Prop.forAll(Gens.exponentialHistogramScale, Gen.choose(1.0, 1e15)) { (scale, value) =>
      val indexer = Base2ExponentialHistogramIndexer(scale)
      val index = indexer.computeIndex(value)
      val base = math.pow(2.0, math.pow(2.0, -scale.toDouble))
      val lowerBound = math.pow(base, index.toDouble)
      val upperBound = math.pow(base, (index + 1).toDouble)

      assert(
        value > lowerBound && value <= upperBound,
        s"scale=$scale, value=$value, index=$index, bounds=($lowerBound, $upperBound]"
      )
    }
  }

  property("index increases monotonically with value") {
    Prop.forAll(Gens.exponentialHistogramScale, Gen.choose(Double.MinPositiveValue, 1e15), Gen.choose(1.0, 100.0)) {
      (scale, value, multiplier) =>
        val indexer = Base2ExponentialHistogramIndexer(scale)
        val smallerIndex = indexer.computeIndex(value)
        val largerIndex = indexer.computeIndex(value * multiplier)

        assert(
          largerIndex >= smallerIndex,
          s"scale=$scale, value=$value, multiplier=$multiplier, smallerIndex=$smallerIndex, largerIndex=$largerIndex"
        )
    }
  }

  property("scale zero indexer agrees with logarithmic formula for normal values") {
    Prop.forAll(Gen.choose(1.0, 1e15)) { value =>
      val scaleZeroIndex = Base2ExponentialHistogramIndexer(0).computeIndex(value)
      val logIndex = math.ceil(math.log(value) / math.log(2)).toInt - 1

      assertEquals(scaleZeroIndex, logIndex, s"value=$value")
    }
  }

  property("downscaling by 1 is equivalent to right-shifting the index") {
    Prop.forAll(Gen.choose(1, 8), Gen.choose(1.0, 1e6)) { (scale, value) =>
      val higherIndex = Base2ExponentialHistogramIndexer(scale).computeIndex(value)
      val lowerIndex = Base2ExponentialHistogramIndexer(scale - 1).computeIndex(value)

      assertEquals(lowerIndex, higherIndex >> 1, s"scale=$scale, value=$value")
    }
  }

  test("compute the correct index for exact powers of 2 at scale 0") {
    val indexer = Base2ExponentialHistogramIndexer(0)
    assertEquals(indexer.computeIndex(1.0), -1)
    assertEquals(indexer.computeIndex(2.0), 0)
    assertEquals(indexer.computeIndex(4.0), 1)
    assertEquals(indexer.computeIndex(8.0), 2)
    assertEquals(indexer.computeIndex(16.0), 3)
  }

  test("compute the correct index for subnormal values at scale 0") {
    val indexer = Base2ExponentialHistogramIndexer(0)
    // Double.MinPositiveValue = 2^(-1074), so index should be -1075
    assertEquals(indexer.computeIndex(Double.MinPositiveValue), -1075)
  }

  override protected def scalaCheckTestParameters: Test.Parameters =
    super.scalaCheckTestParameters
      .withMinSuccessfulTests(100)
      .withMaxSize(100)

}
