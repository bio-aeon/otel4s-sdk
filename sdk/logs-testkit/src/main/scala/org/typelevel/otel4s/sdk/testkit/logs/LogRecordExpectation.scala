/*
 * Copyright 2025 Typelevel
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

package org.typelevel.otel4s.sdk.testkit.logs

import cats.data.NonEmptyList
import org.typelevel.otel4s.AnyValue
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.Attributes
import org.typelevel.otel4s.logs.Severity
import org.typelevel.otel4s.sdk.context.TraceContext
import org.typelevel.otel4s.sdk.logs.data.LogRecordData
import org.typelevel.otel4s.sdk.testkit.AttributesExpectation
import org.typelevel.otel4s.sdk.testkit.ExpectationChecks
import org.typelevel.otel4s.sdk.testkit.InstrumentationScopeExpectation
import org.typelevel.otel4s.sdk.testkit.TelemetryResourceExpectation

import scala.concurrent.duration.FiniteDuration

/** A partial expectation for a single SDK [[org.typelevel.otel4s.sdk.logs.data.LogRecordData]].
  *
  * `LogRecordExpectation` is intended for tests where asserting against the full `LogRecordData` shape would be too
  * verbose. Unspecified properties are ignored.
  */
sealed trait LogRecordExpectation {
  private[logs] def expectedBody: Option[AnyValue]
  private[logs] def expectedSeverity: Option[Severity]
  private[logs] def expectedEventName: Option[String]
  private[logs] def expectsTraceCorrelation: Boolean

  /** An optional human-readable clue shown in mismatch messages. */
  def clue: Option[String]

  /** Requires the log body to match exactly. */
  def body(body: AnyValue): LogRecordExpectation

  /** Requires the log body to be an exact string message. */
  def message(message: String): LogRecordExpectation

  /** Requires the log severity to match exactly. */
  def severity(severity: Severity): LogRecordExpectation

  /** Requires the log severity text to match exactly. */
  def severityText(text: String): LogRecordExpectation

  /** Requires the log event name to match exactly. */
  def eventName(name: String): LogRecordExpectation

  /** Requires the log event name to be absent. */
  def noEventName: LogRecordExpectation

  /** Requires the log trace id to match exactly. */
  def traceId(traceId: String): LogRecordExpectation

  /** Requires the log span id to match exactly. */
  def spanId(spanId: String): LogRecordExpectation

  /** Requires the log to be untraced. */
  def untraced: LogRecordExpectation

  /** Requires the log timestamp to match exactly. */
  def timestamp(timestamp: FiniteDuration): LogRecordExpectation

  /** Requires the log timestamp to match exactly, including absence. */
  def timestamp(timestamp: Option[FiniteDuration]): LogRecordExpectation

  /** Requires the observed timestamp to match exactly. */
  def observedTimestamp(timestamp: FiniteDuration): LogRecordExpectation

  /** Adds a predicate over the log timestamp. */
  def timestampWhere(f: Option[FiniteDuration] => Boolean): LogRecordExpectation

  /** Adds a predicate over the log timestamp with a clue shown in mismatches. */
  def timestampWhere(clue: String)(f: Option[FiniteDuration] => Boolean): LogRecordExpectation

  /** Adds a predicate over the observed timestamp. */
  def observedTimestampWhere(f: FiniteDuration => Boolean): LogRecordExpectation

  /** Adds a predicate over the observed timestamp with a clue shown in mismatches. */
  def observedTimestampWhere(clue: String)(f: FiniteDuration => Boolean): LogRecordExpectation

  /** Requires the log attributes to match the given expectation. */
  def attributes(expectation: AttributesExpectation): LogRecordExpectation

  /** Requires the log attributes to match exactly. */
  def attributesExact(attributes: Attributes): LogRecordExpectation

  /** Requires the log attributes to match exactly. */
  def attributesExact(attributes: Attribute[_]*): LogRecordExpectation

