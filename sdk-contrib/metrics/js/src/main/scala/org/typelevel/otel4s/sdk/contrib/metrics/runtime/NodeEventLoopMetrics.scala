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
import cats.effect.Ref
import cats.effect.Resource
import cats.syntax.flatMap._
import cats.syntax.functor._
import org.typelevel.otel4s.AttributeKey
import org.typelevel.otel4s.Attributes
import org.typelevel.otel4s.metrics.Meter

import scala.concurrent.duration._
import scala.scalajs.js
import scala.scalajs.js.UndefOr
import scala.scalajs.js.annotation.JSImport

/** @see
  *   [[https://opentelemetry.io/docs/specs/semconv/runtime/nodejs-metrics/]]
  */
private object NodeEventLoopMetrics {

  val DefaultMonitoringPrecision: FiniteDuration = 10.millis

  def register[F[_]: Async: Meter](monitoringPrecision: FiniteDuration): Resource[F, Unit] =
    for {
      lastElu <- Resource.eval(Ref.of[F, EventLoopUtilization](PerfHooks.performance.eventLoopUtilization()))
      histogram <- Resource.make(
        Async[F].delay(
          PerfHooks.monitorEventLoopDelay(EventLoopMonitorOptions(resolutionMillis(monitoringPrecision)))
        )
      )(h => Async[F].delay(h.disable()))
      _ <- Resource.eval(Async[F].delay(histogram.enable()))

      _ <- registerEventLoopTime[F]
      _ <- registerEventLoopUtilization[F](lastElu)
      _ <- registerEventLoopDelay[F](histogram)
    } yield ()

  private def registerEventLoopTime[F[_]: Async: Meter]: Resource[F, Unit] =
    Meter[F]
      .observableCounter[Double](MetricNames.EventLoopTime)
      .withDescription("Cumulative duration of time the event loop has been in each state.")
      .withUnit("s")
      .createWithCallback { timeCounter =>
        for {
          data <- Async[F].delay(PerfHooks.performance.eventLoopUtilization())
          _ <- timeCounter.record(data.active / Const.MillisPerSecond, Attributes(Keys.EventLoopState("active")))
          _ <- timeCounter.record(data.idle / Const.MillisPerSecond, Attributes(Keys.EventLoopState("idle")))
        } yield ()
      }
      .void

  private def registerEventLoopUtilization[F[_]: Async: Meter](
      lastElu: Ref[F, EventLoopUtilization]
  ): Resource[F, Unit] =
    Meter[F]
      .observableGauge[Double](MetricNames.EventLoopUtilization)
      .withDescription("Event loop utilization.")
      .withUnit("1")
      .createWithCallback { measurement =>
        for {
          current <- Async[F].delay(PerfHooks.performance.eventLoopUtilization())
          previous <- lastElu.getAndSet(current)
          delta <- Async[F].delay(PerfHooks.performance.eventLoopUtilization(current, previous))
          _ <- measurement.record(delta.utilization)
        } yield ()
      }
      .void

