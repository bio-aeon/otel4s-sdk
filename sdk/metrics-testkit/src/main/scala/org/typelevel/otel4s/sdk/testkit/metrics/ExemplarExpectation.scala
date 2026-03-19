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
import org.typelevel.otel4s.sdk.context.TraceContext
import org.typelevel.otel4s.sdk.metrics.data.ExemplarData
import org.typelevel.otel4s.sdk.testkit.AttributesExpectation
import org.typelevel.otel4s.sdk.testkit.ExpectationChecks

import scala.concurrent.duration.FiniteDuration

/** Describes the expected properties of an exported exemplar. */
sealed trait ExemplarExpectation[A] {

  /** Expects the exemplar to have the given numeric value. */
  def value(value: A): ExemplarExpectation[A]

  /** Expects filtered attributes to satisfy the given expectation. */
  def filteredAttributes(expectation: AttributesExpectation): ExemplarExpectation[A]

  /** Expects filtered attributes to match the given attributes exactly. */
  def filteredAttributesExact(attributes: Attributes): ExemplarExpectation[A]

  /** Expects filtered attributes to match the given attributes exactly. */
  def filteredAttributesExact(attributes: Attribute[_]*): ExemplarExpectation[A]

  /** Expects the exemplar to have no filtered attributes. */
  def filteredAttributesEmpty: ExemplarExpectation[A]

  /** Expects filtered attributes to contain at least the given attributes. */
  def filteredAttributesSubset(attributes: Attributes): ExemplarExpectation[A]

  /** Expects filtered attributes to contain at least the given attributes. */
  def filteredAttributesSubset(attributes: Attribute[_]*): ExemplarExpectation[A]

  /** Expects the exemplar to have the given timestamp. */
  def timestamp(timestamp: FiniteDuration): ExemplarExpectation[A]

  /** Expects the exemplar to have the given trace context. */
  def traceContext(traceContext: TraceContext): ExemplarExpectation[A]

  /** Expects the exemplar to have no trace context. */
  def traceContextAbsent: ExemplarExpectation[A]

  /** Adds a clue that can be surfaced when the expectation fails. */
  def clue(text: String): ExemplarExpectation[A]

  /** Adds a custom predicate that must return `true` for the exemplar. */
  def where(f: ExemplarData.Aux[A] => Boolean): ExemplarExpectation[A]

  /** Adds a custom predicate with a clue used when the predicate fails. */
  def where(clue: String)(f: ExemplarData.Aux[A] => Boolean): ExemplarExpectation[A]

  /** Checks the exemplar and returns all mismatches, if any. */
  def check(exemplar: ExemplarData): Either[NonEmptyList[ExemplarExpectation.Mismatch], Unit]

  /** Returns `true` when the exemplar satisfies this expectation. */
  final def matches(exemplar: ExemplarData): Boolean = check(exemplar).isRight

  /** Returns the clue attached to this expectation, if present. */
  def clue: Option[String]
}

object ExemplarExpectation {

  /** Describes a single reason why an exemplar did not satisfy an expectation. */
  sealed trait Mismatch extends Product with Serializable {

    /** A human-readable description of the mismatch. */
    def message: String
  }

  object Mismatch {

    /** Indicates that the exemplar type did not match the expected numeric kind. */
    sealed trait TypeMismatch extends Mismatch {

      /** The expected exemplar type name. */
      def expected: String

      /** The actual exemplar type name. */
      def actual: String
    }

    /** Indicates that the exemplar value did not match the expected value. */
    sealed trait ValueMismatch extends Mismatch {

      /** The expected value rendered for diagnostics. */
      def expected: String

      /** The actual value rendered for diagnostics. */
      def actual: String
    }

    /** Indicates that filtered attributes did not satisfy the nested expectation. */
    sealed trait FilteredAttributesMismatch extends Mismatch {

      /** Nested attribute mismatches. */
      def mismatches: NonEmptyList[AttributesExpectation.Mismatch]
    }

    /** Indicates that the exemplar timestamp did not match. */
    sealed trait TimestampMismatch extends Mismatch {

      /** The expected timestamp. */
      def expected: FiniteDuration

      /** The actual timestamp. */
      def actual: FiniteDuration
    }

