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
import org.typelevel.otel4s.metrics.MeasurementValue
import org.typelevel.otel4s.metrics.MeasurementValue.DoubleMeasurementValue
import org.typelevel.otel4s.metrics.MeasurementValue.LongMeasurementValue
import org.typelevel.otel4s.sdk.metrics.data.AggregationTemporality
import org.typelevel.otel4s.sdk.metrics.data.MetricData
import org.typelevel.otel4s.sdk.metrics.data.MetricPoints
import org.typelevel.otel4s.sdk.metrics.data.PointData
import org.typelevel.otel4s.sdk.testkit.ExpectationChecks
import org.typelevel.otel4s.sdk.testkit.InstrumentationScopeExpectation
import org.typelevel.otel4s.sdk.testkit.TelemetryResourceExpectation

/** A partial expectation for a single SDK [[org.typelevel.otel4s.sdk.metrics.data.MetricData]].
  *
  * `MetricExpectation` is intended for tests where asserting against the full `MetricData` shape would be too verbose.
  * Unspecified properties are ignored. Point matching is expressed through collection-level [[PointSetExpectation]]
  * values, which allows multiple point constraints to accumulate on the same metric expectation.
  */
sealed trait MetricExpectation {
  private[metrics] def expectedName: Option[String]

  /** An optional human-readable clue shown in mismatch messages. */
  def clue: Option[String]

  /** Requires the metric description to match exactly. */
  def description(description: String): MetricExpectation

  /** Requires the metric unit to match exactly. */
  def unit(unit: String): MetricExpectation

  /** Requires the instrumentation scope name to match exactly. */
  def scopeName(name: String): MetricExpectation

  /** Requires the instrumentation scope to match the given expectation. */
  def scope(scope: InstrumentationScopeExpectation): MetricExpectation

  /** Requires the telemetry resource to match the given expectation. */
  def resource(resource: TelemetryResourceExpectation): MetricExpectation

  /** Attaches a human-readable clue to this expectation. */
  def clue(text: String): MetricExpectation

  /** Adds a custom predicate over the metric data. */
  def where(f: MetricData => Boolean): MetricExpectation

  /** Adds a custom predicate over the metric data with a clue shown in mismatches. */
  def where(clue: String)(f: MetricData => Boolean): MetricExpectation

  /** Checks the given metric and returns structured mismatches when the expectation does not match. */
  def check(metric: MetricData): Either[NonEmptyList[MetricExpectation.Mismatch], Unit]

  /** Returns `true` if this expectation matches the given metric. */
  final def matches(metric: MetricData): Boolean =
    check(metric).isRight
}

object MetricExpectation {

  /** A structured reason explaining why a [[MetricExpectation]] did not match a metric. */
  sealed trait Mismatch extends Product with Serializable {

    /** A human-readable description of the mismatch. */
    def message: String
  }

  object Mismatch {

    /** Indicates that the metric name did not match. */
    sealed trait NameMismatch extends Mismatch {

      /** The expected metric name. */
      def expected: String

      /** The actual metric name. */
      def actual: String
    }

    /** Indicates that the metric description did not match. */
    sealed trait DescriptionMismatch extends Mismatch {

      /** The expected metric description. */
      def expected: String

      /** The actual metric description, or absence of one. */
      def actual: Option[String]
    }

    /** Indicates that the metric unit did not match. */
    sealed trait UnitMismatch extends Mismatch {

      /** The expected metric unit. */
      def expected: String

      /** The actual metric unit, or absence of one. */
      def actual: Option[String]
    }

    /** Indicates that the metric data type did not match. */
    sealed trait TypeMismatch extends Mismatch {

      /** The expected metric type name. */
      def expected: String

      /** The actual metric type name. */
      def actual: String
    }

    /** Indicates that the aggregation temporality did not match. */
    sealed trait AggregationTemporalityMismatch extends Mismatch {

      /** The expected aggregation temporality. */
      def expected: AggregationTemporality

      /** The actual aggregation temporality, if present for the metric type. */
      def actual: Option[AggregationTemporality]
    }

    /** Indicates that the monotonic flag did not match. */
    sealed trait MonotonicMismatch extends Mismatch {

