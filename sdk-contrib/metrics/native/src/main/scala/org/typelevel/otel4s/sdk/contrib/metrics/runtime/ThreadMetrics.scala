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
import cats.effect.syntax.resource._
import cats.syntax.flatMap._
import cats.syntax.functor._
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.metrics.ObservableMeasurement
import org.typelevel.otel4s.semconv.attributes.JvmAttributes

import java.lang.management.ManagementFactory
import java.lang.management.ThreadMXBean

/** @see
  *   [[https://opentelemetry.io/docs/specs/semconv/runtime/jvm-metrics/#jvm-threads]]
  */
private object ThreadMetrics {

  def register[F[_]: Sync: Meter]: Resource[F, Unit] =
    for {
      bean <- Sync[F].delay(ManagementFactory.getThreadMXBean).toResource
      _ <- Meter[F]
        .observableUpDownCounter[Long]("scalanative.thread.count")
        .withDescription("Number of executing platform threads.")
        .withUnit("{thread}")
        .createWithCallback(callback[F](bean))
    } yield ()

  private def callback[F[_]: Sync](
      bean: ThreadMXBean
  )(measurement: ObservableMeasurement[F, Long]): F[Unit] =
    for {
      threadCount <- Sync[F].delay(bean.getThreadCount)
      daemonThreadCount <- Sync[F].delay(bean.getDaemonThreadCount)
      _ <- measurement.record(daemonThreadCount, JvmAttributes.JvmThreadDaemon(true))
      _ <- measurement.record(threadCount.toLong - daemonThreadCount, JvmAttributes.JvmThreadDaemon(false))
    } yield ()

}
