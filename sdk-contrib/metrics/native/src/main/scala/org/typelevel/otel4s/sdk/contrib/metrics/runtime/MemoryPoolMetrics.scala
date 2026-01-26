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

import cats.Monad
import cats.effect.Resource
import cats.effect.Sync
import cats.effect.syntax.resource._
import cats.syntax.flatMap._
import cats.syntax.foldable._
import cats.syntax.functor._
import org.typelevel.otel4s.Attributes
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.metrics.ObservableMeasurement
import org.typelevel.otel4s.semconv.attributes.JvmAttributes

import java.lang.management.ManagementFactory
import java.lang.management.MemoryUsage

/** @see
  *   [[https://opentelemetry.io/docs/specs/semconv/runtime/jvm-metrics/#jvm-memory]]
  */
private object MemoryPoolMetrics {

  def register[F[_]: Sync: Meter]: Resource[F, Unit] =
    Sync[F].delay(ManagementFactory.getMemoryMXBean).toResource.flatMap { bean =>
      val beans = List(
        (
          bean.getHeapMemoryUsage,
          JvmAttributes.JvmMemoryTypeValue.Heap,
          ManagementFactory.getMemoryManagerMXBeans.get(0).getName
        )
      )

      Meter[F].batchCallback.of(
        Meter[F]
          .observableUpDownCounter[Long]("scalanative.memory.used")
          .withDescription("Measure of memory used.")
          .withUnit("By")
          .createObserver,
        Meter[F]
          .observableUpDownCounter[Long]("scalanative.memory.committed")
          .withDescription("Measure of memory committed.")
          .withUnit("By")
          .createObserver,
        Meter[F]
          .observableUpDownCounter[Long]("scalanative.memory.limit")
          .withDescription("Measure of max obtainable memory.")
          .withUnit("By")
          .createObserver,
      ) { (memoryUsed, memoryCommitted, memoryLimit) =>
        for {
          _ <- record(memoryUsed, beans, _.getUsed)
          _ <- record(memoryCommitted, beans, _.getCommitted)
          _ <- record(memoryLimit, beans, _.getMax)
        } yield ()
      }
    }

  private def record[F[_]: Monad](
      measurement: ObservableMeasurement[F, Long],
      beans: List[(MemoryUsage, JvmAttributes.JvmMemoryTypeValue, String)],
      focusValue: MemoryUsage => Long
  ): F[Unit] =
    beans.traverse_ { case (bean, memoryType, name) =>
      val attributes = Attributes(
        JvmAttributes.JvmMemoryPoolName(name),
        JvmAttributes.JvmMemoryType(memoryType.value)
      )

      // could be null in some cases
      Option(bean) match {
        case Some(usage) =>
          val value = focusValue(usage)
          if (value != -1L) {
            measurement.record(value, attributes)
          } else {
            Monad[F].unit
          }

        case None =>
          Monad[F].unit
      }
    }

}
