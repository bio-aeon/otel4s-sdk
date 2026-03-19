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

import cats.data.NonEmptyList
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.Attributes
import org.typelevel.otel4s.metrics.BucketBoundaries
import org.typelevel.otel4s.metrics.MeasurementValue
import org.typelevel.otel4s.metrics.MeasurementValue.DoubleMeasurementValue
import org.typelevel.otel4s.metrics.MeasurementValue.LongMeasurementValue
import org.typelevel.otel4s.sdk.metrics.data.PointData
import org.typelevel.otel4s.sdk.testkit.AttributesExpectation
import org.typelevel.otel4s.sdk.testkit.ExpectationChecks

/** A partial expectation for a single SDK metric point.
  *
  * `PointExpectation` is used together with metric expectations to express which individual points should be present.
  * Unspecified properties are ignored.
  */
sealed trait PointExpectation {

  /** An optional human-readable clue shown in mismatch messages. */
  def clue: Option[String]

  /** Requires the point attributes to satisfy the given expectation. */
  def attributes(expectation: AttributesExpectation): PointExpectation

  /** Requires the point attributes to match exactly. */
  def attributesExact(attributes: Attributes): PointExpectation

  /** Requires the point attributes to match exactly. */
  def attributesExact(attributes: Attribute[_]*): PointExpectation

  /** Requires the point attributes to be empty. */
  def attributesEmpty: PointExpectation

  /** Requires the point attributes to contain the given attributes. */
  def attributesSubset(attributes: Attributes): PointExpectation

  /** Requires the point attributes to contain the given attributes. */
  def attributesSubset(attributes: Attribute[_]*): PointExpectation

  /** Attaches a human-readable clue to this expectation. */
  def clue(text: String): PointExpectation

  /** Checks the given point and returns structured mismatches when the expectation does not match. */
  def check(point: PointData): Either[NonEmptyList[PointExpectation.Mismatch], Unit]

  /** Returns `true` if this expectation matches the given point. */
  final def matches(point: PointData): Boolean =
    check(point).isRight
}

object PointExpectation {

  /** A structured reason explaining why a [[PointExpectation]] did not match a point. */
  sealed trait Mismatch extends Product with Serializable {

    /** A human-readable description of the mismatch. */
    def message: String
  }

  object Mismatch {

    /** Indicates that the point type did not match. */
    sealed trait TypeMismatch extends Mismatch {

      /** The expected point type name. */
      def expected: String

      /** The actual point type name. */
      def actual: String
    }

    /** Indicates that the point value did not match. */
    sealed trait ValueMismatch extends Mismatch {

      /** The expected value rendered for diagnostics. */
      def expected: String

      /** The actual value rendered for diagnostics. */
      def actual: String
    }

    /** Indicates that the histogram count did not match. */
    sealed trait CountMismatch extends Mismatch {

      /** The expected count. */
      def expected: Long

      /** The actual count. */
      def actual: Long
    }

    /** Indicates that the histogram sum did not match. */
    sealed trait SumMismatch extends Mismatch {

      /** The expected sum. */
      def expected: Double

      /** The actual sum. */
      def actual: Double
    }

    /** Indicates that the histogram min did not match. */
    sealed trait MinMismatch extends Mismatch {

      /** The expected min, or absence of one. */
      def expected: Option[Double]

      /** The actual min, or absence of one. */
      def actual: Option[Double]
    }

    /** Indicates that the histogram max did not match. */
    sealed trait MaxMismatch extends Mismatch {

      /** The expected max, or absence of one. */
      def expected: Option[Double]

      /** The actual max, or absence of one. */
      def actual: Option[Double]
    }

    /** Indicates that the histogram stats did not match. */
    sealed trait StatsMismatch extends Mismatch {

      /** The expected stats, or absence of stats. */
      def expected: Option[PointData.Histogram.Stats]

      /** The actual stats, or absence of stats. */
      def actual: Option[PointData.Histogram.Stats]
    }

    /** Indicates that the histogram boundaries did not match. */
    sealed trait BoundariesMismatch extends Mismatch {

      /** The expected boundaries. */
      def expected: BucketBoundaries