      /** The expected monotonic flag. */
      def expected: Boolean

      /** The actual monotonic flag, if present for the metric type. */
      def actual: Option[Boolean]
    }

    /** Indicates that the instrumentation scope did not satisfy the nested expectation. */
    sealed trait ScopeMismatch extends Mismatch {

      /** Nested scope mismatches. */
      def mismatches: NonEmptyList[InstrumentationScopeExpectation.Mismatch]
    }

    /** Indicates that the telemetry resource did not satisfy the nested expectation. */
    sealed trait ResourceMismatch extends Mismatch {

      /** Nested resource mismatches. */
      def mismatches: NonEmptyList[TelemetryResourceExpectation.Mismatch]
    }

    /** Indicates that a custom metric predicate returned `false`. */
    sealed trait PredicateMismatch extends Mismatch {

      /** An optional clue attached to the predicate. */
      def clue: Option[String]
    }

    /** Indicates that point-set constraints did not match. */
    sealed trait PointsMismatch extends Mismatch {

      /** Point-set mismatches. */
      def mismatches: NonEmptyList[PointSetExpectation.Mismatch]

      /** An optional clue attached to the point-set expectation. */
      def clue: Option[String]
    }

    /** Creates a mismatch for an unexpected metric name. */
    def nameMismatch(expected: String, actual: String): NameMismatch =
      NameMismatchImpl(expected, actual)

    /** Creates a mismatch for an unexpected metric description. */
    def descriptionMismatch(expected: String, actual: Option[String]): DescriptionMismatch =
      DescriptionMismatchImpl(expected, actual)

    /** Creates a mismatch for an unexpected metric unit. */
    def unitMismatch(expected: String, actual: Option[String]): UnitMismatch =
      UnitMismatchImpl(expected, actual)

    /** Creates a mismatch for an unexpected metric type. */
    def typeMismatch(expected: String, actual: String): TypeMismatch =
      TypeMismatchImpl(expected, actual)

    /** Creates a mismatch for an unexpected aggregation temporality. */
    def aggregationTemporalityMismatch(
        expected: AggregationTemporality,
        actual: Option[AggregationTemporality]
    ): AggregationTemporalityMismatch =
      AggregationTemporalityMismatchImpl(expected, actual)

    /** Creates a mismatch for an unexpected monotonic flag. */
    def monotonicMismatch(expected: Boolean, actual: Option[Boolean]): MonotonicMismatch =
      MonotonicMismatchImpl(expected, actual)

    /** Creates a mismatch for instrumentation scope data that failed validation. */
    def scopeMismatch(mismatches: NonEmptyList[InstrumentationScopeExpectation.Mismatch]): ScopeMismatch =
      ScopeMismatchImpl(mismatches)

    /** Creates a mismatch for telemetry resource data that failed validation. */
    def resourceMismatch(mismatches: NonEmptyList[TelemetryResourceExpectation.Mismatch]): ResourceMismatch =
      ResourceMismatchImpl(mismatches)

    /** Creates a mismatch for a failed custom predicate. */
    def predicateMismatch(clue: Option[String]): PredicateMismatch =
      PredicateMismatchImpl(clue)

    /** Creates a mismatch for failed point-set expectations. */
    def pointsMismatch(mismatches: NonEmptyList[PointSetExpectation.Mismatch], clue: Option[String]): PointsMismatch =
      PointsMismatchImpl(mismatches, clue)

    private final case class NameMismatchImpl(expected: String, actual: String) extends NameMismatch {
      def message: String = s"name mismatch: expected '$expected', got '$actual'"
    }

    private final case class DescriptionMismatchImpl(expected: String, actual: Option[String])
        extends DescriptionMismatch {
      def message: String =
        s"description mismatch: expected '$expected', got ${actual.fold("<missing>")(v => s"'$v'")}"
    }

    private final case class UnitMismatchImpl(expected: String, actual: Option[String]) extends UnitMismatch {
      def message: String =
        s"unit mismatch: expected '$expected', got ${actual.fold("<missing>")(v => s"'$v'")}"
    }

    private final case class TypeMismatchImpl(expected: String, actual: String) extends TypeMismatch {
      def message: String = s"type mismatch: expected '$expected', got '$actual'"
    }

