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

import org.typelevel.otel4s.AnyValue
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.logs.Severity
import org.typelevel.otel4s.sdk.testkit.InstrumentationScopeExpectation
import org.typelevel.otel4s.sdk.testkit.TelemetryResourceExpectation

import scala.concurrent.duration._

class LogRecordExpectationSuite extends LogExpectationSupport {

  testkitTest(
    "matches body severity event name trace correlation and nested expectations",
    Attribute("service.name", "svc")
  ) { testkit =>
    for {
      record <- buildLog(
        testkit,
        loggerName = "service",
        loggerVersion = Some("1.0.0"),
        loggerSchemaUrl = Some("https://schema.example/v1"),
        body = Some(AnyValue.string("request failed")),
        severity = Some(Severity.error),
        severityText = Some("ERROR"),
        eventName = Some("log.failure"),
        attributes = List(Attribute("http.route", "/users")),
        timestamp = Some(1.second),
        observedTimestamp = Some(1500.millis)
      )
    } yield {
      assertSuccess(
        LogRecordExpectation
          .message("request failed")
          .severity(Severity.error)
          .severityText("ERROR")
          .eventName("log.failure")
          .attributesSubset(Attribute("http.route", "/users"))
          .scopeName("service")
          .scope(
            InstrumentationScopeExpectation
              .name("service")
              .version("1.0.0")
              .schemaUrl("https://schema.example/v1")
          )
          .resource(TelemetryResourceExpectation.any.attributesSubset(Attribute("service.name", "svc")))
          .timestamp(1.second)
          .observedTimestamp(1500.millis)
          .check(record)
      )
    }
  }

  test("matches explicit trace correlation on sdk models") {
    val record = logRecord(
      body = Some(AnyValue.string("correlated")),
      traceContext = Some(traceContext("0af7651916cd43dd8448eb211c80319c", "b7ad6b7169203331"))
    )

    assertSuccess(
      LogRecordExpectation
        .message("correlated")
        .traceId("0af7651916cd43dd8448eb211c80319c")
        .spanId("b7ad6b7169203331")
        .check(record)
    )
  }

  testkitTest("matches raw body values and missing event name") { testkit =>
    val body = AnyValue.map(Map("status" -> AnyValue.string("failed"), "count" -> AnyValue.long(2L)))

    for {
      record <- buildLog(testkit, body = Some(body))
    } yield {
      assertSuccess(
        LogRecordExpectation.any
          .body(body)
          .noEventName
          .attributesEmpty
          .check(record)
      )
    }
  }

  testkitTest("supports timestamp predicates and untraced expectation") { testkit =>
    for {
      record <- buildLog(
        testkit,
        body = Some(AnyValue.string("hello")),
        timestamp = Some(1.second),
        observedTimestamp = Some(2.seconds)
      )
    } yield {
      assertSuccess(
        LogRecordExpectation.any
          .message("hello")
          .untraced
          .timestampWhere(_.contains(1.second))
          .observedTimestampWhere(_ == 2.seconds)
          .check(record)
      )
    }
  }

  testkitTest("reports nested and timestamp mismatches") { testkit =>
    for {
      record <- buildLog(
        testkit,
        loggerName = "service",
        body = Some(AnyValue.string("ok")),
        eventName = Some("log.ok"),
        attributes = List(Attribute("http.route", "/users")),
        timestamp = Some(1.second),
        observedTimestamp = Some(2.seconds)
      )
    } yield {
      val result = LogRecordExpectation
        .message("failed")
        .severity(Severity.error)
        .eventName("log.failed")
        .attributesSubset(Attribute("error.type", "timeout"))
        .scope(InstrumentationScopeExpectation.name("other"))
        .resource(TelemetryResourceExpectation.any.attributesSubset(Attribute("service.name", "svc")))
        .timestamp(3.seconds)
        .observedTimestamp(4.seconds)
        .timestampWhere("exact timestamp")(_.contains(5.seconds))
        .observedTimestampWhere("exact observed timestamp")(_ == 6.seconds)
        .check(record)

      val mismatches = result.left.toOption.get
      assert(mismatches.exists(_.isInstanceOf[LogRecordExpectation.Mismatch.BodyMismatch]))
      assert(mismatches.exists(_.isInstanceOf[LogRecordExpectation.Mismatch.SeverityMismatch]))
      assert(mismatches.exists(_.isInstanceOf[LogRecordExpectation.Mismatch.EventNameMismatch]))
      assert(mismatches.exists(_.isInstanceOf[LogRecordExpectation.Mismatch.AttributesMismatch]))
      assert(mismatches.exists(_.isInstanceOf[LogRecordExpectation.Mismatch.ScopeMismatch]))
      assert(mismatches.exists(_.isInstanceOf[LogRecordExpectation.Mismatch.ResourceMismatch]))
      assert(mismatches.exists(_.isInstanceOf[LogRecordExpectation.Mismatch.TimestampMismatch]))
      assert(mismatches.exists(_.isInstanceOf[LogRecordExpectation.Mismatch.ObservedTimestampMismatch]))
      assert(mismatches.exists(_.isInstanceOf[LogRecordExpectation.Mismatch.TimestampPredicateMismatch]))
      assert(mismatches.exists(_.isInstanceOf[LogRecordExpectation.Mismatch.ObservedTimestampPredicateMismatch]))
    }
  }
}