      /** The actual boundaries. */
      def actual: BucketBoundaries
    }

    /** Indicates that the histogram bucket counts did not match. */
    sealed trait CountsMismatch extends Mismatch {

      /** The expected bucket counts. */
      def expected: List[Long]

      /** The actual bucket counts. */
      def actual: List[Long]
    }

    /** Indicates that point attributes did not satisfy the nested expectation. */
    sealed trait AttributesMismatch extends Mismatch {

      /** Nested attribute mismatches. */
      def mismatches: NonEmptyList[AttributesExpectation.Mismatch]
    }

    /** Indicates that the exemplar count did not match. */
    sealed trait ExemplarCountMismatch extends Mismatch {

      /** The expected number of exemplars. */
      def expected: Int

      /** The actual number of exemplars. */
      def actual: Int
    }

    /** Indicates that one or more exemplar expectations failed. */
    sealed trait ExemplarMismatch extends Mismatch {

      /** The exemplar mismatches. */
      def mismatches: NonEmptyList[ExemplarExpectation.Mismatch]

      /** An optional clue attached to the exemplar expectation. */
      def clue: Option[String]
    }

    /** Indicates that a custom point predicate returned `false`. */
    sealed trait PredicateMismatch extends Mismatch {

      /** The clue describing the failed predicate. */
      def clue: String
    }

    /** Creates a mismatch for an unexpected point type. */
    def typeMismatch(expected: String, actual: String): TypeMismatch =
      TypeMismatchImpl(expected, actual)

    /** Creates a mismatch for an unexpected point value. */
    def valueMismatch(expected: String, actual: String): ValueMismatch =
      ValueMismatchImpl(expected, actual)

    /** Creates a mismatch for an unexpected histogram count. */
    def countMismatch(expected: Long, actual: Long): CountMismatch =
      CountMismatchImpl(expected, actual)

    /** Creates a mismatch for an unexpected histogram sum. */
    def sumMismatch(expected: Double, actual: Double): SumMismatch =
      SumMismatchImpl(expected, actual)

    /** Creates a mismatch for an unexpected histogram min. */
    def minMismatch(expected: Option[Double], actual: Option[Double]): MinMismatch =
      MinMismatchImpl(expected, actual)

    /** Creates a mismatch for an unexpected histogram max. */
    def maxMismatch(expected: Option[Double], actual: Option[Double]): MaxMismatch =
      MaxMismatchImpl(expected, actual)

    /** Creates a mismatch for unexpected histogram stats. */
    def statsMismatch(
        expected: Option[PointData.Histogram.Stats],
        actual: Option[PointData.Histogram.Stats]
    ): StatsMismatch =
      StatsMismatchImpl(expected, actual)

    /** Creates a mismatch for unexpected histogram boundaries. */
    def boundariesMismatch(expected: BucketBoundaries, actual: BucketBoundaries): BoundariesMismatch =
      BoundariesMismatchImpl(expected, actual)

    /** Creates a mismatch for unexpected histogram bucket counts. */
    def countsMismatch(expected: List[Long], actual: List[Long]): CountsMismatch =
      CountsMismatchImpl(expected, actual)

    /** Creates a mismatch for point attributes that failed validation. */
    def attributesMismatch(mismatches: NonEmptyList[AttributesExpectation.Mismatch]): AttributesMismatch =
      AttributesMismatchImpl(mismatches)

    /** Creates a mismatch for an unexpected exemplar count. */
    def exemplarCountMismatch(expected: Int, actual: Int): ExemplarCountMismatch =
      ExemplarCountMismatchImpl(expected, actual)

    /** Creates a mismatch for failed exemplar expectations. */
    def exemplarMismatch(
        mismatches: NonEmptyList[ExemplarExpectation.Mismatch],
        clue: Option[String]
    ): ExemplarMismatch =
      ExemplarMismatchImpl(mismatches, clue)

    /** Creates a mismatch for a failed custom predicate. */
    def predicateMismatch(clue: String): PredicateMismatch =
      PredicateMismatchImpl(clue)

