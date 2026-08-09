/*
 * Copyright 2023 Typelevel
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

package org.typelevel.otel4s.sdk.trace
package processor

import cats.Foldable
import cats.effect.Deferred
import cats.effect.IO
import cats.effect.Ref
import cats.effect.std.Queue
import cats.effect.testkit.TestControl
import cats.syntax.foldable._
import cats.syntax.traverse._
import munit.CatsEffectSuite
import munit.ScalaCheckEffectSuite
import org.scalacheck.Test
import org.scalacheck.effect.PropF
import org.typelevel.otel4s.sdk.TelemetryResource
import org.typelevel.otel4s.sdk.common.Diagnostic
import org.typelevel.otel4s.sdk.common.InstrumentationScope
import org.typelevel.otel4s.sdk.data.LimitedData
import org.typelevel.otel4s.sdk.trace.data.SpanData
import org.typelevel.otel4s.sdk.trace.data.StatusData
import org.typelevel.otel4s.sdk.trace.exporter.InMemorySpanExporter
import org.typelevel.otel4s.sdk.trace.exporter.SpanExporter
import org.typelevel.otel4s.sdk.trace.scalacheck.Arbitraries._
import org.typelevel.otel4s.trace.SpanContext
import org.typelevel.otel4s.trace.SpanKind
import org.typelevel.otel4s.trace.TraceFlags
import org.typelevel.otel4s.trace.TraceState

import scala.concurrent.duration._

class BatchSpanProcessorSuite extends CatsEffectSuite with ScalaCheckEffectSuite {

  private implicit val noopDiagnostic: Diagnostic[IO] = Diagnostic.noop

  test("show details in the name") {
    val exporter = new FailingExporter(
      "error-prone",
      new RuntimeException("something went wrong")
    )

    val expected =
      "BatchSpanProcessor{exporter=error-prone, scheduleDelay=5 seconds, exporterTimeout=30 seconds, maxQueueSize=2048, maxExportBatchSize=512}"

    BatchSpanProcessor.builder(exporter).build.use { processor =>
      IO(assertEquals(processor.name, expected))
    }
  }

  test("do nothing on start") {
    PropF.forAllF { (spans: List[SpanData]) =>
      for {
        exporter <- InMemorySpanExporter.create[IO](None)
        _ <- BatchSpanProcessor.builder(exporter).build.use { p =>
          spans.traverse_(_ => p.onStart(None, null))
        }
        exported <- exporter.finishedSpans
      } yield assert(exported.isEmpty)
    }
  }

  test("export only sampled spans on end") {
    PropF.forAllF { (spans: List[SpanData]) =>
      val sampled = spans.filter(_.spanContext.isSampled)

      for {
        exporter <- InMemorySpanExporter.create[IO](None)
        _ <- BatchSpanProcessor.builder(exporter).build.use { p =>
          spans.traverse_(span => p.onEnd(span))
        }
        exported <- exporter.finishedSpans
      } yield assertEquals(exported, sampled)
    }
  }

  test("do not rethrow export errors") {
    PropF.forAllF { (spans: List[SpanData]) =>
      val error = new RuntimeException("something went wrong")
      val exporter = new FailingExporter("error-prone", error)

      for {
        attempts <- BatchSpanProcessor.builder(exporter).build.use { p =>
          spans.traverse(span => p.onEnd(span).attempt)
        }
      } yield assertEquals(attempts, List.fill(spans.size)(Right(())))
    }
  }

  test("export a full batch without waiting for the schedule delay") {
    TestControl.executeEmbed {
      for {
        exportTimes <- Queue.unbounded[IO, FiniteDuration]
        exporter = new TimingExporter(exportTimes)
        elapsed <- BatchSpanProcessor
          .builder(exporter)
          .withScheduleDelay(1.hour)
          .withMaxQueueSize(1)
          .withMaxExportBatchSize(1)
          .build
          .use { processor =>
            for {
              // Give the worker time to enter its waiting state.
              _ <- IO.sleep(1.millis)
              started <- IO.monotonic
              _ <- processor.onEnd(sampledSpan)
              exported <- exportTimes.take
            } yield exported - started
          }
      } yield assertEquals(elapsed, Duration.Zero)
    }
  }

  test("export consecutive full batches without waiting for the schedule delay") {
    TestControl.executeEmbed {
      for {
        exportTimes <- Queue.unbounded[IO, FiniteDuration]
        exporter = new TimingExporter(exportTimes)
        elapsed <- BatchSpanProcessor
          .builder(exporter)
          .withScheduleDelay(1.hour)
          .withMaxQueueSize(1)
          .withMaxExportBatchSize(1)
          .build
          .use { processor =>
            def exportBatch: IO[FiniteDuration] =
              for {
                // Give the worker time to enter its next waiting state.
                _ <- IO.sleep(1.millis)
                started <- IO.monotonic
                _ <- processor.onEnd(sampledSpan)
                exported <- exportTimes.take
              } yield exported - started

            for {
              first <- exportBatch
              second <- exportBatch
            } yield (first, second)
          }
      } yield {
        assertEquals(elapsed._1, Duration.Zero)
        assertEquals(elapsed._2, Duration.Zero)
      }
    }
  }

  test("do not export concurrently when force flush overlaps the worker") {
    TestControl.executeEmbed {
      for {
        firstExportStarted <- Deferred[IO, Unit]
        releaseFirstExport <- Deferred[IO, Unit]
        overlappingExport <- Deferred[IO, Unit]
        exportCount <- Ref.of[IO, Int](0)
        exporter = new CoordinatedExporter(
          firstExportStarted,
          releaseFirstExport,
          overlappingExport,
          exportCount
        )
        result <- BatchSpanProcessor
          .builder(exporter)
          .withScheduleDelay(1.hour)
          .withMaxQueueSize(1)
          .withMaxExportBatchSize(1)
          .build
          .use { processor =>
            for {
              _ <- processor.onEnd(sampledSpan)
              _ <- firstExportStarted.get
              flush <- processor.forceFlush.start
              _ <- IO.sleep(1.second)
              overlap <- overlappingExport.tryGet
              _ <- releaseFirstExport.complete(())
              _ <- flush.joinWithNever
              count <- exportCount.get
            } yield (overlap, count)
          }
      } yield {
        assertEquals(result._1, None)
        assertEquals(result._2, 1)
      }
    }
  }

  override protected def scalaCheckTestParameters: Test.Parameters =
    super.scalaCheckTestParameters
      .withMinSuccessfulTests(10)
      .withMaxSize(10)

  private val sampledSpan: SpanData =
    SpanData(
      name = "sampled",
      spanContext = SpanContext(
        traceId = SpanContext.TraceId.fromLongs(1L, 1L),
        spanId = SpanContext.SpanId.fromLong(1L),
        traceFlags = TraceFlags.Sampled,
        traceState = TraceState.empty,
        remote = false
      ),
      parentSpanContext = None,
      kind = SpanKind.Internal,
      startTimestamp = Duration.Zero,
      endTimestamp = Some(Duration.Zero),
      status = StatusData.Unset,
      attributes = LimitedData.attributes(0, 0),
      events = LimitedData.vector(0),
      links = LimitedData.vector(0),
      instrumentationScope = InstrumentationScope.empty,
      resource = TelemetryResource.empty
    )

  private class TimingExporter(exportTimes: Queue[IO, FiniteDuration]) extends SpanExporter.Unsealed[IO] {
    val name: String = "timing"

    def exportSpans[G[_]: Foldable](spans: G[SpanData]): IO[Unit] =
      IO.monotonic.flatMap(exportTimes.offer)

    def flush: IO[Unit] =
      IO.unit
  }

  private class CoordinatedExporter(
      firstExportStarted: Deferred[IO, Unit],
      releaseFirstExport: Deferred[IO, Unit],
      overlappingExport: Deferred[IO, Unit],
      exportCount: Ref[IO, Int]
  ) extends SpanExporter.Unsealed[IO] {
    val name: String = "coordinated"

    def exportSpans[G[_]: Foldable](spans: G[SpanData]): IO[Unit] =
      exportCount.updateAndGet(_ + 1).flatMap {
        case 1 => firstExportStarted.complete(()).void *> releaseFirstExport.get
        case _ => overlappingExport.complete(()).void
      }

    def flush: IO[Unit] =
      IO.unit
  }

  private class FailingExporter(
      exporterName: String,
      onExport: Throwable
  ) extends SpanExporter.Unsealed[IO] {
    def name: String = exporterName

    def exportSpans[G[_]: Foldable](spans: G[SpanData]): IO[Unit] =
      IO.raiseError(onExport)

    def flush: IO[Unit] =
      IO.unit
  }

}
