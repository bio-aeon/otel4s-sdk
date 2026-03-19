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

sealed trait NumberComparison[A] {
  def equal(expected: A, actual: A): Boolean
  def render(value: A): String
}

object NumberComparison {

  def apply[A](implicit ev: NumberComparison[A]): NumberComparison[A] =
    ev

  implicit val longExact: NumberComparison[Long] = new NumberComparison[Long] {
    def equal(expected: Long, actual: Long): Boolean =
      expected == actual

    def render(value: Long): String =
      value.toString
  }

  implicit val doubleDefault: NumberComparison[Double] =
    within(1e-6)

  def within(tolerance: Double): NumberComparison[Double] =
    new NumberComparison[Double] {
      def equal(expected: Double, actual: Double): Boolean =
        math.abs(expected - actual) <= tolerance

      def render(value: Double): String =
        BigDecimal.decimal(value).bigDecimal.stripTrailingZeros.toPlainString
    }
}