    private final case class TypeMismatchImpl(expected: String, actual: String) extends TypeMismatch {
      def message: String =
        s"type mismatch: expected '$expected', got '$actual'"
    }

    private final case class ValueMismatchImpl(expected: String, actual: String) extends ValueMismatch {
      def message: String =
        s"value mismatch: expected '$expected', got '$actual'"
    }

    private final case class CountMismatchImpl(expected: Long, actual: Long) extends CountMismatch {
      def message: String =
        s"count mismatch: expected $expected, got $actual"
    }

    private final case class SumMismatchImpl(expected: Double, actual: Double) extends SumMismatch {
      def message: String =
        s"sum mismatch: expected ${NumberComparison[Double].render(expected)}, got ${NumberComparison[Double].render(actual)}"
    }

    private final case class MinMismatchImpl(expected: Option[Double], actual: Option[Double]) extends MinMismatch {
      def message: String =
        s"min mismatch: expected ${expected.fold("<missing>")(NumberComparison[Double].render)}, got ${actual.fold("<missing>")(NumberComparison[Double].render)}"
    }

    private final case class MaxMismatchImpl(expected: Option[Double], actual: Option[Double]) extends MaxMismatch {
      def message: String =
        s"max mismatch: expected ${expected.fold("<missing>")(NumberComparison[Double].render)}, got ${actual.fold("<missing>")(NumberComparison[Double].render)}"
    }

    private final case class StatsMismatchImpl(
        expected: Option[PointData.Histogram.Stats],
        actual: Option[PointData.Histogram.Stats]
    ) extends StatsMismatch {
      def message: String =
        s"stats mismatch: expected ${expected.fold("<missing>")(_.toString)}, got ${actual.fold("<missing>")(_.toString)}"
    }

    private final case class BoundariesMismatchImpl(expected: BucketBoundaries, actual: BucketBoundaries)
        extends BoundariesMismatch {
      def message: String =
        s"boundaries mismatch: expected $expected, got $actual"
    }

    private final case class CountsMismatchImpl(expected: List[Long], actual: List[Long]) extends CountsMismatch {
      def message: String =
        s"counts mismatch: expected $expected, got $actual"
    }

    private final case class AttributesMismatchImpl(mismatches: NonEmptyList[AttributesExpectation.Mismatch])
        extends AttributesMismatch {
      def message: String =
        s"attributes mismatch: ${mismatches.toList.map(_.message).mkString(", ")}"
    }

    private final case class ExemplarCountMismatchImpl(expected: Int, actual: Int) extends ExemplarCountMismatch {
      def message: String =
        s"exemplar count mismatch: expected $expected, got $actual"
    }

    private final case class ExemplarMismatchImpl(
        mismatches: NonEmptyList[ExemplarExpectation.Mismatch],
        clue: Option[String]
    ) extends ExemplarMismatch {
      def message: String = {
        val rendered = mismatches.toList.map(_.message).mkString(", ")
        val clueSuffix = clue.fold("")(value => s" [$value]")
        s"exemplar mismatch$clueSuffix: $rendered"
      }
    }

    private final case class PredicateMismatchImpl(clue: String) extends PredicateMismatch {
      def message: String =
        s"predicate mismatch: $clue"
    }
  }

  /** A point expectation for numeric points. */
  sealed trait Numeric[A] extends PointExpectation {

    /** Requires the point value to match exactly. */
    def value(value: A): Numeric[A]

    /** Adds a custom predicate over the numeric point data. */
    def where(f: PointData.NumberPoint.Aux[A] => Boolean): Numeric[A]

    /** Adds a custom predicate over the numeric point data with a clue shown in mismatches. */
    def where(clue: String)(f: PointData.NumberPoint.Aux[A] => Boolean): Numeric[A]

    /** Requires the point to contain exactly the given number of exemplars. */
    def exemplarCount(count: Int): Numeric[A]

    /** Requires the point to contain exemplars matching all given expectations. */
    def containsExemplars(first: ExemplarExpectation[A], rest: ExemplarExpectation[A]*): Numeric[A]

    /** Requires the point attributes to satisfy the given expectation. */
    def attributes(expectation: AttributesExpectation): Numeric[A]

