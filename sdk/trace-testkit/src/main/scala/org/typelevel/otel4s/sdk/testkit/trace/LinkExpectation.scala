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

package org.typelevel.otel4s.sdk.testkit.trace

import cats.data.NonEmptyList
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.Attributes
import org.typelevel.otel4s.sdk.testkit.AttributesExpectation
import org.typelevel.otel4s.sdk.testkit.ExpectationChecks
import org.typelevel.otel4s.sdk.trace.data.LinkData
import org.typelevel.otel4s.trace.SpanContext

/** A partial expectation for a single [[org.typelevel.otel4s.sdk.trace.data.LinkData]].
  *
  * `LinkExpectation` is intended for tests where asserting against the full link payload would be too verbose.
  * Unspecified properties are ignored.
  */
sealed trait LinkExpectation {

  /** An optional human-readable clue shown in mismatch messages. */
  def clue: Option[String]

  /** Requires the link span context to match the given expectation. */
  def spanContext(expectation: SpanContextExpectation): LinkExpectation

  /** Requires the link span context to match exactly. */
  def spanContextExact(spanContext: SpanContext): LinkExpectation

  /** Requires the link attributes to match the given expectation. */
  def attributes(expectation: AttributesExpectation): LinkExpectation

  /** Requires the link attributes to match exactly. */
  def attributesExact(attributes: Attributes): LinkExpectation

  /** Requires the link attributes to match exactly. */
  def attributesExact(attributes: Attribute[_]*): LinkExpectation

  /** Requires the link attributes to contain at least the given subset. */
  def attributesSubset(attributes: Attributes): LinkExpectation

  /** Requires the link attributes to contain at least the given subset. */
  def attributesSubset(attributes: Attribute[_]*): LinkExpectation

  /** Requires the link to have no attributes. */
  def attributesEmpty: LinkExpectation

  /** Attaches a human-readable clue to this expectation. */
  def clue(text: String): LinkExpectation

  /** Adds a custom predicate over the link data. */
  def where(f: LinkData => Boolean): LinkExpectation

  /** Adds a custom predicate over the link data with a clue shown in mismatches. */
  def where(clue: String)(f: LinkData => Boolean): LinkExpectation

  /** Checks the given link and returns structured mismatches when the expectation does not match. */
  def check(link: LinkData): Either[NonEmptyList[LinkExpectation.Mismatch], Unit]

  /** Returns `true` if this expectation matches the given link. */
  final def matches(link: LinkData): Boolean =
    check(link).isRight
}

object LinkExpectation {

  /** A structured reason explaining why a [[LinkExpectation]] did not match a link. */
  sealed trait Mismatch extends Product with Serializable {

    /** A human-readable description of the mismatch. */
    def message: String
  }

  object Mismatch {

    private[testkit] final case class SpanContextMismatch(mismatches: NonEmptyList[SpanContextExpectation.Mismatch])
        extends Mismatch {
      def message: String = s"span context mismatch: ${mismatches.toList.map(_.message).mkString(", ")}"
    }

    private[testkit] final case class AttributesMismatch(mismatches: NonEmptyList[AttributesExpectation.Mismatch])
        extends Mismatch {
      def message: String = s"attributes mismatch: ${mismatches.toList.map(_.message).mkString(", ")}"
    }

    private[testkit] final case class PredicateMismatch(clue: Option[String]) extends Mismatch {
      def message: String = s"link predicate returned false${clue.fold("")(v => s": $v")}"
    }
  }

  /** Creates an expectation that leaves all link fields unconstrained. */
  def any: LinkExpectation =
    Impl()

  private final case class Impl(
      expectedSpanContext: Option[SpanContextExpectation] = None,
      expectedAttributes: Option[AttributesExpectation] = None,
      clue: Option[String] = None,
      predicates: List[(LinkData => Boolean, Option[String])] = Nil
  ) extends LinkExpectation {
    def spanContext(expectation: SpanContextExpectation): LinkExpectation =
      copy(expectedSpanContext = Some(expectation))
    def spanContextExact(spanContext: SpanContext): LinkExpectation =
      copy(expectedSpanContext = Some(SpanContextExpectation.exact(spanContext)))
    def attributes(expectation: AttributesExpectation): LinkExpectation = copy(expectedAttributes = Some(expectation))
    def attributesExact(attributes: Attributes): LinkExpectation =
      copy(expectedAttributes = Some(AttributesExpectation.exact(attributes)))
    def attributesExact(attributes: Attribute[_]*): LinkExpectation = attributesExact(Attributes(attributes: _*))
    def attributesSubset(attributes: Attributes): LinkExpectation =
      copy(expectedAttributes = Some(AttributesExpectation.subset(attributes)))
    def attributesSubset(attributes: Attribute[_]*): LinkExpectation = attributesSubset(Attributes(attributes: _*))
    def attributesEmpty: LinkExpectation = attributesExact(Attributes.empty)
    def clue(text: String): LinkExpectation = copy(clue = Some(text))
    def where(f: LinkData => Boolean): LinkExpectation = copy(predicates = predicates :+ (f -> None))
    def where(clue: String)(f: LinkData => Boolean): LinkExpectation =
      copy(predicates = predicates :+ (f -> Some(clue)))

    def check(link: LinkData): Either[NonEmptyList[Mismatch], Unit] =
      ExpectationChecks.combine(
        expectedSpanContext.fold(ExpectationChecks.success[Mismatch]) { expected =>
          ExpectationChecks.nested(expected.check(link.spanContext))(Mismatch.SpanContextMismatch(_))
        },
        expectedAttributes.fold(ExpectationChecks.success[Mismatch]) { expected =>
          ExpectationChecks.nested(expected.check(link.attributes.elements))(Mismatch.AttributesMismatch(_))
        },
        ExpectationChecks.combine(predicates.map { case (predicate, clue) =>
          if (predicate(link)) ExpectationChecks.success
          else ExpectationChecks.mismatch(Mismatch.PredicateMismatch(clue))
        })
      )
  }
}
