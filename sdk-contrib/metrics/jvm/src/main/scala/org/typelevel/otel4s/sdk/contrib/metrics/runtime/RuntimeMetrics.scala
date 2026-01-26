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
import cats.effect.syntax.resource._
import cats.syntax.applicative._
import org.typelevel.otel4s.metrics.BucketBoundaries
import org.typelevel.otel4s.metrics.MeterProvider
import org.typelevel.otel4s.sdk.BuildInfo
import org.typelevel.otel4s.sdk.common.Diagnostic

object RuntimeMetrics {

  /** The runtime metrics configuration. */
  sealed trait Config {

    /** Whether class metrics are enabled. */
    def classMetricsEnabled: Boolean

    /** Whether CPU metrics are enabled. */
    def cpuMetricsEnabled: Boolean

    /** Whether GC metrics are enabled. */
    def gcMetricsEnabled: Boolean

    /** Whether memory pool metrics are enabled. */
    def memoryPoolMetricsEnabled: Boolean

    /** Whether thread metrics are enabled. */
    def threadMetricsEnabled: Boolean

    /** The histogram bucket boundaries for GC metrics. */
    def gcMetricsBucketBoundaries: BucketBoundaries

    /** Enables class metrics. */
    def withClassMetricsEnabled: Config

    /** Disables class metrics. */
    def withClassMetricsDisabled: Config

    /** Enables CPU metrics. */
    def withCpuMetricsEnabled: Config

    /** Disables CPU metrics. */
    def withCpuMetricsDisabled: Config

    /** Enables GC metrics. */
    def withGcMetricsEnabled: Config

    /** Sets the GC metrics bucket boundaries. */
    def withGcMetricsBucketBoundaries(boundaries: BucketBoundaries): Config

    /** Disables GC metrics. */
    def withGcMetricsDisabled: Config

    /** Enables memory pool metrics. */
    def withMemoryPoolMetricsEnabled: Config

    /** Disables memory pool metrics. */
    def withMemoryPoolMetricsDisabled: Config

    /** Enables thread metrics. */
    def withThreadMetricsEnabled: Config

    /** Disables thread metrics. */
    def withThreadMetricsDisabled: Config
  }

  object Config {
    private val DefaultGcBucketBoundaries = BucketBoundaries(0.01, 0.1, 1, 10)

    private val EnabledAll: Config = ConfigImpl(
      classMetricsEnabled = true,
      cpuMetricsEnabled = true,
      gcMetricsEnabled = true,
      memoryPoolMetricsEnabled = true,
      threadMetricsEnabled = true,
      gcMetricsBucketBoundaries = DefaultGcBucketBoundaries
    )

    private val DisabledAll: Config = ConfigImpl(
      classMetricsEnabled = false,
      cpuMetricsEnabled = false,
      gcMetricsEnabled = false,
      memoryPoolMetricsEnabled = false,
      threadMetricsEnabled = false,
      gcMetricsBucketBoundaries = DefaultGcBucketBoundaries
    )

    /** The configuration with all metrics enabled. */
    def enabledAll: Config = EnabledAll

    /** The configuration with all metrics disabled. */
    def disabledAll: Config = DisabledAll

    private final case class ConfigImpl(
        classMetricsEnabled: Boolean,
        cpuMetricsEnabled: Boolean,
        gcMetricsEnabled: Boolean,
        memoryPoolMetricsEnabled: Boolean,
        threadMetricsEnabled: Boolean,
        gcMetricsBucketBoundaries: BucketBoundaries
    ) extends Config {
      def withClassMetricsEnabled: Config =
        copy(classMetricsEnabled = true)

      def withClassMetricsDisabled: Config =
        copy(classMetricsEnabled = false)

      def withCpuMetricsEnabled: Config =
        copy(cpuMetricsEnabled = true)

      def withCpuMetricsDisabled: Config =
        copy(cpuMetricsEnabled = false)

      def withGcMetricsEnabled: Config =
        copy(gcMetricsEnabled = true)

      def withGcMetricsBucketBoundaries(boundaries: BucketBoundaries): Config =
        copy(gcMetricsBucketBoundaries = boundaries)

      def withGcMetricsDisabled: Config =
        copy(gcMetricsEnabled = false)

      def withMemoryPoolMetricsEnabled: Config =
        copy(memoryPoolMetricsEnabled = true)

      def withMemoryPoolMetricsDisabled: Config =
        copy(memoryPoolMetricsEnabled = false)

      def withThreadMetricsEnabled: Config =
        copy(threadMetricsEnabled = true)

      def withThreadMetricsDisabled: Config =
        copy(threadMetricsEnabled = false)
    }
  }

  /** Registers the JVM runtime metrics.
    *
    * The metrics lifecycle is managed by the returned [[cats.effect.Resource]].
    *
    * The metrics can be configured via [[RuntimeMetrics.Config]].
    *
    * Registers the following runtime metrics:
    *   - Class
    *     - `jvm.class.count`
    *     - `jvm.class.loaded`
    *     - `jvm.class.unloaded`
    *   - CPU
    *     - `jvm.cpu.count`
    *     - `jvm.cpu.recent_utilization`
    *     - `jvm.cpu.time`
    *   - GC
    *     - `jvm.gc.duration`
    *   - Memory
    *     - `jvm.memory.committed`
    *     - `jvm.memory.limit`
    *     - `jvm.memory.used`
    *     - `jvm.memory.used_after_last_gc`
    *   - Thread
    *     - `jvm.thread.count`
    */
  def register[F[_]: Async: MeterProvider: Diagnostic]: Resource[F, Unit] =
    register(Config.enabledAll)

  /** Registers the JVM runtime metrics using the provided config. */
  def register[F[_]: Async: MeterProvider: Diagnostic](config: Config): Resource[F, Unit] =
    MeterProvider[F]
      .meter("org.typelevel.otel4s.sdk.runtime")
      .withVersion(BuildInfo.version)
      .get
      .toResource
      .flatMap { implicit meter =>
        for {
          _ <- ClassMetrics.register.whenA(config.classMetricsEnabled)
          _ <- CpuMetrics.register.whenA(config.cpuMetricsEnabled)
          _ <- GarbageCollectorMetrics.register(config.gcMetricsBucketBoundaries).whenA(config.gcMetricsEnabled)
          _ <- MemoryPoolMetrics.register.whenA(config.memoryPoolMetricsEnabled)
          _ <- ThreadMetrics.register.whenA(config.threadMetricsEnabled)
        } yield ()
      }

}
