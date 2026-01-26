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
import org.typelevel.otel4s.metrics.ObservableMeasurement

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

private object CpuMetrics {

  def register[F[_]: Async: Meter]: Resource[F, Unit] =
    for {
      state <- Resource.eval(Ref.of[F, Option[ProcessCpuState]](None))
      _ <- Meter[F].batchCallback.of(
        Meter[F]
          .observableCounter[Double](MetricNames.ProcessCpuTime)
          .withDescription("Total CPU seconds broken down by different states.")
          .withUnit("s")
          .createObserver,
        Meter[F]
          .observableGauge[Double](MetricNames.ProcessCpuUtilization)
          .withDescription(
            "Difference in process.cpu.time since the last measurement, divided by the elapsed time and number of CPUs available to the process."
          )
          .withUnit("1")
          .createObserver
      ) { (processCpuTime, processCpuUtilization) =>
        for {
          current <- readProcessCpuState[F]
          previous <- state.getAndSet(Some(current))
          utilization = processCpuUtilizationData(current, previous)
          _ <- recordProcessCpu(processCpuTime, processCpuUtilization, current, utilization)
        } yield ()
      }
    } yield ()

  private def readProcessCpuState[F[_]: Async]: F[ProcessCpuState] =
    Async[F].delay {
      val usage = NodeProcess.cpuUsage()
      val now = js.Date.now()
      ProcessCpuState(
        user = usage.user / Const.MicrosPerSecond,
        system = usage.system / Const.MicrosPerSecond,
        timestampMs = now
      )
    }

  private def processCpuUtilizationData(
      current: ProcessCpuState,
      previous: Option[ProcessCpuState]
  ): ProcessCpuUtilization =
    previous match {
      case Some(prev) =>
        val elapsedSeconds = (current.timestampMs - prev.timestampMs) / Const.MillisPerSecond
        val cpuCount = math.max(NodeOs.cpus().length, 1)

        if (elapsedSeconds <= 0) {
          ProcessCpuUtilization(0d, 0d)
        } else {
          val userDelta = math.max(0d, current.user - prev.user)
          val systemDelta = math.max(0d, current.system - prev.system)

          ProcessCpuUtilization(
            user = userDelta / elapsedSeconds / cpuCount,
            system = systemDelta / elapsedSeconds / cpuCount
          )
        }

      case None =>
        ProcessCpuUtilization(0d, 0d)
    }

  private def recordProcessCpu[F[_]: Async](
      processCpuTime: ObservableMeasurement[F, Double],
      processCpuUtilization: ObservableMeasurement[F, Double],
      current: ProcessCpuState,
      utilization: ProcessCpuUtilization
  ): F[Unit] = {
    val userAttributes = processCpuAttributes(Const.ProcessCpuStateUser)
    val systemAttributes = processCpuAttributes(Const.ProcessCpuStateSystem)

    for {
      _ <- processCpuTime.record(current.user, userAttributes)
      _ <- processCpuTime.record(current.system, systemAttributes)
      _ <- processCpuUtilization.record(utilization.user, userAttributes)
      _ <- processCpuUtilization.record(utilization.system, systemAttributes)
    } yield ()
  }

  private def processCpuAttributes(state: String): Attributes =
    Attributes(Keys.CpuMode(state))

  private final case class ProcessCpuState(
      user: Double,
      system: Double,
      timestampMs: Double
  )

  private final case class ProcessCpuUtilization(
      user: Double,
      system: Double
  )

  private object MetricNames {
    val ProcessCpuTime = "process.cpu.time"
    val ProcessCpuUtilization = "process.cpu.utilization"
  }

  private object Const {
    val MillisPerSecond = 1000d
    val MicrosPerSecond = 1000000d

    val ProcessCpuStateUser = "user"
    val ProcessCpuStateSystem = "system"
  }

  private object Keys {
    val CpuMode: AttributeKey[String] = AttributeKey("cpu.mode")
  }

  @js.native
  @JSImport("os", JSImport.Namespace)
  private object NodeOs extends js.Object {
    def cpus(): js.Array[CpuInfo] = js.native
  }

  @js.native
  private trait CpuInfo extends js.Object

  @js.native
  @JSImport("process", JSImport.Namespace)
  private object NodeProcess extends js.Object {
    def cpuUsage(): ProcessCpuUsageInfo = js.native
  }

  @js.native
  private trait ProcessCpuUsageInfo extends js.Object {
    def user: Double = js.native
    def system: Double = js.native
  }

}