  private def registerEventLoopDelay[F[_]: Async: Meter](
      histogram: IntervalHistogram
  ): Resource[F, Unit] =
    Meter[F].batchCallback.of(
      Meter[F]
        .observableGauge[Double](MetricNames.EventLoopDelayMin)
        .withDescription("Event loop minimum delay.")
        .withUnit("s")
        .createObserver,
      Meter[F]
        .observableGauge[Double](MetricNames.EventLoopDelayMax)
        .withDescription("Event loop maximum delay.")
        .withUnit("s")
        .createObserver,
      Meter[F]
        .observableGauge[Double](MetricNames.EventLoopDelayMean)
        .withDescription("Event loop mean delay.")
        .withUnit("s")
        .createObserver,
      Meter[F]
        .observableGauge[Double](MetricNames.EventLoopDelayStddev)
        .withDescription("Event loop standard deviation delay.")
        .withUnit("s")
        .createObserver,
      Meter[F]
        .observableGauge[Double](MetricNames.EventLoopDelayP50)
        .withDescription("Event loop 50 percentile delay.")
        .withUnit("s")
        .createObserver,
      Meter[F]
        .observableGauge[Double](MetricNames.EventLoopDelayP90)
        .withDescription("Event loop 90 percentile delay.")
        .withUnit("s")
        .createObserver,
      Meter[F]
        .observableGauge[Double](MetricNames.EventLoopDelayP99)
        .withDescription("Event loop 99 percentile delay.")
        .withUnit("s")
        .createObserver
    ) { (min, max, mean, stddev, p50, p90, p99) =>
      Async[F].defer {
        if (histogram.count < 5) {
          Async[F].unit
        } else {
          for {
            _ <- min.record(safe(histogram.min) / Const.NanosPerSecond)
            _ <- max.record(safe(histogram.max) / Const.NanosPerSecond)
            _ <- mean.record(safe(histogram.mean) / Const.NanosPerSecond)
            _ <- stddev.record(safe(histogram.stddev) / Const.NanosPerSecond)
            _ <- p50.record(safe(histogram.percentile(50)) / Const.NanosPerSecond)
            _ <- p90.record(safe(histogram.percentile(90)) / Const.NanosPerSecond)
            _ <- p99.record(safe(histogram.percentile(99)) / Const.NanosPerSecond)
            _ <- Async[F].delay(histogram.reset())
          } yield ()
        }
      }
    }

  private def safe(value: Double): Double =
    if (value.isNaN) 0d else value

  private def resolutionMillis(precision: FiniteDuration): Int =
    math.max(precision.toMillis.toInt, 1)

  private object MetricNames {
    val EventLoopTime = "nodejs.eventloop.time"
    val EventLoopUtilization = "nodejs.eventloop.utilization"
    val EventLoopDelayMin = "nodejs.eventloop.delay.min"
    val EventLoopDelayMax = "nodejs.eventloop.delay.max"
    val EventLoopDelayMean = "nodejs.eventloop.delay.mean"
    val EventLoopDelayStddev = "nodejs.eventloop.delay.stddev"
    val EventLoopDelayP50 = "nodejs.eventloop.delay.p50"
    val EventLoopDelayP90 = "nodejs.eventloop.delay.p90"
    val EventLoopDelayP99 = "nodejs.eventloop.delay.p99"
  }

  private object Const {
    val MillisPerSecond = 1000d
    val NanosPerSecond = 1000000000d
  }

  private object Keys {
    val EventLoopState: AttributeKey[String] = AttributeKey("nodejs.eventloop.state")
  }

  @js.native
  private trait EventLoopMonitorOptions extends js.Object {
    def resolution: Int = js.native
  }

  private object EventLoopMonitorOptions {
    def apply(resolution: Int): EventLoopMonitorOptions =
      js.Dynamic.literal(resolution = resolution).asInstanceOf[EventLoopMonitorOptions]
  }

  @js.native
  @JSImport("node:perf_hooks", JSImport.Namespace)
  private object PerfHooks extends js.Object {
    def performance: Performance = js.native
    def monitorEventLoopDelay(options: EventLoopMonitorOptions): IntervalHistogram = js.native
  }

  @js.native
  private trait Performance extends js.Object {
    def eventLoopUtilization(
        utilization1: UndefOr[EventLoopUtilization] = js.undefined,
        utilization2: UndefOr[EventLoopUtilization] = js.undefined
    ): EventLoopUtilization = js.native
  }

  @js.native
  private trait EventLoopUtilization extends js.Object {
    def idle: Double = js.native
    def active: Double = js.native
    def utilization: Double = js.native
  }

  @js.native
  private trait IntervalHistogram extends js.Object {
    def min: Double = js.native
    def max: Double = js.native
    def mean: Double = js.native
    def stddev: Double = js.native
    def percentile(percentile: Double): Double = js.native
    def count: Double = js.native
    def reset(): Unit = js.native
    def enable(): Unit = js.native
    def disable(): Unit = js.native
  }

}