    private final case class AggregationTemporalityMismatchImpl(
        expected: AggregationTemporality,
        actual: Option[AggregationTemporality]
    ) extends AggregationTemporalityMismatch {
      def message: String =
        s"aggregation temporality mismatch: expected '$expected', got ${actual.fold("<missing>")(v => s"'$v'")}"
    }

    private final case class MonotonicMismatchImpl(expected: Boolean, actual: Option[Boolean])
        extends MonotonicMismatch {
      def message: String =
        s"monotonic mismatch: expected '$expected', got ${actual.fold("<missing>")(v => s"'$v'")}"
    }

    private final case class ScopeMismatchImpl(mismatches: NonEmptyList[InstrumentationScopeExpectation.Mismatch])
        extends ScopeMismatch {
      def message: String =
        s"scope mismatch: ${mismatches.toList.map(_.message).mkString(", ")}"
    }

    private final case class ResourceMismatchImpl(mismatches: NonEmptyList[TelemetryResourceExpectation.Mismatch])
        extends ResourceMismatch {
      def message: String =
        s"resource mismatch: ${mismatches.toList.map(_.message).mkString(", ")}"
    }

    private final case class PredicateMismatchImpl(clue: Option[String]) extends PredicateMismatch {
      def message: String =
        s"predicate mismatch${clue.fold("")(value => s": $value")}"
    }

    private final case class PointsMismatchImpl(
        mismatches: NonEmptyList[PointSetExpectation.Mismatch],
        clue: Option[String]
    ) extends PointsMismatch {
      def message: String = {
        val rendered = mismatches.toList.map(_.message).mkString(", ")
        val clueSuffix = clue.fold("")(value => s" [$value]")
        s"points mismatch$clueSuffix: $rendered"
      }
    }
  }

  /** A typed expectation for numeric metrics. */
  sealed trait Numeric[A] extends MetricExpectation {

    /** The `MeasurementValue` used to distinguish long and double metrics at runtime. */
    def valueType: MeasurementValue[A]

    /** Requires at least one point with the given value. */
    def value(value: A, attributes: Attribute[_]*): Numeric[A]

    /** Requires at least one point with the given value and exact attributes. */
    def value(value: A, attributes: Attributes): Numeric[A]

    /** Adds a collection-level expectation over the metric points. */
    def points(expectation: PointSetExpectation[PointData.NumberPoint.Aux[A]]): Numeric[A]

    /** Requires the metric to contain all given point expectations. */
    def containsPoints(first: PointExpectation.Numeric[A], rest: PointExpectation.Numeric[A]*): Numeric[A]

    /** Requires the metric points to match the given point expectations exactly. */
    def exactlyPoints(first: PointExpectation.Numeric[A], rest: PointExpectation.Numeric[A]*): Numeric[A]

    /** Requires the metric to have exactly the given number of points. */
    def pointCount(count: Int): Numeric[A]

    /** Requires no point to match the given point expectation. */
    def withoutPointsMatching(point: PointExpectation.Numeric[A]): Numeric[A]

    /** Requires the metric aggregation temporality to match exactly. */
    def temporality(temporality: AggregationTemporality): Numeric[A]

    /** Requires the metric monotonic flag to match exactly. */
    def monotonic(monotonic: Boolean): Numeric[A]

    /** Adds a custom predicate over the full numeric point collection. */
    def pointsWhere(f: List[PointData.NumberPoint.Aux[A]] => Boolean): Numeric[A]

    /** Adds a custom predicate over the full numeric point collection with a clue shown in mismatches. */
    def pointsWhere(clue: String)(f: List[PointData.NumberPoint.Aux[A]] => Boolean): Numeric[A]

    /** Requires the metric description to match exactly. */
    def description(description: String): Numeric[A]

    /** Requires the metric unit to match exactly. */
    def unit(unit: String): Numeric[A]

    /** Requires the instrumentation scope name to match exactly. */
    def scopeName(name: String): Numeric[A]

    /** Requires the instrumentation scope to match the given expectation. */
    def scope(scope: InstrumentationScopeExpectation): Numeric[A]