  /** Requires the log attributes to contain at least the given subset. */
  def attributesSubset(attributes: Attributes): LogRecordExpectation

  /** Requires the log attributes to contain at least the given subset. */
  def attributesSubset(attributes: Attribute[_]*): LogRecordExpectation

  /** Requires the log to have no attributes. */
  def attributesEmpty: LogRecordExpectation

  /** Requires the instrumentation scope name to match exactly. */
  def scopeName(name: String): LogRecordExpectation

  /** Requires the instrumentation scope to match the given expectation. */
  def scope(expectation: InstrumentationScopeExpectation): LogRecordExpectation

  /** Requires the telemetry resource to match the given expectation. */
  def resource(expectation: TelemetryResourceExpectation): LogRecordExpectation

  /** Attaches a human-readable clue to this expectation. */
  def clue(text: String): LogRecordExpectation

  /** Adds a custom predicate over the whole log record. */
  def where(f: LogRecordData => Boolean): LogRecordExpectation

  /** Adds a custom predicate over the whole log record with a clue shown in mismatches. */
  def where(clue: String)(f: LogRecordData => Boolean): LogRecordExpectation

  /** Checks the given log record and returns structured mismatches when the expectation does not match. */
  def check(record: LogRecordData): Either[NonEmptyList[LogRecordExpectation.Mismatch], Unit]

  /** Returns `true` if this expectation matches the given record. */
  final def matches(record: LogRecordData): Boolean =
    check(record).isRight
}

object LogRecordExpectation {

  /** A structured reason explaining why a [[LogRecordExpectation]] did not match a log record. */
  sealed trait Mismatch extends Product with Serializable {
    def message: String
  }

  object Mismatch {

    private[testkit] final case class BodyMismatch(expected: AnyValue, actual: Option[AnyValue]) extends Mismatch {
      def message: String =
        s"body mismatch: expected '$expected', got ${actual.fold("<missing>")(v => s"'$v'")}"
    }

    private[testkit] final case class SeverityMismatch(expected: Severity, actual: Option[Severity]) extends Mismatch {
      def message: String =
        s"severity mismatch: expected '$expected', got ${actual.fold("<missing>")(v => s"'$v'")}"
    }

    private[testkit] final case class SeverityTextMismatch(expected: String, actual: Option[String]) extends Mismatch {
      def message: String =
        s"severity text mismatch: expected '$expected', got ${actual.fold("<missing>")(v => s"'$v'")}"
    }

    private[testkit] final case class EventNameMismatch(expected: Option[String], actual: Option[String])
        extends Mismatch {
      def message: String = {
        val exp = expected.fold("<missing>")(v => s"'$v'")
        val act = actual.fold("<missing>")(v => s"'$v'")
        s"event name mismatch: expected $exp, got $act"
      }
    }

    private[testkit] final case class TraceIdMismatch(expected: String, actual: Option[String]) extends Mismatch {
      def message: String =
        s"trace id mismatch: expected '$expected', got ${actual.fold("<missing>")(v => s"'$v'")}"
    }

    private[testkit] final case class SpanIdMismatch(expected: String, actual: Option[String]) extends Mismatch {
      def message: String =
        s"span id mismatch: expected '$expected', got ${actual.fold("<missing>")(v => s"'$v'")}"
    }

    private[testkit] final case class UntracedMismatch(actual: TraceContext) extends Mismatch {
      def message: String =
        s"trace correlation mismatch: expected <missing>, got '$actual'"
    }

    private[testkit] final case class TimestampMismatch(
        expected: Option[FiniteDuration],
        actual: Option[FiniteDuration]
    ) extends Mismatch {
      def message: String = {
        val exp = expected.fold("<missing>")(v => s"'$v'")
        val act = actual.fold("<missing>")(v => s"'$v'")
        s"timestamp mismatch: expected $exp, got $act"
      }
    }

