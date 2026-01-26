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

import cats.effect.IO
import munit.CatsEffectSuite
import org.typelevel.otel4s.metrics.MeterProvider
import org.typelevel.otel4s.sdk.metrics.data.MetricData
import org.typelevel.otel4s.sdk.testkit.metrics.MetricsTestkit
import org.typelevel.otel4s.semconv.MetricSpec
import org.typelevel.otel4s.semconv.Requirement
import org.typelevel.otel4s.semconv.metrics.JvmMetrics

import scala.concurrent.duration._

class RuntimeMetricsSuite extends CatsEffectSuite {

  test("specification check") {
    val specs = List(
      // cpu
      JvmMetrics.CpuTime,
      JvmMetrics.CpuCount,
      // gc
      JvmMetrics.GcDuration,
      // memory
      JvmMetrics.MemoryUsed,
      JvmMetrics.MemoryCommitted,
      JvmMetrics.MemoryLimit,
      // thread
      JvmMetrics.ThreadCount
    )

    val config = RuntimeMetrics.Config.enabledAll.withGcMetricsRefreshRate(100.millis)

    MetricsTestkit.inMemory[IO]().use { testkit =>
      implicit val meterProvider: MeterProvider[IO] = testkit.meterProvider
      RuntimeMetrics.register[IO](config).surround {
        for {
          _ <- IO.delay(System.gc())
          _ <- IO.sleep(500.millis)
          metrics <- testkit.collectMetrics
        } yield specs.foreach(spec => specTest(metrics, spec))
      }
    }
  }

  private def specTest(metrics: List[MetricData], spec: MetricSpec): Unit = {
    val specName = spec.name.replace("jvm.", "scalanative.")
    val metric = metrics.find(_.name == specName)
    assert(
      metric.isDefined,
      s"$specName metric is missing. Available [${metrics.map(_.name).mkString(", ")}]",
    )

    val clue = s"[$specName] has a mismatched property"

    // since we are trying to follow the JVM semantics, we need to adjust the description a bit
    val description = spec.description
      .replace("JVM", "process")
      .replace("Java virtual machine", "process")

    metric.foreach { md =>
      assertEquals(md.name, specName, clue)
      assertEquals(md.description, Some(description), clue)
      assertEquals(md.unit, Some(spec.unit), clue)

      val required = spec.attributeSpecs
        .filter(_.requirement.level == Requirement.Level.Required)
        .map(_.key)
        .toSet

      val current = md.data.points.toVector
        .flatMap(_.attributes.map(_.key))
        .filter(key => required.contains(key))
        .toSet

      assertEquals(current, required, clue)
    }
  }

}