    /** Requires the telemetry resource to match the given expectation. */
    def resource(resource: TelemetryResourceExpectation): Numeric[A]

    /** Attaches a human-readable clue to this expectation. */
    def clue(text: String): Numeric[A]

    /** Adds a custom predicate over the metric data. */
    def where(f: MetricData => Boolean): Numeric[A]

    /** Adds a custom predicate over the metric data with a clue shown in mismatches. */
    def where(clue: String)(f: MetricData => Boolean): Numeric[A]
  }

  /** A typed expectation for histogram metrics. */
  sealed trait Histogram extends MetricExpectation {

    /** Adds a collection-level expectation over the metric points. */
    def points(expectation: PointSetExpectation[PointData.Histogram]): Histogram

    /** Requires the metric to contain all given point expectations. */
    def containsPoints(first: PointExpectation.Histogram, rest: PointExpectation.Histogram*): Histogram

    /** Requires the metric points to match the given point expectations exactly. */
    def exactlyPoints(first: PointExpectation.Histogram, rest: PointExpectation.Histogram*): Histogram

    /** Requires the metric to have exactly the given number of points. */
    def pointCount(count: Int): Histogram

    /** Requires no point to match the given point expectation. */
    def withoutPointsMatching(point: PointExpectation.Histogram): Histogram

    /** Requires the metric aggregation temporality to match exactly. */
    def temporality(temporality: AggregationTemporality): Histogram

    /** Adds a custom predicate over the full histogram point collection. */
    def pointsWhere(f: List[PointData.Histogram] => Boolean): Histogram

    /** Adds a custom predicate over the full histogram point collection with a clue shown in mismatches. */
    def pointsWhere(clue: String)(f: List[PointData.Histogram] => Boolean): Histogram

    /** Requires the metric description to match exactly. */
    def description(description: String): Histogram

    /** Requires the metric unit to match exactly. */
    def unit(unit: String): Histogram

    /** Requires the instrumentation scope name to match exactly. */
    def scopeName(name: String): Histogram

    /** Requires the instrumentation scope to match the given expectation. */
    def scope(scope: InstrumentationScopeExpectation): Histogram

    /** Requires the telemetry resource to match the given expectation. */
    def resource(resource: TelemetryResourceExpectation): Histogram

    /** Attaches a human-readable clue to this expectation. */
    def clue(text: String): Histogram

    /** Adds a custom predicate over the metric data. */
    def where(f: MetricData => Boolean): Histogram

    /** Adds a custom predicate over the metric data with a clue shown in mismatches. */
    def where(clue: String)(f: MetricData => Boolean): Histogram
  }

  /** Creates an expectation that matches any metric with the given name. */
  def name(name: String): MetricExpectation =
    NameImpl(name = Some(name))

  /** Creates a typed expectation for a gauge metric. */
  def gauge[A: MeasurementValue: NumberComparison](name: String): Numeric[A] =
    NumericImpl(
      name = Some(name),
      kind = NumericKind.Gauge,
      valueType = MeasurementValue[A],
      numberComparison = NumberComparison[A]
    )

  /** Creates a typed expectation for a sum metric. */
  def sum[A: MeasurementValue: NumberComparison](name: String): Numeric[A] =
    NumericImpl(
      name = Some(name),
      kind = NumericKind.Sum,
      valueType = MeasurementValue[A],
      numberComparison = NumberComparison[A]
    )

  /** Creates an expectation for a histogram metric. */
  def histogram(name: String): Histogram =
    HistogramImpl(name = Some(name))

  private sealed trait NumericKind {
    def matches[A](data: MetricData, valueType: MeasurementValue[A]): Boolean
    def expectedTypeName[A](valueType: MeasurementValue[A]): String
  }

  private object NumericKind {
    case object Gauge extends NumericKind {
      def matches[A](data: MetricData, valueType: MeasurementValue[A]): Boolean =
        (data.data, valueType) match {
          case (_: MetricPoints.Gauge, _: LongMeasurementValue[_]) =>
            data.data.points.forall(_.isInstanceOf[PointData.LongNumber])
          case (_: MetricPoints.Gauge, _: DoubleMeasurementValue[_]) =>
            data.data.points.forall(_.isInstanceOf[PointData.DoubleNumber])
          case _ =>
            false
        }

      def expectedTypeName[A](valueType: MeasurementValue[A]): String =
        valueType match {
          case _: LongMeasurementValue[_]   => "Gauge[Long]"
          case _: DoubleMeasurementValue[_] => "Gauge[Double]"
        }
    }