    /** Requires the point attributes to match exactly. */
    def attributesExact(attributes: Attributes): Numeric[A]

    /** Requires the point attributes to match exactly. */
    def attributesExact(attributes: Attribute[_]*): Numeric[A]

    /** Requires the point attributes to be empty. */
    def attributesEmpty: Numeric[A]

    /** Requires the point attributes to contain the given subset. */
    def attributesSubset(attributes: Attributes): Numeric[A]

    /** Requires the point attributes to contain the given subset. */
    def attributesSubset(attributes: Attribute[_]*): Numeric[A]

    /** Attaches a human-readable clue to this expectation. */
    def clue(text: String): Numeric[A]
  }

  /** A point expectation for histogram points. */
  sealed trait Histogram extends PointExpectation {

    /** Requires the histogram stats to match exactly. */
    def stats(stats: PointData.Histogram.Stats): Histogram

    /** Requires the histogram point to have no stats. */
    def withoutStats: Histogram

    /** Requires the histogram point count to match exactly. */
    def count(count: Long): Histogram

    /** Requires the histogram point sum to match exactly. */
    def sum(sum: Double): Histogram

    /** Requires the histogram point min to match exactly. */
    def min(min: Double): Histogram

    /** Requires the histogram point to have no min value. */
    def withoutMin: Histogram

    /** Requires the histogram point max to match exactly. */
    def max(max: Double): Histogram

    /** Requires the histogram point to have no max value. */
    def withoutMax: Histogram

    /** Requires the histogram bucket boundaries to match exactly. */
    def boundaries(boundaries: BucketBoundaries): Histogram

    /** Requires the histogram bucket counts to match exactly. */
    def counts(counts: List[Long]): Histogram

    /** Requires the histogram bucket counts to match exactly. */
    def counts(counts: Long*): Histogram

    /** Adds a custom predicate over histogram point data. */
    def where(f: PointData.Histogram => Boolean): Histogram

    /** Adds a custom predicate over histogram point data with a clue shown in mismatches. */
    def where(clue: String)(f: PointData.Histogram => Boolean): Histogram

    /** Requires the point to contain exactly the given number of exemplars. */
    def exemplarCount(count: Int): Histogram

    /** Requires the point to contain exemplars matching all given expectations. */
    def containsExemplars(first: ExemplarExpectation[Double], rest: ExemplarExpectation[Double]*): Histogram

    /** Requires the point attributes to satisfy the given expectation. */
    def attributes(expectation: AttributesExpectation): Histogram

    /** Requires the point attributes to match exactly. */
    def attributesExact(attributes: Attributes): Histogram

    /** Requires the point attributes to match exactly. */
    def attributesExact(attributes: Attribute[_]*): Histogram

    /** Requires the point attributes to be empty. */
    def attributesEmpty: Histogram

    /** Requires the point attributes to contain the given subset. */
    def attributesSubset(attributes: Attributes): Histogram

    /** Requires the point attributes to contain the given subset. */
    def attributesSubset(attributes: Attribute[_]*): Histogram

    /** Attaches a human-readable clue to this expectation. */
    def clue(text: String): Histogram
  }

  /** Creates an expectation for a numeric point with the given value. */
  def numeric[A: MeasurementValue: NumberComparison](value: A): Numeric[A] =
    NumericImpl(
      expectedValue = Some(value),
      valueType = MeasurementValue[A],
      numberComparison = NumberComparison[A]
    )

  /** Creates an expectation for a histogram point. */
  def histogram(implicit cmp: NumberComparison[Double]): Histogram =
    HistogramImpl(doubleComparison = cmp)

  /** Creates an expectation for a histogram point with exact sum, count, boundaries, and counts expectations. */
  def histogram(
      sum: Double,
      count: Long,
      boundaries: BucketBoundaries,
      counts: List[Long]
  )(implicit cmp: NumberComparison[Double]): Histogram =
    HistogramImpl(
      doubleComparison = cmp,
      expectedSum = Some(sum),
      expectedCount = Some(count),
      expectedMin = None,
      expectedMax = None,
      expectedBoundaries = Some(boundaries),
      expectedCounts = Some(counts)
    )

