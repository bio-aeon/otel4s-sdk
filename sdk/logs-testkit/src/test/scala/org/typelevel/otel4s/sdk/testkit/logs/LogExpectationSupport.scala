/*
 * Copyright 2025 Typelevel
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

package org.typelevel.otel4s.sdk.testkit.logs

import cats.data.NonEmptyList
import cats.effect.IO
import munit.CatsEffectSuite
import munit.Location
import munit.TestOptions
import org.typelevel.otel4s.AnyValue
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.Attributes
import org.typelevel.otel4s.logs.Severity
import org.typelevel.otel4s.sdk.TelemetryResource
import org.typelevel.otel4s.sdk.common.InstrumentationScope
import org.typelevel.otel4s.sdk.context.Context
import org.typelevel.otel4s.sdk.context.TraceContext
import org.typelevel.otel4s.sdk.data.LimitedData
import org.typelevel.otel4s.sdk.logs.data.LogRecordData
import scodec.bits.ByteVector

import scala.concurrent.duration._
import scala.concurrent.duration.FiniteDuration
import scala.util.chaining._

trait LogExpectationSupport extends CatsEffectSuite {

  protected def testkitTest(name: String)(
      body: LogsTestkit[IO] => IO[Unit]
  )(implicit loc: Location): Unit =
    test(TestOptions(name)) {
      LogsTestkit.inMemory[IO]().use(body)
    }

  protected def testkitTest(name: String, resourceAttributes: Attribute[_]*)(
      body: LogsTestkit[IO] => IO[Unit]
  )(implicit loc: Location): Unit =
    test(TestOptions(name)) {
      val resource = TelemetryResource.default.mergeUnsafe(TelemetryResource(Attributes(resourceAttributes: _*)))
      LogsTestkit
        .inMemory[IO](
          _.addLoggerProviderCustomizer(_.withResource(resource))
        )
        .use(body)
    }

  protected def assertSuccess[A](result: Either[NonEmptyList[A], Unit]): Unit =
    assertEquals(result, Right(()))

  protected def traceContext(traceIdHex: String, spanIdHex: String, sampled: Boolean = true): TraceContext =
    TraceContext(
      ByteVector.fromValidHex(traceIdHex),
      ByteVector.fromValidHex(spanIdHex),
      sampled = sampled
    )

  protected def logRecord(
      body: Option[AnyValue] = None,
      severity: Option[Severity] = None,
      severityText: Option[String] = None,
      eventName: Option[String] = None,
      traceContext: Option[TraceContext] = None,
      timestamp: Option[FiniteDuration] = None,
      observedTimestamp: FiniteDuration = 1.second,
      attributes: List[Attribute[_]] = Nil
  ): LogRecordData =
    LogRecordData(
      timestamp = timestamp,
      observedTimestamp = observedTimestamp,
      traceContext = traceContext,
      severity = severity,
      severityText = severityText,
      body = body,
      eventName = eventName,
      attributes = LimitedData.attributes(32, 128).appendAll(attributes.to(Attributes)),
      instrumentationScope = InstrumentationScope("service", None, None, Attributes.empty),
      resource = TelemetryResource.default
    )

  protected def exportedLastRecord(testkit: LogsTestkit[IO]): IO[LogRecordData] =
    testkit.collectLogs.flatMap {
      case Nil   => IO.raiseError(new AssertionError("expected at least one log record, got 0"))
      case other => IO.pure(other.last)
    }

  protected def buildLog(
      testkit: LogsTestkit[IO],
      loggerName: String = "service",
      loggerVersion: Option[String] = None,
      loggerSchemaUrl: Option[String] = None,
      body: Option[AnyValue] = None,
      severity: Option[Severity] = None,
      severityText: Option[String] = None,
      eventName: Option[String] = None,
      attributes: List[Attribute[_]] = Nil,
      context: Option[Context] = None,
      timestamp: Option[FiniteDuration] = None,
      observedTimestamp: Option[FiniteDuration] = None
  ): IO[LogRecordData] =
    for {
      logger <- testkit.loggerProvider
        .logger(loggerName)
        .pipe(builder => loggerVersion.fold(builder)(builder.withVersion))
        .pipe(builder => loggerSchemaUrl.fold(builder)(builder.withSchemaUrl))
        .get
      _ <- logger.logRecordBuilder
        .pipe(builder => timestamp.fold(builder)(builder.withTimestamp))
        .pipe(builder => observedTimestamp.fold(builder)(builder.withObservedTimestamp))
        .pipe(builder => context.fold(builder)(builder.withContext))
        .pipe(builder => severity.fold(builder)(builder.withSeverity))
        .pipe(builder => severityText.fold(builder)(builder.withSeverityText))
        .pipe(builder => body.fold(builder)(builder.withBody))
        .pipe(builder => eventName.fold(builder)(builder.withEventName))
        .addAttributes(attributes)
        .emit
      record <- exportedLastRecord(testkit)
    } yield record
}