    case object Sum extends NumericKind {
      def matches[A](data: MetricData, valueType: MeasurementValue[A]): Boolean =
        (data.data, valueType) match {
          case (_: MetricPoints.Sum, _: LongMeasurementValue[_]) =>
            data.data.points.forall(_.isInstanceOf[PointData.LongNumber])
          case (_: MetricPoints.Sum, _: DoubleMeasurementValue[_]) =>
            data.data.points.forall(_.isInstanceOf[PointData.DoubleNumber])
          case _ =>
            false
        }

      def expectedTypeName[A](valueType: MeasurementValue[A]): String =
        valueType match {
          case _: LongMeasurementValue[_]   => "Sum[Long]"
          case _: DoubleMeasurementValue[_] => "Sum[Double]"
        }
    }
  }

  private final case class NameImpl(
      name: Option[String] = None,
      description: Option[String] = None,
      unit: Option[String] = None,
      scope: Option[InstrumentationScopeExpectation] = None,
      resource: Option[TelemetryResourceExpectation] = None,
      clue: Option[String] = None,
      predicates: List[(MetricData => Boolean, Option[String])] = Nil
  ) extends MetricExpectation {
    def expectedName: Option[String] = name
    def description(description: String): MetricExpectation = copy(description = Some(description))
    def unit(unit: String): MetricExpectation = copy(unit = Some(unit))
    def scopeName(name: String): MetricExpectation =
      copy(scope = Some(scope.fold(InstrumentationScopeExpectation.name(name))(_.name(name))))
    def scope(scope: InstrumentationScopeExpectation): MetricExpectation = copy(scope = Some(scope))
    def resource(resource: TelemetryResourceExpectation): MetricExpectation = copy(resource = Some(resource))
    def clue(text: String): MetricExpectation = copy(clue = Some(text))
    def where(f: MetricData => Boolean): MetricExpectation = copy(predicates = predicates :+ (f -> None))
    def where(clue: String)(f: MetricData => Boolean): MetricExpectation =
      copy(predicates = predicates :+ (f -> Some(clue)))
    def check(metric: MetricData): Either[NonEmptyList[Mismatch], Unit] =
      ExpectationChecks.combine(
        checkCommon(metric, name, description, unit, scope, resource),
        checkPredicates(metric, predicates)
      )
  }