  private final case class NumericImpl[A](
      expectedValue: Option[A] = None,
      valueType: MeasurementValue[A],
      numberComparison: NumberComparison[A],
      attributeExpectation: Option[AttributesExpectation] = None,
      clue: Option[String] = None,
      predicates: List[(PointData.NumberPoint.Aux[A] => Boolean, Option[String])] = Nil,
      expectedExemplarCount: Option[Int] = None,
      exemplarExpectations: List[ExemplarExpectation[A]] = Nil
  ) extends Numeric[A] {
    def value(value: A): Numeric[A] =
      copy(expectedValue = Some(value))

    def attributes(expectation: AttributesExpectation): Numeric[A] =
      copy(attributeExpectation = Some(expectation))

    def attributesExact(attributes: Attributes): Numeric[A] =
      copy(attributeExpectation = Some(AttributesExpectation.exact(attributes)))

    def attributesExact(attributes: Attribute[_]*): Numeric[A] =
      attributesExact(Attributes(attributes *))

    def attributesEmpty: Numeric[A] =
      attributesExact(Attributes.empty)

    def attributesSubset(attributes: Attributes): Numeric[A] =
      copy(attributeExpectation = Some(AttributesExpectation.subset(attributes)))

    def attributesSubset(attributes: Attribute[_]*): Numeric[A] =
      attributesSubset(Attributes(attributes *))

    def clue(text: String): Numeric[A] =
      copy(clue = Some(text))

    def where(f: PointData.NumberPoint.Aux[A] => Boolean): Numeric[A] =
      copy(predicates = predicates :+ (f -> None))

    def where(clue: String)(f: PointData.NumberPoint.Aux[A] => Boolean): Numeric[A] =
      copy(predicates = predicates :+ (f -> Some(clue)))

    def exemplarCount(count: Int): Numeric[A] =
      copy(expectedExemplarCount = Some(count))

    def containsExemplars(first: ExemplarExpectation[A], rest: ExemplarExpectation[A]*): Numeric[A] =
      copy(exemplarExpectations = exemplarExpectations ++ (first +: rest).toList)

    def check(point: PointData): Either[NonEmptyList[Mismatch], Unit] =
      toNumericPoint(valueType, point) match {
        case Right(numericPoint) =>
          ExpectationChecks.combine(
            expectedValue.fold(ExpectationChecks.success[Mismatch]) { expected =>
              if (numberComparison.equal(expected, numericPoint.value)) ExpectationChecks.success
              else {
                val expectedValue = numberComparison.render(expected)
                val actualValue = numberComparison.render(numericPoint.value)
                ExpectationChecks.mismatch(Mismatch.valueMismatch(expectedValue, actualValue))
              }
            },
            attributeExpectation.fold(ExpectationChecks.success[Mismatch]) { expected =>
              ExpectationChecks.nested(expected.check(numericPoint.attributes))(Mismatch.attributesMismatch)
            },
            expectedExemplarCount.fold(ExpectationChecks.success[Mismatch]) { expected =>
              val actual = numericPoint.exemplars.length
              if (expected == actual) ExpectationChecks.success
              else ExpectationChecks.mismatch(Mismatch.exemplarCountMismatch(expected, actual))
            },
            checkExemplarExpectations(exemplarExpectations, numericPoint.exemplars.toList, valueType),
            ExpectationChecks.combine(predicates.map { case (predicate, clue) =>
              if (predicate(numericPoint)) ExpectationChecks.success
              else
                ExpectationChecks.mismatch(Mismatch.predicateMismatch(clue.getOrElse("point predicate returned false")))
            })
          )
        case Left(mismatch) =>
          ExpectationChecks.mismatch(mismatch)
      }
  }

