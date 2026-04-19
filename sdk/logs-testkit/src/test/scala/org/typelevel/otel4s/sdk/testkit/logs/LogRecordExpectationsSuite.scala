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

import cats.effect.IO
import org.typelevel.otel4s.AnyValue
import org.typelevel.otel4s.logs.Severity

class LogRecordExpectationsSuite extends LogExpectationSupport {

  testkitTest("checkAllDistinct requires distinct matches and format renders failures") { testkit =>
    for {
      recordA <- buildLog(testkit, body = Some(AnyValue.string("request failed")), severity = Some(Severity.error))
      recordB <- buildLog(testkit, body = Some(AnyValue.string("db timeout")), severity = Some(Severity.warn))
    } yield {
      val records = List(recordA, recordB)

      assertSuccess(
        LogRecordExpectations.checkAllDistinct(
          records,
          LogRecordExpectation.message("request failed").severity(Severity.error),
          LogRecordExpectation.message("db timeout").severity(Severity.warn)
        )
      )

      val mismatches = LogRecordExpectations
        .checkAllDistinct(
          records.take(1),
          List(
            LogRecordExpectation.message("request failed"),
            LogRecordExpectation.message("request failed")
          )
        )
        .left
        .toOption
        .get

      val rendered = LogRecordExpectations.format(mismatches)
      assert(rendered.contains("Log record expectations failed:"))
      assert(rendered.contains("no distinct log record remained"))
    }
  }

  testkitTest("check matches trace and span correlation") { _ =>
    val records = List(
      logRecord(
        body = Some(AnyValue.string("correlated")),
        traceContext = Some(traceContext("0af7651916cd43dd8448eb211c80319c", "b7ad6b7169203331"))
      )
    )

    IO {
      assertEquals(
        LogRecordExpectations.check(
          records,
          LogRecordExpectation
            .message("correlated")
            .traceId("0af7651916cd43dd8448eb211c80319c")
            .spanId("b7ad6b7169203331")
        ),
        None
      )
    }
  }

  testkitTest("closest mismatch ranking prefers body and severity before unrelated trace correlation") { _ =>
    val records = List(
      logRecord(
        body = Some(AnyValue.string("target")),
        severity = Some(Severity.error),
        traceContext = Some(traceContext("11111111111111111111111111111111", "2222222222222222"))
      ),
      logRecord(
        body = Some(AnyValue.string("other body")),
        severity = Some(Severity.error),
        traceContext = Some(traceContext("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbb"))
      )
    )

    IO {
      val mismatch = LogRecordExpectations
        .check(
          records,
          LogRecordExpectation
            .message("target")
            .severity(Severity.error)
            .traceId("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
            .spanId("bbbbbbbbbbbbbbbb")
        )
        .get

      val closest = mismatch.asInstanceOf[LogRecordMismatch.ClosestMismatch]
      assertEquals(closest.record.body, Some(AnyValue.string("target")))
    }
  }
}