    /** Indicates that the exemplar trace context did not match. */
    sealed trait TraceContextMismatch extends Mismatch {

      /** The expected trace context, or absence of one. */
      def expected: Option[TraceContext]

      /** The actual trace context, or absence of one. */
      def actual: Option[TraceContext]
    }

    /** Indicates that a custom predicate returned `false`. */
    sealed trait PredicateMismatch extends Mismatch {

      /** The clue describing the failed predicate. */
      def clue: String
    }

    /** Creates a mismatch for an unexpected exemplar type. */
    def typeMismatch(expected: String, actual: String): TypeMismatch =
      TypeMismatchImpl(expected, actual)

    /** Creates a mismatch for an unexpected exemplar value. */
    def valueMismatch(expected: String, actual: String): ValueMismatch =
      ValueMismatchImpl(expected, actual)

    /** Creates a mismatch for filtered attributes that failed validation. */
    def filteredAttributesMismatch(
        mismatches: NonEmptyList[AttributesExpectation.Mismatch]
    ): FilteredAttributesMismatch =
      FilteredAttributesMismatchImpl(mismatches)

    /** Creates a mismatch for an unexpected timestamp. */
    def timestampMismatch(expected: FiniteDuration, actual: FiniteDuration): TimestampMismatch =
      TimestampMismatchImpl(expected, actual)

    /** Creates a mismatch for an unexpected trace context. */
    def traceContextMismatch(expected: Option[TraceContext], actual: Option[TraceContext]): TraceContextMismatch =
      TraceContextMismatchImpl(expected, actual)

    /** Creates a mismatch for a failed custom predicate. */
    def predicateMismatch(clue: String): PredicateMismatch =
      PredicateMismatchImpl(clue)

    private final case class TypeMismatchImpl(expected: String, actual: String) extends TypeMismatch {
      def message: String = s"type mismatch: expected '$expected', got '$actual'"
    }

    private final case class ValueMismatchImpl(expected: String, actual: String) extends ValueMismatch {
      def message: String = s"value mismatch: expected '$expected', got '$actual'"
    }

    private final case class FilteredAttributesMismatchImpl(
        mismatches: NonEmptyList[AttributesExpectation.Mismatch]
    ) extends FilteredAttributesMismatch {
      def message: String =
        s"filtered attributes mismatch: ${mismatches.toList.map(_.message).mkString(", ")}"
    }

    private final case class TimestampMismatchImpl(expected: FiniteDuration, actual: FiniteDuration)
        extends TimestampMismatch {
      def message: String = s"timestamp mismatch: expected $expected, got $actual"
    }

    private final case class TraceContextMismatchImpl(expected: Option[TraceContext], actual: Option[TraceContext])
        extends TraceContextMismatch {
      def message: String = s"trace context mismatch: expected $expected, got $actual"
    }

    private final case class PredicateMismatchImpl(clue: String) extends PredicateMismatch {
      def message: String = s"predicate mismatch: $clue"
    }
  }

  /** Creates an expectation for a numeric exemplar value. */
  def numeric[A: MeasurementValue: NumberComparison](value: A): ExemplarExpectation[A] =
    Impl(
      expectedValue = Some(value),
      valueType = MeasurementValue[A],
      numberComparison = NumberComparison[A]
    )