  private final case class HistogramImpl(
      doubleComparison: NumberComparison[Double],
      expectedStats: Option[Option[PointData.Histogram.Stats]] = None,
      expectedSum: Option[Double] = None,
      expectedCount: Option[Long] = None,
      expectedMin: Option[Option[Double]] = None,
      expectedMax: Option[Option[Double]] = None,
      expectedBoundaries: Option[BucketBoundaries] = None,
      expectedCounts: Option[List[Long]] = None,
      attributeExpectation: Option[AttributesExpectation] = None,
      clue: Option[String] = None,
      predicates: List[(PointData.Histogram => Boolean, Option[String])] = Nil,
      expectedExemplarCount: Option[Int] = None,
      exemplarExpectations: List[ExemplarExpectation[Double]] = Nil
  ) extends Histogram {
    def stats(stats: PointData.Histogram.Stats): Histogram = copy(expectedStats = Some(Some(stats)))
    def withoutStats: Histogram = copy(expectedStats = Some(None))
    def count(count: Long): Histogram = copy(expectedCount = Some(count))
    def sum(sum: Double): Histogram = copy(expectedSum = Some(sum))
    def min(min: Double): Histogram = copy(expectedMin = Some(Some(min)))
    def withoutMin: Histogram = copy(expectedMin = Some(None))
    def max(max: Double): Histogram = copy(expectedMax = Some(Some(max)))
    def withoutMax: Histogram = copy(expectedMax = Some(None))
    def boundaries(boundaries: BucketBoundaries): Histogram = copy(expectedBoundaries = Some(boundaries))
    def counts(counts: List[Long]): Histogram = copy(expectedCounts = Some(counts))
    def counts(counts: Long*): Histogram = this.counts(counts.toList)
    def attributes(expectation: AttributesExpectation): Histogram = copy(attributeExpectation = Some(expectation))
    def attributesExact(attributes: Attributes): Histogram =
      copy(attributeExpectation = Some(AttributesExpectation.exact(attributes)))
    def attributesExact(attributes: Attribute[_]*): Histogram = attributesExact(Attributes(attributes *))
    def attributesEmpty: Histogram = attributesExact(Attributes.empty)
    def attributesSubset(attributes: Attributes): Histogram =
      copy(attributeExpectation = Some(AttributesExpectation.subset(attributes)))
    def attributesSubset(attributes: Attribute[_]*): Histogram = attributesSubset(Attributes(attributes *))
    def clue(text: String): Histogram = copy(clue = Some(text))
    def where(f: PointData.Histogram => Boolean): Histogram = copy(predicates = predicates :+ (f -> None))
    def where(clue: String)(f: PointData.Histogram => Boolean): Histogram =
      copy(predicates = predicates :+ (f -> Some(clue)))
    def exemplarCount(count: Int): Histogram = copy(expectedExemplarCount = Some(count))
    def containsExemplars(first: ExemplarExpectation[Double], rest: ExemplarExpectation[Double]*): Histogram =
      copy(exemplarExpectations = exemplarExpectations ++ (first +: rest).toList)

    def check(point: PointData): Either[NonEmptyList[Mismatch], Unit] =
      point match {
        case histogram: PointData.Histogram =>
          val actualSum = histogram.stats.fold(0.0)(_.sum)
          val actualCount = histogram.stats.fold(0L)(_.count)
          val actualMin = histogram.stats.flatMap(_.min)
          val actualMax = histogram.stats.flatMap(_.max)
          ExpectationChecks.combine(
            expectedStats.fold(ExpectationChecks.success[Mismatch]) { expected =>
              if (expected == histogram.stats) ExpectationChecks.success
              else ExpectationChecks.mismatch(Mismatch.statsMismatch(expected, histogram.stats))
            },
            expectedSum.fold(ExpectationChecks.success[Mismatch]) { expected =>
              if (doubleComparison.equal(expected, actualSum)) ExpectationChecks.success
              else ExpectationChecks.mismatch(Mismatch.sumMismatch(expected, actualSum))
            },
            expectedCount.fold(ExpectationChecks.success[Mismatch]) { expected =>
              if (expected == actualCount) ExpectationChecks.success
              else ExpectationChecks.mismatch(Mismatch.countMismatch(expected, actualCount))
            },
            expectedMin.fold(ExpectationChecks.success[Mismatch]) { expected =>
              if (expected == actualMin) ExpectationChecks.success
              else ExpectationChecks.mismatch(Mismatch.minMismatch(expected, actualMin))
            },
            expectedMax.fold(ExpectationChecks.success[Mismatch]) { expected =>
              if (expected == actualMax) ExpectationChecks.success
              else ExpectationChecks.mismatch(Mismatch.maxMismatch(expected, actualMax))
            },
            expectedBoundaries.fold(ExpectationChecks.success[Mismatch]) { expected =>
              if (expected == histogram.boundaries) ExpectationChecks.success
              else ExpectationChecks.mismatch(Mismatch.boundariesMismatch(expected, histogram.boundaries))
            },
            expectedCounts.fold(ExpectationChecks.success[Mismatch]) { expected =>
              val actual = histogram.counts.toList
              if (expected == actual) ExpectationChecks.success
              else ExpectationChecks.mismatch(Mismatch.countsMismatch(expected, actual))
            },
            attributeExpectation.fold(ExpectationChecks.success[Mismatch]) { expected =>
              ExpectationChecks.nested(expected.check(histogram.attributes))(Mismatch.attributesMismatch)
            },
            expectedExemplarCount.fold(ExpectationChecks.success[Mismatch]) { expected =>
              val actual = histogram.exemplars.length
              if (expected == actual) ExpectationChecks.success
              else ExpectationChecks.mismatch(Mismatch.exemplarCountMismatch(expected, actual))
            },
            checkExemplarExpectations(exemplarExpectations, histogram.exemplars.toList, MeasurementValue[Double]),
            ExpectationChecks.combine(predicates.map { case (predicate, clue) =>
              if (predicate(histogram)) ExpectationChecks.success
              else
                ExpectationChecks.mismatch(Mismatch.predicateMismatch(clue.getOrElse("point predicate returned false")))
            })
          )
        case other =>
          ExpectationChecks.mismatch(Mismatch.typeMismatch("Histogram", pointTypeName(other)))
      }
  }

