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

package org.typelevel.otel4s.sdk.testkit.metrics

import cats.effect.IO
import munit.CatsEffectSuite
import munit.Location
import munit.TestOptions
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.Attributes
import org.typelevel.otel4s.sdk.metrics.data.MetricData

class MetricExpectationsFormattingSuite extends CatsEffectSuite {

  testkitTest("format renders not found mismatches with clues and available metrics") { testkit =>
    for {
      meter <- testkit.meterProvider.get("test")
      counter <- meter.counter[Long]("service.counter").create
      _ <- counter.add(1L)
      metrics <- testkit.collectMetrics
    } yield {
      val rendered = formatFailures(metrics, MetricExpectation.gauge[Long]("service.gauge").clue("missing gauge"))
      assertEquals(
        rendered,
        """Metric expectations failed:
          |1. [missing gauge] no metric matched the expectation; available metrics: [service.counter]""".stripMargin
      )
    }
  }

  testkitTest("format renders closest metric type mismatches as bulleted output") { testkit =>
    for {
      meter <- testkit.meterProvider.get("test")
      counter <- meter.counter[Long]("service.counter").create
      _ <- counter.add(1L)
      metrics <- testkit.collectMetrics
    } yield {
      val rendered = formatFailures(metrics, MetricExpectation.gauge[Long]("service.counter"))
      assert(rendered.contains("closest metric 'service.counter' mismatched:"))
      assert(rendered.contains("type mismatch: expected 'Gauge[Long]', got 'Sum[Long]'"))
    }
  }

  testkitTest("format preserves multiple metric-level mismatches in order") { testkit =>
    for {
      meter <- testkit.meterProvider.get("test")
      counter <- meter.counter[Long]("service.counter").create
      _ <- counter.add(1L)
      metrics <- testkit.collectMetrics
    } yield {
      val rendered = formatFailures(
        metrics,
        MetricExpectation
          .sum[Long]("service.counter")
          .description("requests processed")
          .unit("ms")
      )

      assert(rendered.contains("description mismatch: expected 'requests processed'"))
      assert(rendered.contains("unit mismatch: expected 'ms'"))
    }
  }

  testkitTest("format shows metric, point-set, and point clues for nested point mismatches") { testkit =>
    for {
      meter <- testkit.meterProvider.get("test")
      counter <- meter.counter[Long]("service.counter").create
      _ <- counter.add(1L, Attributes(Attribute("region", "eu")))
      metrics <- testkit.collectMetrics
    } yield {
      val rendered = formatFailures(
        metrics,
        MetricExpectation
          .sum[Long]("service.counter")
          .clue("counter requirement")
          .points(
            PointSetExpectation
              .exists(
                PointExpectation
                  .numeric(1L)
                  .attributesSubset(Attribute("region", "us"))
                  .clue("US point")
              )
              .clue("regional points")
          )
      )

      assert(rendered.contains("[counter requirement] closest metric 'service.counter' mismatched:"))
      assert(rendered.contains("points mismatch [regional points]"))
      assert(rendered.contains("missing expected point [US point]"))
      assert(rendered.contains("attribute mismatch for 'region'"))
    }
  }

  testkitTest("format renders distinct matching failures") { testkit =>
    for {
      meter <- testkit.meterProvider.get("test")
      counter <- meter.counter[Long]("service.counter").create
      _ <- counter.add(1L)
      metrics <- testkit.collectMetrics
    } yield {
      val rendered = MetricExpectations.checkAllDistinct(
        metrics,
        MetricExpectation.sum[Long]("service.counter").value(1L),
        MetricExpectation.sum[Long]("service.counter").value(1L)
      ) match {
        case Left(mismatches) => MetricExpectations.format(mismatches)
        case Right(_)         => fail("expected mismatches, got success")
      }

      assertEquals(
        rendered,
        """Metric expectations failed:
          |1. no distinct metric remained for the expectation; matched metrics: [service.counter]""".stripMargin
      )
    }
  }

  private def testkitTest[A](options: TestOptions)(body: MetricsTestkit[IO] => IO[A])(implicit loc: Location): Unit =
    test(options)(MetricsTestkit.inMemory[IO]().use(body))

  private def formatFailures(metrics: List[MetricData], expectations: MetricExpectation*): String =
    MetricExpectations.checkAll(metrics, expectations.toList) match {
      case Left(mismatches) => MetricExpectations.format(mismatches)
      case Right(_)         => fail("expected mismatches, got success")
    }
}
