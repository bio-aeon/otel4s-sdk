package org.typelevel.otel4s.sdk.testkit.trace

import cats.data.NonEmptyList
import org.typelevel.otel4s.sdk.trace.data.SpanData

/** An exact expectation for the full set of exported traces in one collection. */
sealed trait TraceForestExpectation {

  /** The exact list of expected root traces. */
  def roots: List[TraceExpectation]

  /** How root traces are matched. */
  def rootsMatchMode: TraceForestExpectation.MatchMode

  /** An optional human-readable clue shown in mismatch messages. */
  def clue: Option[String]

  /** Attaches a human-readable clue to this expectation. */
  def clue(text: String): TraceForestExpectation
}

object TraceForestExpectation {

  sealed trait MatchMode extends Product with Serializable

  object MatchMode {
    case object Ordered extends MatchMode
    case object Unordered extends MatchMode
  }

  /** A structured reason explaining why a [[TraceForestExpectation]] did not match exported traces. */
  sealed trait Mismatch extends Product with Serializable {

    /** A human-readable description of the mismatch. */
    def message: String
  }

  object Mismatch {

    private[testkit] final case class RootCountMismatch(expected: Int, actual: Int, actualRootNames: List[String])
        extends Mismatch {
      def message: String =
        s"root count mismatch: expected '$expected', got '$actual'; actual roots: [${actualRootNames.mkString(", ")}]"
    }

    private[testkit] final case class MissingRoot(expectation: TraceExpectation, availableRootNames: List[String])
        extends Mismatch {
      def message: String =
        s"missing root matching expectation; available roots: [${availableRootNames.mkString(", ")}]"
    }

    private[testkit] final case class DistinctRootMatchUnavailable(
        expectation: TraceExpectation,
        candidateRootNames: List[String]
    ) extends Mismatch {
      def message: String =
        s"no distinct root remained for the expectation; matched roots: [${candidateRootNames.mkString(", ")}]"
    }

    private[testkit] final case class RootMismatch(
        actual: SpanData,
        mismatches: NonEmptyList[TraceExpectation.Mismatch]
    ) extends Mismatch {
      def message: String =
        s"trace mismatch for root '${actual.name}': ${mismatches.toList.map(_.message).mkString(", ")}"
    }

    private[testkit] final case class CluedMismatch(clue: String, mismatches: NonEmptyList[Mismatch]) extends Mismatch {
      def message: String =
        s"trace expectations mismatch [$clue]: ${mismatches.toList.map(_.message).mkString(", ")}"
    }
  }

  /** Creates an expectation that requires no exported root traces. */
  def empty: TraceForestExpectation =
    Impl(Nil, MatchMode.Unordered, None)

  /** Creates an expectation whose root traces must match in order. */
  def ordered(first: TraceExpectation, rest: TraceExpectation*): TraceForestExpectation =
    Impl(NonEmptyList(first, rest.toList).toList, MatchMode.Ordered, None)

  /** Creates an expectation whose root traces may match in any order. */
  def unordered(first: TraceExpectation, rest: TraceExpectation*): TraceForestExpectation =
    Impl(NonEmptyList(first, rest.toList).toList, MatchMode.Unordered, None)

  private final case class Impl(
      roots: List[TraceExpectation],
      rootsMatchMode: MatchMode,
      clue: Option[String]
  ) extends TraceForestExpectation {
    def clue(text: String): TraceForestExpectation = copy(clue = Some(text))
  }
}