  private[metrics] def toNumericPoint[A](
      valueType: MeasurementValue[A],
      point: PointData
  ): Either[Mismatch, PointData.NumberPoint.Aux[A]] =
    valueType match {
      case _: LongMeasurementValue[_] =>
        point match {
          case long: PointData.LongNumber =>
            Right(long.asInstanceOf[PointData.NumberPoint.Aux[A]])
          case other =>
            Left(Mismatch.typeMismatch("LongNumber", pointTypeName(other)))
        }
      case _: DoubleMeasurementValue[_] =>
        point match {
          case double: PointData.DoubleNumber =>
            Right(double.asInstanceOf[PointData.NumberPoint.Aux[A]])
          case other =>
            Left(Mismatch.typeMismatch("DoubleNumber", pointTypeName(other)))
        }
    }

  private def pointTypeName(point: PointData): String =
    point match {
      case _: PointData.LongNumber   => "LongNumber"
      case _: PointData.DoubleNumber => "DoubleNumber"
      case _: PointData.Histogram    => "Histogram"
    }

  private def checkExemplarExpectations[A](
      expectations: List[ExemplarExpectation[A]],
      exemplars: List[org.typelevel.otel4s.sdk.metrics.data.ExemplarData],
      valueType: MeasurementValue[A]
  ): Either[NonEmptyList[Mismatch], Unit] =
    ExpectationChecks.combine(expectations.map { expectation =>
      if (exemplars.exists(exemplar => expectation.matches(exemplar))) {
        ExpectationChecks.success
      } else {
        val mismatches = exemplars
          .flatMap(exemplar => expectation.check(exemplar).left.toOption)
          .sortBy(_.length)
          .headOption
          .getOrElse(
            NonEmptyList.one(
              ExemplarExpectation.Mismatch.typeMismatch(
                valueType match {
                  case _: LongMeasurementValue[_]   => "LongExemplar"
                  case _: DoubleMeasurementValue[_] => "DoubleExemplar"
                },
                "<missing>"
              )
            )
          )
        ExpectationChecks.mismatch(Mismatch.exemplarMismatch(mismatches, expectation.clue))
      }
    })
}
