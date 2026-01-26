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
import org.typelevel.otel4s.metrics.MeterProvider
import org.typelevel.otel4s.sdk.BuildInfo

import scala.concurrent.duration.FiniteDuration

object RuntimeMetrics {

  /** The runtime metrics configuration. */
  sealed trait Config {

    /** Whether process CPU metrics are enabled. */
    def processCpuMetricsEnabled: Boolean

    /** Whether process memory metrics are enabled. */
    def processMemoryMetricsEnabled: Boolean

    /** Whether GC metrics are enabled. */
    def nodeGcMetricsEnabled: Boolean

    /** Whether Node event loop metrics are enabled. */
    def nodeEventLoopMetricsEnabled: Boolean

    /** Whether Node memory metrics are enabled. */
    def nodeMemoryMetricsEnabled: Boolean

    /** The monitoring precision for event loop delay metrics. */
    def eventLoopMonitoringPrecision: FiniteDuration

    /** Enables process CPU metrics. */
    def withProcessCpuMetricsEnabled: Config

    /** Disables process CPU metrics. */
    def withProcessCpuMetricsDisabled: Config

    /** Enables process memory metrics. */
    def withProcessMemoryMetricsEnabled: Config

    /** Disables process memory metrics. */
    def withProcessMemoryMetricsDisabled: Config

    /** Enables GC metrics. */
    def withNodeGcMetricsEnabled: Config

    /** Disables GC metrics. */
    def withNodeGcMetricsDisabled: Config

    /** Enables Node event loop metrics. */
    def withNodeEventLoopMetricsEnabled: Config

    /** Disables Node event loop metrics. */
    def withNodeEventLoopMetricsDisabled: Config

    /** Enables Node memory metrics. */
    def withNodeMemoryMetricsEnabled: Config

    /** Disables Node memory metrics. */
    def withNodeMemoryMetricsDisabled: Config

    /** Sets the monitoring precision for event loop delay metrics. */
    def withEventLoopMonitoringPrecision(precision: FiniteDuration): Config
  }

  object Config {
    private val EnabledAll: Config = ConfigImpl(
      processCpuMetricsEnabled = true,
      processMemoryMetricsEnabled = true,
      nodeGcMetricsEnabled = true,
      nodeEventLoopMetricsEnabled = true,
      nodeMemoryMetricsEnabled = true,
      eventLoopMonitoringPrecision = NodeEventLoopMetrics.DefaultMonitoringPrecision
    )

    private val DisabledAll: Config = ConfigImpl(
      processCpuMetricsEnabled = false,
      processMemoryMetricsEnabled = false,
      nodeGcMetricsEnabled = false,
      nodeEventLoopMetricsEnabled = false,
      nodeMemoryMetricsEnabled = false,
      eventLoopMonitoringPrecision = NodeEventLoopMetrics.DefaultMonitoringPrecision
    )

    /** The configuration with all metrics enabled. */
    def enabledAll: Config = EnabledAll

    /** The configuration with all metrics disabled. */
    def disabledAll: Config = DisabledAll

    private final case class ConfigImpl(
        processCpuMetricsEnabled: Boolean,
        processMemoryMetricsEnabled: Boolean,
        nodeGcMetricsEnabled: Boolean,
        nodeEventLoopMetricsEnabled: Boolean,
        nodeMemoryMetricsEnabled: Boolean,
        eventLoopMonitoringPrecision: FiniteDuration
    ) extends Config {
      def withProcessCpuMetricsEnabled: Config =
        copy(processCpuMetricsEnabled = true)

      def withProcessCpuMetricsDisabled: Config =
        copy(processCpuMetricsEnabled = false)

      def withProcessMemoryMetricsEnabled: Config =
        copy(processMemoryMetricsEnabled = true)

      def withProcessMemoryMetricsDisabled: Config =
        copy(processMemoryMetricsEnabled = false)

      def withNodeGcMetricsEnabled: Config =
        copy(nodeGcMetricsEnabled = true)

      def withNodeGcMetricsDisabled: Config =
        copy(nodeGcMetricsEnabled = false)

      def withNodeEventLoopMetricsEnabled: Config =
        copy(nodeEventLoopMetricsEnabled = true)

      def withNodeEventLoopMetricsDisabled: Config =
        copy(nodeEventLoopMetricsEnabled = false)

      def withNodeMemoryMetricsEnabled: Config =
        copy(nodeMemoryMetricsEnabled = true)

      def withNodeMemoryMetricsDisabled: Config =
        copy(nodeMemoryMetricsEnabled = false)

      def withEventLoopMonitoringPrecision(precision: FiniteDuration): Config =
        copy(eventLoopMonitoringPrecision = precision)
    }
  }

  /** Registers the runtime metrics.
    *
    * The metrics lifecycle is managed by the returned [[cats.effect.Resource]].
    *
    * The metrics can be configured via [[RuntimeMetrics.Config]].
    *
    * Registers the runtime metrics:
    *   - Process CPU
    *     - `process.cpu.time`
    *     - `process.cpu.utilization`
    *   - Process memory
    *     - `process.memory.usage`
    *   - V8 GC
    *     - `v8js.gc.duration`
    *   - Node.js event loop
    *     - `nodejs.eventloop.time`
    *     - `nodejs.eventloop.utilization`
    *     - `nodejs.eventloop.delay.min`
    *     - `nodejs.eventloop.delay.max`
    *     - `nodejs.eventloop.delay.mean`
    *     - `nodejs.eventloop.delay.stddev`
    *     - `nodejs.eventloop.delay.p50`
    *     - `nodejs.eventloop.delay.p90`
    *     - `nodejs.eventloop.delay.p99`
    *   - V8 memory
    *     - `v8js.memory.heap.limit`
    *     - `v8js.memory.heap.used`
    *     - `v8js.heap.space.available_size`
    *     - `v8js.heap.space.physical_size`
    */
  def register[F[_]: Async: MeterProvider]: Resource[F, Unit] =
    register(Config.enabledAll)

  /** Registers the runtime metrics using the provided config. */
  def register[F[_]: Async: MeterProvider](config: Config): Resource[F, Unit] =
    MeterProvider[F]
      .meter("org.typelevel.otel4s.sdk.runtime")
      .withVersion(BuildInfo.version)
      .get
      .toResource
      .flatMap { implicit meter =>
        for {
          _ <- CpuMetrics.register[F].whenA(config.processCpuMetricsEnabled)
          _ <- MemoryMetrics.register[F].whenA(config.processMemoryMetricsEnabled)
          _ <- NodeGarbageCollectorMetrics.register[F].whenA(config.nodeGcMetricsEnabled)
          _ <- NodeEventLoopMetrics
            .register[F](config.eventLoopMonitoringPrecision)
            .whenA(config.nodeEventLoopMetricsEnabled)
          _ <- NodeMemoryMetrics.register[F].whenA(config.nodeMemoryMetricsEnabled)
        } yield ()
      }

}
