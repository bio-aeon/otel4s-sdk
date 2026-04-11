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
import org.typelevel.otel4s.sdk.testkit.{FlatExpectationMatching, MaximumMatching}
import org.typelevel.otel4s.sdk.trace.data.SpanData

/** Helpers for matching collected spans against exact trace expectations. */
object TraceExpectations {
  private[trace] final case class ActualTrace(span: SpanData, children: List[ActualTrace])

  /** Checks that the collected spans form exactly the trace trees described by the expectation. */
  def check(
      spans: List[SpanData],
      expectation: TraceForestExpectation
  ): Either[NonEmptyList[TraceForestExpectation.Mismatch], Unit] = {
    val result = {
      val traces = buildTraces(spans)
      val countMismatch =
        if (expectation.roots.length == traces.length) Nil
        else
          List(
            TraceForestExpectation.Mismatch.RootCountMismatch(
              expectation.roots.length,
              traces.length,
              traces.map(_.span.name)
            )
          )

      val rootMismatches =
        if (expectation.roots.length != traces.length) Nil
        else
          expectation.rootsMatchMode match {
            case TraceForestExpectation.MatchMode.Ordered =>
              expectation.roots.zip(traces).flatMap { case (rootExpectation, actualTrace) =>
                rootExpectation.check(actualTrace).left.toOption.map { mismatches =>
                  TraceForestExpectation.Mismatch.RootMismatch(actualTrace.span, mismatches)
                }
              }

            case TraceForestExpectation.MatchMode.Unordered =>
              val candidates = expectation.roots.toVector.map { root =>
                traces.indices.filter(index => root.check(traces(index)).isRight).toList
              }
              val matching = MaximumMatching.find(candidates)

              expectation.roots.indices.collect {
                case index if !matching.matchedExpectationIndices(index) =>
                  candidates(index) match {
                    case Nil =>
                      bestRootMismatch(traces, expectation.roots(index))
                    case matches =>
                      TraceForestExpectation.Mismatch.DistinctRootMatchUnavailable(
                        expectation.roots(index),
                        matches.map(traces(_).span.name).distinct
                      )
                  }
              }.toList
          }

      NonEmptyList.fromList(countMismatch ++ rootMismatches).toLeft(())
    }

    expectation.clue match {
      case Some(value) =>
        result.left.map(mismatches =>
          NonEmptyList.one(TraceForestExpectation.Mismatch.CluedMismatch(value, mismatches))
        )
      case None => result
    }
  }

  /** Formats mismatches into a multi-line human-readable failure message. */
  def format(mismatches: NonEmptyList[TraceForestExpectation.Mismatch]): String =
    FlatExpectationMatching.format("Trace expectations", mismatches)(_.message)

  private[trace] def bestRootMismatch(
      traces: List[ActualTrace],
      expectation: TraceExpectation
  ): TraceForestExpectation.Mismatch =
    traces
      .flatMap(tree => expectation.check(tree).left.toOption.map(tree -> _))
      .sortBy { case (tree, mismatches) =>
        val nameMismatch = mismatches.exists {
          case spanMismatch: TraceExpectation.Mismatch.SpanMismatch =>
            spanMismatch.mismatches.exists {
              case _: SpanExpectation.Mismatch.NameMismatch => true
              case _                                        => false
            }
          case _ => false
        }
        (nameMismatch, mismatches.length, tree.children.length)
      }
      .headOption
      .map { case (tree, mismatches) => TraceForestExpectation.Mismatch.RootMismatch(tree.span, mismatches) }
      .getOrElse(TraceForestExpectation.Mismatch.MissingRoot(expectation, traces.map(_.span.name)))

  private[trace] def bestChildMismatch(
      traces: List[ActualTrace],
      expectation: TraceExpectation
  ): TraceExpectation.Mismatch =
    traces
      .flatMap(tree => expectation.check(tree).left.toOption.map(tree -> _))
      .sortBy { case (tree, mismatches) =>
        val nameMismatch = mismatches.exists {
          case spanMismatch: TraceExpectation.Mismatch.SpanMismatch =>
            spanMismatch.mismatches.exists {
              case _: SpanExpectation.Mismatch.NameMismatch => true
              case _                                        => false
            }
          case _ => false
        }
        (nameMismatch, mismatches.length, tree.children.length)
      }
      .headOption
      .map { case (tree, mismatches) => TraceExpectation.Mismatch.ChildMismatch(tree.span, mismatches) }
      .getOrElse(TraceExpectation.Mismatch.MissingChild(expectation, traces.map(_.span.name)))

  private def buildTraces(spans: List[SpanData]): List[ActualTrace] = {
    val spansById = spans.map(span => span.spanContext.spanIdHex -> span).toMap
    val childrenByParentId = spans.foldLeft(Map.empty[String, List[SpanData]]) { case (acc, span) =>
      span.parentSpanContext
        .map(_.spanIdHex)
        .filter(spansById.contains)
        .fold(acc)(parentId => acc.updated(parentId, acc.getOrElse(parentId, Nil) :+ span))
    }

    def loop(span: SpanData): ActualTrace =
      ActualTrace(span, childrenByParentId.getOrElse(span.spanContext.spanIdHex, Nil).map(loop))

    spans
      .filter { span =>
        span.parentSpanContext.forall(parent => !spansById.contains(parent.spanIdHex))
      }
      .map(loop)
  }
}
