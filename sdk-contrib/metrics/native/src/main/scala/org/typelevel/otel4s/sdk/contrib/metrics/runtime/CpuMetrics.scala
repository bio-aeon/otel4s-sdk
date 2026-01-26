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

import cats.effect.Resource
import cats.effect.Sync
import cats.syntax.flatMap._
import cats.syntax.functor._
import org.typelevel.otel4s.metrics.Meter

import java.util.concurrent.TimeUnit

/** @see
  *   [[https://opentelemetry.io/docs/specs/semconv/runtime/jvm-metrics/#jvm-cpu]]
  */
private object CpuMetrics {
  private val NanosPerSecond = TimeUnit.SECONDS.toNanos(1)

  def register[F[_]: Sync: Meter]: Resource[F, Unit] =
    for {
      _ <- Meter[F]
        .observableUpDownCounter[Double]("scalanative.cpu.time")
        .withDescription("CPU time used by the process as reported by the process.")
        .withUnit("s")
        .createWithCallback { measurement =>
          for {
            cpuTimeNanos <- Sync[F].delay(getProcessCpuTime())
            _ <- Sync[F].whenA(cpuTimeNanos >= 0)(measurement.record(cpuTimeNanos.toDouble / NanosPerSecond))
          } yield ()
        }

      _ <- Meter[F]
        .observableUpDownCounter[Long]("scalanative.cpu.count")
        .withDescription("Number of processors available to the process.")
        .withUnit("{cpu}")
        .createWithCallback { measurement =>
          for {
            availableProcessors <- Sync[F].delay(Runtime.getRuntime.availableProcessors())
            _ <- measurement.record(availableProcessors.toLong)
          } yield ()
        }
    } yield ()

  private def getProcessCpuTime(): Long = {
    import scala.scalanative.meta.LinktimeInfo
    import scala.scalanative.unsafe._

    if (LinktimeInfo.isWindows) {
      import scala.scalanative.windows.MinWinBaseApi.FileTimeStruct
      import scala.scalanative.windows.MinWinBaseApiOps._
      import scala.scalanative.windows.ProcessThreadsApi

      val creationTime = stackalloc[FileTimeStruct]()
      val exitTime = stackalloc[FileTimeStruct]()
      val kernelTime = stackalloc[FileTimeStruct]()
      val userTime = stackalloc[FileTimeStruct]()
      val success = ProcessThreadsApi.GetProcessTimes(
        ProcessThreadsApi.GetCurrentProcess(),
        creationTime,
        exitTime,
        kernelTime,
        userTime
      )
      if (success) {
        val totalTime = kernelTime.fileTime + userTime.fileTime
        totalTime.toLong * FileTimeOps.EpochInterval
      } else {
        -1L
      }
    } else {
      import scala.scalanative.posix.sys.resource._
      import scala.scalanative.posix.sys.resourceOps._
      import scala.scalanative.posix.sys.timeOps._

      val usage = stackalloc[rusage]()
      if (getrusage(RUSAGE_SELF, usage) == 0) {
        val micros =
          usage.ru_utime.tv_sec * 1000 * 1000 + usage.ru_utime.tv_usec +
            usage.ru_stime.tv_sec * 1000 * 1000 + usage.ru_stime.tv_usec
        micros.toLong * 1000
      } else {
        -1L
      }
    }
  }

}