  private final case class Impl[A](
      expectedValue: Option[A],
      valueType: MeasurementValue[A],
      numberComparison: NumberComparison[A],
      filteredAttributesExpectation: Option[AttributesExpectation] = None,
      expectedTimestamp: Option[FiniteDuration] = None,
      expectedTraceContext: Option[Option[TraceContext]] = None,
      clue: Option[String] = None,
      predicates: List[(ExemplarData.Aux[A] => Boolean, Option[String])] = Nil
  ) extends ExemplarExpectation[A] {
    def value(value: A): ExemplarExpectation[A] = copy(expectedValue = Some(value))
    def filteredAttributes(expectation: AttributesExpectation): ExemplarExpectation[A] =
      copy(filteredAttributesExpectation = Some(expectation))
    def filteredAttributesExact(attributes: Attributes): ExemplarExpectation[A] =
      filteredAttributes(AttributesExpectation.exact(attributes))
    def filteredAttributesExact(attributes: Attribute[_]*): ExemplarExpectation[A] =
      filteredAttributesExact(Attributes(attributes *))
    def filteredAttributesEmpty: ExemplarExpectation[A] =
      filteredAttributesExact(Attributes.empty)
    def filteredAttributesSubset(attributes: Attributes): ExemplarExpectation[A] =
      filteredAttributes(AttributesExpectation.subset(attributes))
    def filteredAttributesSubset(attributes: Attribute[_]*): ExemplarExpectation[A] =
      filteredAttributesSubset(Attributes(attributes *))
    def timestamp(timestamp: FiniteDuration): ExemplarExpectation[A] =
      copy(expectedTimestamp = Some(timestamp))
    def traceContext(traceContext: TraceContext): ExemplarExpectation[A] =
      copy(expectedTraceContext = Some(Some(traceContext)))
    def traceContextAbsent: ExemplarExpectation[A] =
      copy(expectedTraceContext = Some(None))
    def clue(text: String): ExemplarExpectation[A] = copy(clue = Some(text))
    def where(f: ExemplarData.Aux[A] => Boolean): ExemplarExpectation[A] =
      copy(predicates = predicates :+ (f -> None))
    def where(clue: String)(f: ExemplarData.Aux[A] => Boolean): ExemplarExpectation[A] =
      copy(predicates = predicates :+ (f -> Some(clue)))

    def check(exemplar: ExemplarData): Either[NonEmptyList[Mismatch], Unit] =
      toTypedExemplar(valueType, exemplar) match {
        case Right(typed) =>
          ExpectationChecks.combine(
            expectedValue.fold(ExpectationChecks.success[Mismatch]) { expected =>
              if (numberComparison.equal(expected, typed.value)) ExpectationChecks.success
              else {
                val exp = numberComparison.render(expected)
                val act = numberComparison.render(typed.value)
                ExpectationChecks.mismatch(Mismatch.valueMismatch(exp, act))
              }
            },
            filteredAttributesExpectation.fold(ExpectationChecks.success[Mismatch]) { expected =>
              ExpectationChecks.nested(expected.check(typed.filteredAttributes))(Mismatch.filteredAttributesMismatch)
            },
            expectedTimestamp.fold(ExpectationChecks.success[Mismatch]) { expected =>
              if (expected == typed.timestamp) ExpectationChecks.success
              else ExpectationChecks.mismatch(Mismatch.timestampMismatch(expected, typed.timestamp))
            },
            expectedTraceContext.fold(ExpectationChecks.success[Mismatch]) { expected =>
              if (expected == typed.traceContext) ExpectationChecks.success
              else ExpectationChecks.mismatch(Mismatch.traceContextMismatch(expected, typed.traceContext))
            },
            ExpectationChecks.combine(predicates.map { case (predicate, clue) =>
              if (predicate(typed)) ExpectationChecks.success
              else
                ExpectationChecks.mismatch(
                  Mismatch.predicateMismatch(clue.getOrElse("exemplar predicate returned false"))
                )
            })
          )
        case Left(mismatch) =>
          ExpectationChecks.mismatch(mismatch)
      }
  }

  private[metrics] def toTypedExemplar[A](
      valueType: MeasurementValue[A],
      exemplar: ExemplarData
  ): Either[Mismatch, ExemplarData.Aux[A]] =
    valueType match {
      case _: LongMeasurementValue[_] =>
        exemplar match {
          case long: ExemplarData.LongExemplar =>
            Right(long.asInstanceOf[ExemplarData.Aux[A]])
          case other =>
            Left(Mismatch.typeMismatch("LongExemplar", exemplarTypeName(other)))
        }
      case _: DoubleMeasurementValue[_] =>
        exemplar match {
          case double: ExemplarData.DoubleExemplar =>
            Right(double.asInstanceOf[ExemplarData.Aux[A]])
          case other =>
            Left(Mismatch.typeMismatch("DoubleExemplar", exemplarTypeName(other)))
        }
    }

  private def exemplarTypeName(exemplar: ExemplarData): String =
    exemplar match {
      case _: ExemplarData.LongExemplar   => "LongExemplar"
      case _: ExemplarData.DoubleExemplar => "DoubleExemplar"
    }
}