  private final case class NumericImpl[A](
      name: Option[String],
      kind: NumericKind,
      valueType: MeasurementValue[A],
      numberComparison: NumberComparison[A],
      description: Option[String] = None,
      unit: Option[String] = None,
      scope: Option[InstrumentationScopeExpectation] = None,
      resource: Option[TelemetryResourceExpectation] = None,
      clue: Option[String] = None,
      predicates: List[(MetricData => Boolean, Option[String])] = Nil,
      pointConstraints: List[PointSetExpectation[PointData.NumberPoint.Aux[A]]] = Nil,
      expectedTemporality: Option[AggregationTemporality] = None,
      expectedMonotonic: Option[Boolean] = None
  ) extends Numeric[A] {
    def expectedName: Option[String] = name

    def value(value: A, attributes: Attribute[_]*): Numeric[A] =
      if (attributes.isEmpty)
        points(PointSetExpectation.exists(PointExpectation.numeric(value)(valueType, numberComparison)))
      else this.value(value, Attributes(attributes *))

    def value(value: A, attributes: Attributes): Numeric[A] =
      points(
        PointSetExpectation.exists(
          PointExpectation.numeric(value)(valueType, numberComparison).attributesExact(attributes)
        )
      )

    def points(expectation: PointSetExpectation[PointData.NumberPoint.Aux[A]]): Numeric[A] =
      copy(pointConstraints = pointConstraints :+ expectation)

    def containsPoints(first: PointExpectation.Numeric[A], rest: PointExpectation.Numeric[A]*): Numeric[A] =
      points(PointSetExpectation.contains(first, rest *))

    def exactlyPoints(first: PointExpectation.Numeric[A], rest: PointExpectation.Numeric[A]*): Numeric[A] =
      points(PointSetExpectation.exactly(first, rest *))

    def pointCount(count: Int): Numeric[A] =
      points(PointSetExpectation.count(count))

    def withoutPointsMatching(point: PointExpectation.Numeric[A]): Numeric[A] =
      points(PointSetExpectation.none(point))

    def temporality(temporality: AggregationTemporality): Numeric[A] =
      copy(expectedTemporality = Some(temporality))

    def monotonic(monotonic: Boolean): Numeric[A] =
      copy(expectedMonotonic = Some(monotonic))

    def pointsWhere(f: List[PointData.NumberPoint.Aux[A]] => Boolean): Numeric[A] =
      points(PointSetExpectation.predicate(f))

    def pointsWhere(clue: String)(f: List[PointData.NumberPoint.Aux[A]] => Boolean): Numeric[A] =
      points(PointSetExpectation.predicate(clue)(f))

    def description(description: String): Numeric[A] = copy(description = Some(description))
    def unit(unit: String): Numeric[A] = copy(unit = Some(unit))
    def scopeName(name: String): Numeric[A] =
      copy(scope = Some(scope.fold(InstrumentationScopeExpectation.name(name))(_.name(name))))
    def scope(scope: InstrumentationScopeExpectation): Numeric[A] = copy(scope = Some(scope))
    def resource(resource: TelemetryResourceExpectation): Numeric[A] = copy(resource = Some(resource))
    def clue(text: String): Numeric[A] = copy(clue = Some(text))
    def where(f: MetricData => Boolean): Numeric[A] = copy(predicates = predicates :+ (f -> None))
    def where(clue: String)(f: MetricData => Boolean): Numeric[A] = copy(predicates = predicates :+ (f -> Some(clue)))

    def check(metric: MetricData): Either[NonEmptyList[Mismatch], Unit] = {
      val typeResult = checkNumericType(metric, kind, valueType)
      val aggregationResult =
        if (typeResult.isRight) checkNumericAggregation(metric, expectedTemporality, expectedMonotonic)
        else ExpectationChecks.success
      val predicatesResult = if (typeResult.isRight) checkPredicates(metric, predicates) else ExpectationChecks.success
      val pointsResult =
        if (typeResult.isRight) checkPointConstraints(pointConstraints, numericPoints(metric, valueType))
        else ExpectationChecks.success
      ExpectationChecks.combine(
        typeResult,
        checkCommon(metric, name, description, unit, scope, resource),
        aggregationResult,
        predicatesResult,
        pointsResult
      )
    }
  }