    private[testkit] final case class ObservedTimestampMismatch(
        expected: FiniteDuration,
        actual: FiniteDuration
    ) extends Mismatch {
      def message: String =
        s"observed timestamp mismatch: expected '$expected', got '$actual'"
    }

    private[testkit] final case class TimestampPredicateMismatch(clue: Option[String]) extends Mismatch {
      def message: String =
        s"timestamp predicate returned false${clue.fold("")(v => s": $v")}"
    }

    private[testkit] final case class ObservedTimestampPredicateMismatch(clue: Option[String]) extends Mismatch {
      def message: String =
        s"observed timestamp predicate returned false${clue.fold("")(v => s": $v")}"
    }

    private[testkit] final case class AttributesMismatch(mismatches: NonEmptyList[AttributesExpectation.Mismatch])
        extends Mismatch {
      def message: String =
        s"attributes mismatch: ${mismatches.toList.map(_.message).mkString(", ")}"
    }

    private[testkit] final case class ScopeMismatch(
        mismatches: NonEmptyList[InstrumentationScopeExpectation.Mismatch]
    ) extends Mismatch {
      def message: String =
        s"scope mismatch: ${mismatches.toList.map(_.message).mkString(", ")}"
    }

    private[testkit] final case class ResourceMismatch(mismatches: NonEmptyList[TelemetryResourceExpectation.Mismatch])
        extends Mismatch {
      def message: String =
        s"resource mismatch: ${mismatches.toList.map(_.message).mkString(", ")}"
    }

    private[testkit] final case class PredicateMismatch(clue: Option[String]) extends Mismatch {
      def message: String =
        s"predicate mismatch${clue.fold("")(v => s": $v")}"
    }
  }

  /** Creates an expectation that leaves all log record fields unconstrained. */
  def any: LogRecordExpectation = Impl()

  /** Creates an expectation matching a log record with the given exact raw body. */
  def body(body: AnyValue): LogRecordExpectation = Impl(expectedBodyValue = Some(body))

  /** Creates an expectation matching a log record with the given exact string message. */
  def message(message: String): LogRecordExpectation = body(AnyValue.string(message))

  /** Creates an expectation matching a log record with the given severity. */
  def severity(severity: Severity): LogRecordExpectation = Impl(expectedSeverityValue = Some(severity))

  /** Creates an expectation matching a log record with the given event name. */
  def eventName(name: String): LogRecordExpectation = Impl(expectedEventNameValue = Some(Some(name)))

