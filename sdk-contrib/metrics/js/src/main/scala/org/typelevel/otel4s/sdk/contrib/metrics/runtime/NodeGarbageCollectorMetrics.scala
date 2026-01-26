/*
 * Copyright 2026 Typelevel
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

package org.typelevel.otel4s.sdk.contrib.metrics.runtime

import cats.effect.Async
import cats.effect.Resource
import cats.effect.std.Dispatcher
import cats.effect.syntax.resource._
import org.typelevel.otel4s.AttributeKey
import org.typelevel.otel4s.Attributes
import org.typelevel.otel4s.metrics.BucketBoundaries
import org.typelevel.otel4s.metrics.Histogram
import org.typelevel.otel4s.metrics.Meter

import scala.scalajs.js
import scala.scalajs.js.UndefOr
import scala.scalajs.js.annotation.JSImport

/** @see
  *   [[https://opentelemetry.io/docs/specs/semconv/runtime/v8js-metrics/#metric-v8jsgcduration]]
  */
private object NodeGarbageCollectorMetrics {
  private val DefaultBucketBoundaries = BucketBoundaries(0.01, 0.1, 1, 10)

  def register[F[_]: Async: Meter]: Resource[F, Unit] =
    register(DefaultBucketBoundaries)

  def register[F[_]: Async: Meter](bucketBoundaries: BucketBoundaries): Resource[F, Unit] =
    for {
      histogram <- Meter[F]
        .histogram[Double](MetricNames.V8jsGcDuration)
        .withDescription(
          "Garbage collection duration by kind, one of major, minor, incremental or weakcb."
        )
        .withUnit("s")
        .withExplicitBucketBoundaries(bucketBoundaries)
        .create
        .toResource

      dispatcher <- Dispatcher.parallel[F](await = false)
      _ <- Resource.make(makeObserver[F](histogram, dispatcher))(observer => Async[F].delay(observer.disconnect()))
    } yield ()

  private def makeObserver[F[_]: Async](
      histogram: Histogram[F, Double],
      dispatcher: Dispatcher[F]
  ): F[PerformanceObserver] =
    Async[F].delay {
      val cb: js.Function1[PerformanceObserverEntryList, Unit] = list => {
        val entries = list.getEntries().toVector
        entries.foreach { entry =>
          gcKind(entry).foreach { kind =>
            dispatcher.unsafeRunAndForget(
              histogram.record(entry.duration / Const.MillisPerSecond, Attributes(Keys.V8jsGcType(kind)))
            )
          }
        }
      }

      val observer = new PerformanceObserver(cb)

      observer.observe(PerformanceObserverOptions(js.Array("gc")))
      observer
    }

  private def gcKind(entry: PerformanceEntry): Option[String] = {
    val detailKind = entry.detail.toOption.map(_.kind)
    val entryKind = entry.kind.toOption
    val kindCode = detailKind.orElse(entryKind)

    kindCode.flatMap(KindByCode.get)
  }

  private val KindByCode: Map[Int, String] = Map(
    PerfConstants.NODE_PERFORMANCE_GC_MAJOR -> "major",
    PerfConstants.NODE_PERFORMANCE_GC_MINOR -> "minor",
    PerfConstants.NODE_PERFORMANCE_GC_INCREMENTAL -> "incremental",
    PerfConstants.NODE_PERFORMANCE_GC_WEAKCB -> "weakcb"
  )

  private object MetricNames {
    val V8jsGcDuration = "v8js.gc.duration"
  }

  private object Const {
    val MillisPerSecond = 1000d
  }

  private object Keys {
    val V8jsGcType: AttributeKey[String] = AttributeKey("v8js.gc.type")
  }

  @js.native
  @JSImport("node:perf_hooks", "PerformanceObserver")
  private class PerformanceObserver(
      @annotation.unused callback: js.Function1[PerformanceObserverEntryList, Unit]
  ) extends js.Object {
    def observe(options: PerformanceObserverOptions): Unit = js.native
    def disconnect(): Unit = js.native
  }

  @js.native
  private trait PerformanceObserverEntryList extends js.Object {
    def getEntries(): js.Array[PerformanceEntry] = js.native
  }

  @js.native
  private trait PerformanceEntry extends js.Object {
    def duration: Double = js.native
    def kind: UndefOr[Int] = js.native
    def detail: UndefOr[PerformanceEntryDetail] = js.native
  }

  @js.native
  private trait PerformanceEntryDetail extends js.Object {
    def kind: Int = js.native
  }

  @js.native
  private trait PerformanceObserverOptions extends js.Object {
    def entryTypes: js.Array[String] = js.native
  }

  private object PerformanceObserverOptions {
    def apply(entryTypes: js.Array[String]): PerformanceObserverOptions =
      js.Dynamic.literal(entryTypes = entryTypes).asInstanceOf[PerformanceObserverOptions]
  }

  @js.native
  @JSImport("node:perf_hooks", "constants")
  private object PerfConstants extends js.Object {
    def NODE_PERFORMANCE_GC_MAJOR: Int = js.native
    def NODE_PERFORMANCE_GC_MINOR: Int = js.native
    def NODE_PERFORMANCE_GC_INCREMENTAL: Int = js.native
    def NODE_PERFORMANCE_GC_WEAKCB: Int = js.native
  }

}