  private final case class HistogramImpl(
      name: Option[String],
      description: Option[String] = None,
      unit: Option[String] = None,
      scope: Option[InstrumentationScopeExpectation] = None,
      resource: Option[TelemetryResourceExpectation] = None,
      clue: Option[String] = None,
      predicates: List[(MetricData => Boolean, Option[String])] = Nil,
      pointConstraints: List[PointSetExpectation[PointData.Histogram]] = Nil,
      expectedTemporality: Option[AggregationTemporality] = None
  ) extends Histogram {
    def expectedName: Option[String] = name
    def points(expectation: PointSetExpectation[PointData.Histogram]): Histogram =
      copy(pointConstraints = pointConstraints :+ expectation)
    def containsPoints(first: PointExpectation.Histogram, rest: PointExpectation.Histogram*): Histogram =
      points(PointSetExpectation.contains(first, rest *))
    def exactlyPoints(first: PointExpectation.Histogram, rest: PointExpectation.Histogram*): Histogram =
      points(PointSetExpectation.exactly(first, rest *))
    def pointCount(count: Int): Histogram = points(PointSetExpectation.count(count))
    def withoutPointsMatching(point: PointExpectation.Histogram): Histogram = points(PointSetExpectation.none(point))
    def temporality(temporality: AggregationTemporality): Histogram = copy(expectedTemporality = Some(temporality))
    def pointsWhere(f: List[PointData.Histogram] => Boolean): Histogram = points(PointSetExpectation.predicate(f))
    def pointsWhere(clue: String)(f: List[PointData.Histogram] => Boolean): Histogram = points(
      PointSetExpectation.predicate(clue)(f)
    )
    def description(description: String): Histogram = copy(description = Some(description))
    def unit(unit: String): Histogram = copy(unit = Some(unit))
    def scopeName(name: String): Histogram =
      copy(scope = Some(scope.fold(InstrumentationScopeExpectation.name(name))(_.name(name))))
    def scope(scope: InstrumentationScopeExpectation): Histogram = copy(scope = Some(scope))
    def resource(resource: TelemetryResourceExpectation): Histogram = copy(resource = Some(resource))
    def clue(text: String): Histogram = copy(clue = Some(text))
    def where(f: MetricData => Boolean): Histogram = copy(predicates = predicates :+ (f -> None))
    def where(clue: String)(f: MetricData => Boolean): Histogram = copy(predicates = predicates :+ (f -> Some(clue)))
    def check(metric: MetricData): Either[NonEmptyList[Mismatch], Unit] = {
      val typeResult = checkHistogramType(metric)
      val aggregationResult =
        if (typeResult.isRight) checkHistogramAggregation(metric, expectedTemporality)
        else ExpectationChecks.success
      val predicatesResult =
        if (typeResult.isRight) checkPredicates(metric, predicates)
        else ExpectationChecks.success
      val pointsResult =
        if (typeResult.isRight) checkPointConstraints(pointConstraints, histogramPoints(metric))
        else ExpectationChecks.success
      ExpectationChecks.combine(
        typeResult,
        checkCommon(metric, name, description, unit, scope, resource),
        aggregationResult,
        predicatesResult,
        pointsResult
      )
    }
  }

  private def checkCommon(
      metric: MetricData,
      name: Option[String],
      description: Option[String],
      unit: Option[String],
      scope: Option[InstrumentationScopeExpectation],
      resource: Option[TelemetryResourceExpectation]
  ): Either[NonEmptyList[Mismatch], Unit] =
    ExpectationChecks.combine(
      name.fold(ExpectationChecks.success[Mismatch]) { expected =>
        if (expected == metric.name) ExpectationChecks.success
        else ExpectationChecks.mismatch(Mismatch.nameMismatch(expected, metric.name))
      },
      description.fold(ExpectationChecks.success[Mismatch]) { expected =>
        if (metric.description.contains(expected)) ExpectationChecks.success
        else ExpectationChecks.mismatch(Mismatch.descriptionMismatch(expected, metric.description))
      },
      unit.fold(ExpectationChecks.success[Mismatch]) { expected =>
        if (metric.unit.contains(expected)) ExpectationChecks.success
        else ExpectationChecks.mismatch(Mismatch.unitMismatch(expected, metric.unit))
      },
      scope.fold(ExpectationChecks.success[Mismatch]) { expected =>
        ExpectationChecks.nested(expected.check(metric.instrumentationScope))(Mismatch.scopeMismatch)
      },
      resource.fold(ExpectationChecks.success[Mismatch]) { expected =>
        ExpectationChecks.nested(expected.check(metric.resource))(Mismatch.resourceMismatch)
      }
    )

  private def checkPredicates(
      metric: MetricData,
      predicates: List[(MetricData => Boolean, Option[String])]
  ): Either[NonEmptyList[Mismatch], Unit] =
    ExpectationChecks.combine(predicates.map { case (predicate, clue) =>
      if (predicate(metric)) ExpectationChecks.success[Mismatch]
      else ExpectationChecks.mismatch(Mismatch.predicateMismatch(clue))
    })

  private def checkNumericType[A](
      metric: MetricData,
      kind: NumericKind,
      valueType: MeasurementValue[A]
  ): Either[NonEmptyList[Mismatch], Unit] =
    if (kind.matches(metric, valueType)) ExpectationChecks.success
    else
      ExpectationChecks.mismatch(Mismatch.typeMismatch(kind.expectedTypeName(valueType), actualTypeName(metric.data)))