  private final case class Impl(
      expectedBodyValue: Option[AnyValue] = None,
      expectedSeverityValue: Option[Severity] = None,
      expectedSeverityText: Option[String] = None,
      expectedEventNameValue: Option[Option[String]] = None,
      expectedTraceId: Option[String] = None,
      expectedSpanId: Option[String] = None,
      untracedRequired: Boolean = false,
      expectedTimestamp: Option[Option[FiniteDuration]] = None,
      expectedObservedTimestamp: Option[FiniteDuration] = None,
      timestampPredicates: List[(Option[FiniteDuration] => Boolean, Option[String])] = Nil,
      observedTimestampPredicates: List[(FiniteDuration => Boolean, Option[String])] = Nil,
      attributesExpectation: Option[AttributesExpectation] = None,
      scopeExpectation: Option[InstrumentationScopeExpectation] = None,
      resourceExpectation: Option[TelemetryResourceExpectation] = None,
      clue: Option[String] = None,
      predicates: List[(LogRecordData => Boolean, Option[String])] = Nil
  ) extends LogRecordExpectation {
    private[logs] def expectedBody: Option[AnyValue] = expectedBodyValue
    private[logs] def expectedSeverity: Option[Severity] = expectedSeverityValue
    private[logs] def expectedEventName: Option[String] = expectedEventNameValue.flatten
    private[logs] def expectsTraceCorrelation: Boolean =
      expectedTraceId.nonEmpty || expectedSpanId.nonEmpty || untracedRequired

    def body(body: AnyValue): LogRecordExpectation = copy(expectedBodyValue = Some(body))
    def message(message: String): LogRecordExpectation = body(AnyValue.string(message))
    def severity(severity: Severity): LogRecordExpectation = copy(expectedSeverityValue = Some(severity))
    def severityText(text: String): LogRecordExpectation = copy(expectedSeverityText = Some(text))
    def eventName(name: String): LogRecordExpectation = copy(expectedEventNameValue = Some(Some(name)))
    def noEventName: LogRecordExpectation = copy(expectedEventNameValue = Some(None))
    def traceId(traceId: String): LogRecordExpectation = copy(expectedTraceId = Some(traceId), untracedRequired = false)
    def spanId(spanId: String): LogRecordExpectation = copy(expectedSpanId = Some(spanId), untracedRequired = false)
    def untraced: LogRecordExpectation = copy(expectedTraceId = None, expectedSpanId = None, untracedRequired = true)
    def timestamp(timestamp: FiniteDuration): LogRecordExpectation = this.timestamp(Some(timestamp))
    def timestamp(timestamp: Option[FiniteDuration]): LogRecordExpectation = copy(expectedTimestamp = Some(timestamp))
    def observedTimestamp(timestamp: FiniteDuration): LogRecordExpectation =
      copy(expectedObservedTimestamp = Some(timestamp))
    def timestampWhere(f: Option[FiniteDuration] => Boolean): LogRecordExpectation =
      copy(timestampPredicates = timestampPredicates :+ (f -> None))
    def timestampWhere(clue: String)(f: Option[FiniteDuration] => Boolean): LogRecordExpectation =
      copy(timestampPredicates = timestampPredicates :+ (f -> Some(clue)))
    def observedTimestampWhere(f: FiniteDuration => Boolean): LogRecordExpectation =
      copy(observedTimestampPredicates = observedTimestampPredicates :+ (f -> None))
    def observedTimestampWhere(clue: String)(f: FiniteDuration => Boolean): LogRecordExpectation =
      copy(observedTimestampPredicates = observedTimestampPredicates :+ (f -> Some(clue)))
    def attributes(expectation: AttributesExpectation): LogRecordExpectation =
      copy(attributesExpectation = Some(expectation))
    def attributesExact(attributes: Attributes): LogRecordExpectation =
      this.attributes(AttributesExpectation.exact(attributes))
    def attributesExact(attributes: Attribute[_]*): LogRecordExpectation = attributesExact(Attributes(attributes: _*))
    def attributesSubset(attributes: Attributes): LogRecordExpectation =
      this.attributes(AttributesExpectation.subset(attributes))
    def attributesSubset(attributes: Attribute[_]*): LogRecordExpectation = attributesSubset(Attributes(attributes: _*))
    def attributesEmpty: LogRecordExpectation = attributesExact(Attributes.empty)
    def scopeName(name: String): LogRecordExpectation =
      copy(scopeExpectation = Some(scopeExpectation.fold(InstrumentationScopeExpectation.name(name))(_.name(name))))
    def scope(expectation: InstrumentationScopeExpectation): LogRecordExpectation =
      copy(scopeExpectation = Some(expectation))
    def resource(expectation: TelemetryResourceExpectation): LogRecordExpectation =
      copy(resourceExpectation = Some(expectation))
    def clue(text: String): LogRecordExpectation = copy(clue = Some(text))
    def where(f: LogRecordData => Boolean): LogRecordExpectation = copy(predicates = predicates :+ (f -> None))
    def where(clue: String)(f: LogRecordData => Boolean): LogRecordExpectation =
      copy(predicates = predicates :+ (f -> Some(clue)))

    def check(record: LogRecordData): Either[NonEmptyList[Mismatch], Unit] =
      ExpectationChecks.combine(
        expectedBodyValue.fold(ExpectationChecks.success[Mismatch]) { expected =>
          if (record.body.contains(expected)) ExpectationChecks.success
          else ExpectationChecks.mismatch(Mismatch.BodyMismatch(expected, record.body))
        },
        expectedSeverityValue.fold(ExpectationChecks.success[Mismatch]) { expected =>
          if (record.severity.contains(expected)) ExpectationChecks.success
          else ExpectationChecks.mismatch(Mismatch.SeverityMismatch(expected, record.severity))
        },
        expectedSeverityText.fold(ExpectationChecks.success[Mismatch]) { expected =>
          if (record.severityText.contains(expected)) ExpectationChecks.success
          else ExpectationChecks.mismatch(Mismatch.SeverityTextMismatch(expected, record.severityText))
        },
        expectedEventNameValue.fold(ExpectationChecks.success[Mismatch]) { expected =>
          if (record.eventName == expected) ExpectationChecks.success
          else ExpectationChecks.mismatch(Mismatch.EventNameMismatch(expected, record.eventName))
        },
        expectedTraceId.fold(ExpectationChecks.success[Mismatch]) { expected =>
          val actual = record.traceContext.map(_.traceId.toHex)
          if (actual.contains(expected)) ExpectationChecks.success
          else ExpectationChecks.mismatch(Mismatch.TraceIdMismatch(expected, actual))
        },
        expectedSpanId.fold(ExpectationChecks.success[Mismatch]) { expected =>
          val actual = record.traceContext.map(_.spanId.toHex)
          if (actual.contains(expected)) ExpectationChecks.success
          else ExpectationChecks.mismatch(Mismatch.SpanIdMismatch(expected, actual))
        },
        if (!untracedRequired) ExpectationChecks.success[Mismatch]
        else {
          record.traceContext match {
            case None        => ExpectationChecks.success
            case Some(value) => ExpectationChecks.mismatch(Mismatch.UntracedMismatch(value))
          }
        },
        expectedTimestamp.fold(ExpectationChecks.success[Mismatch]) { expected =>
          if (record.timestamp == expected) ExpectationChecks.success
          else ExpectationChecks.mismatch(Mismatch.TimestampMismatch(expected, record.timestamp))
        },
        expectedObservedTimestamp.fold(ExpectationChecks.success[Mismatch]) { expected =>
          if (record.observedTimestamp == expected) ExpectationChecks.success
          else ExpectationChecks.mismatch(Mismatch.ObservedTimestampMismatch(expected, record.observedTimestamp))
        },
        ExpectationChecks.combine(timestampPredicates.map { case (predicate, clue) =>
          if (predicate(record.timestamp)) ExpectationChecks.success
          else ExpectationChecks.mismatch(Mismatch.TimestampPredicateMismatch(clue))
        }),
        ExpectationChecks.combine(observedTimestampPredicates.map { case (predicate, clue) =>
          if (predicate(record.observedTimestamp)) ExpectationChecks.success
          else ExpectationChecks.mismatch(Mismatch.ObservedTimestampPredicateMismatch(clue))
        }),
        attributesExpectation.fold(ExpectationChecks.success[Mismatch]) { expected =>
          ExpectationChecks.nested(expected.check(record.attributes.elements))(Mismatch.AttributesMismatch(_))
        },
        scopeExpectation.fold(ExpectationChecks.success[Mismatch]) { expected =>
          ExpectationChecks.nested(expected.check(record.instrumentationScope))(Mismatch.ScopeMismatch(_))
        },
        resourceExpectation.fold(ExpectationChecks.success[Mismatch]) { expected =>
          ExpectationChecks.nested(expected.check(record.resource))(Mismatch.ResourceMismatch(_))
        },
        ExpectationChecks.combine(predicates.map { case (predicate, clue) =>
          if (predicate(record)) ExpectationChecks.success
          else ExpectationChecks.mismatch(Mismatch.PredicateMismatch(clue))
        })
      )
  }
}
