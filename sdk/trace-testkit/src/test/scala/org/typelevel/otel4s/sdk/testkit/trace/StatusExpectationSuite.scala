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

package org.typelevel.otel4s.sdk.testkit.trace

import cats.data.NonEmptyList
import munit.FunSuite
import org.typelevel.otel4s.sdk.trace.data.StatusData
import org.typelevel.otel4s.trace.StatusCode

class StatusExpectationSuite extends FunSuite {

  test("any matches any status") {
    assertEquals(StatusExpectation.any.check(StatusData.Unset), Right(()))
  }

  test("code matches exact code") {
    assertEquals(StatusExpectation.code(StatusCode.Ok).check(StatusData.Ok), Right(()))
    assertEquals(
      StatusExpectation.code(StatusCode.Ok).check(StatusData.Unset),
      Left(NonEmptyList.one(StatusExpectation.Mismatch.CodeMismatch(StatusCode.Ok, StatusCode.Unset)))
    )
  }

  test("description matches exact description") {
    val actual = StatusData.Error(Some("timeout"))

    assertEquals(StatusExpectation.error.description("timeout").check(actual), Right(()))
    assertEquals(
      StatusExpectation.error.description("boom").check(actual),
      Left(NonEmptyList.one(StatusExpectation.Mismatch.DescriptionMismatch(Some("boom"), Some("timeout"))))
    )
  }

  test("description(None) requires missing description") {
    assertEquals(StatusExpectation.error.description(None).check(StatusData.Error(None)), Right(()))
    assertEquals(
      StatusExpectation.error.description(None).check(StatusData.Error(Some("timeout"))),
      Left(NonEmptyList.one(StatusExpectation.Mismatch.DescriptionMismatch(None, Some("timeout"))))
    )
  }

  test("unset, ok, and error are convenience constructors") {
    assertEquals(StatusExpectation.unset.check(StatusData.Unset), Right(()))
    assertEquals(StatusExpectation.ok.check(StatusData.Ok), Right(()))
    assertEquals(StatusExpectation.error.check(StatusData.Error(None)), Right(()))
  }
}