  private def checkHistogramType(metric: MetricData): Either[NonEmptyList[Mismatch], Unit] =
    metric.data match {
      case _: MetricPoints.Histogram => ExpectationChecks.success
      case _ => ExpectationChecks.mismatch(Mismatch.typeMismatch("Histogram", actualTypeName(metric.data)))
    }

  private def checkNumericAggregation(
      metric: MetricData,
      expectedTemporality: Option[AggregationTemporality],
      expectedMonotonic: Option[Boolean]
  ): Either[NonEmptyList[Mismatch], Unit] =
    metric.data match {
      case sum: MetricPoints.Sum =>
        ExpectationChecks.combine(
          expectedTemporality.fold(ExpectationChecks.success[Mismatch]) { expected =>
            if (expected == sum.aggregationTemporality) ExpectationChecks.success
            else
              ExpectationChecks.mismatch(
                Mismatch.aggregationTemporalityMismatch(expected, Some(sum.aggregationTemporality))
              )
          },
          expectedMonotonic.fold(ExpectationChecks.success[Mismatch]) { expected =>
            if (expected == sum.monotonic) ExpectationChecks.success
            else ExpectationChecks.mismatch(Mismatch.monotonicMismatch(expected, Some(sum.monotonic)))
          }
        )
      case _: MetricPoints.Gauge =>
        ExpectationChecks.combine(
          expectedTemporality.fold(ExpectationChecks.success[Mismatch]) { expected =>
            ExpectationChecks.mismatch(Mismatch.aggregationTemporalityMismatch(expected, None))
          },
          expectedMonotonic.fold(ExpectationChecks.success[Mismatch]) { expected =>
            ExpectationChecks.mismatch(Mismatch.monotonicMismatch(expected, None))
          }
        )
      case _ =>
        ExpectationChecks.success
    }

  private def checkHistogramAggregation(
      metric: MetricData,
      expectedTemporality: Option[AggregationTemporality]
  ): Either[NonEmptyList[Mismatch], Unit] =
    metric.data match {
      case histogram: MetricPoints.Histogram =>
        expectedTemporality.fold(ExpectationChecks.success[Mismatch]) { expected =>
          if (expected == histogram.aggregationTemporality) ExpectationChecks.success
          else
            ExpectationChecks.mismatch(
              Mismatch.aggregationTemporalityMismatch(expected, Some(histogram.aggregationTemporality))
            )
        }
      case _ =>
        ExpectationChecks.success
    }

  private def checkPointConstraints[P](
      expectations: List[PointSetExpectation[P]],
      points: List[P]
  ): Either[NonEmptyList[Mismatch], Unit] =
    ExpectationChecks.combine(expectations.map { expectation =>
      ExpectationChecks.nested(expectation.check(points))(mismatches =>
        Mismatch.pointsMismatch(mismatches, expectation.clue)
      )
    })

  private def numericPoints[A](
      metric: MetricData,
      valueType: MeasurementValue[A]
  ): List[PointData.NumberPoint.Aux[A]] =
    metric.data.points.toVector.toList.map(point => numericPoint(valueType, point))

  private def histogramPoints(metric: MetricData): List[PointData.Histogram] =
    metric.data match {
      case histogram: MetricPoints.Histogram => histogram.points.toVector.toList
      case _                                 => throw new IllegalStateException("unexpected non-histogram metric")
    }

  private def numericPoint[A](
      valueType: MeasurementValue[A],
      point: PointData
  ): PointData.NumberPoint.Aux[A] =
    PointExpectation.toNumericPoint(valueType, point) match {
      case Right(value)   => value
      case Left(mismatch) => throw new IllegalStateException(mismatch.message)
    }

  private def actualTypeName(data: MetricPoints): String =
    data match {
      case gauge: MetricPoints.Gauge =>
        if (gauge.points.forall(_.isInstanceOf[PointData.LongNumber])) "Gauge[Long]"
        else "Gauge[Double]"
      case sum: MetricPoints.Sum =>
        if (sum.points.forall(_.isInstanceOf[PointData.LongNumber])) "Sum[Long]"
        else "Sum[Double]"
      case _: MetricPoints.Histogram =>
        "Histogram"
    }
}
