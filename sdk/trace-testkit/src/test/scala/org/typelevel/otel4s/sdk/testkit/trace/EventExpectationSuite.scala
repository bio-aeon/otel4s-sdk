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
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.Attributes
import org.typelevel.otel4s.sdk.data.LimitedData
import org.typelevel.otel4s.sdk.testkit.AttributesExpectation
import org.typelevel.otel4s.sdk.trace.data.EventData

import scala.concurrent.duration._

class EventExpectationSuite extends FunSuite {

  test("event expectation matches name timestamp attributes and predicates") {
    val event = eventData(
      name = "db.query",
      timestamp = 5.seconds,
      attributes = Attributes(
        Attribute("db.system", "postgresql"),
        Attribute("db.operation", "SELECT")
      )
    )

    val expectation =
      EventExpectation
        .name("db.query")
        .timestamp(5.seconds)
        .attributesSubset(Attribute("db.system", "postgresql"))
        .where("must be a SELECT")(
          _.attributes.elements.exists(attribute => attribute == Attribute("db.operation", "SELECT"))
        )
        .clue("database event")

    assertEquals(expectation.check(event), Right(()))
    assert(expectation.matches(event))
    assertEquals(expectation.clue, Some("database event"))
  }

  test("event expectation reports name timestamp and attributes mismatches together") {
    val event = eventData(
      name = "db.write",
      timestamp = 7.seconds,
      attributes = Attributes(
        Attribute("db.system", "mysql"),
        Attribute("db.operation", "INSERT")
      )
    )

    val expectation =
      EventExpectation
        .name("db.query")
        .timestamp(5.seconds)
        .attributesExact(Attribute("db.system", "postgresql"))

    assertEquals(
      expectation.check(event),
      Left(
        NonEmptyList.of(
          EventExpectation.Mismatch.NameMismatch("db.query", "db.write"),
          EventExpectation.Mismatch.TimestampMismatch(5.seconds, 7.seconds),
          EventExpectation.Mismatch.AttributesMismatch(
            NonEmptyList.of(
              AttributesExpectation.Mismatch.AttributeValueMismatch(
                Attribute("db.system", "postgresql"),
                Attribute("db.system", "mysql")
              ),
              AttributesExpectation.Mismatch.UnexpectedAttribute(
                Attribute("db.operation", "INSERT")
              )
            )
          )
        )
      )
    )
  }

  test("any leaves event fields unconstrained") {
    val event = eventData(
      name = "exception",
      timestamp = 1.second,
      attributes = Attributes(Attribute("exception.type", "java.lang.RuntimeException"))
    )

    assertEquals(EventExpectation.any.check(event), Right(()))
    assert(EventExpectation.any.matches(event))
  }

  test("predicate clue is preserved in mismatch output") {
    val event = eventData(
      name = "db.query",
      timestamp = 5.seconds,
      attributes = Attributes(Attribute("db.system", "postgresql"))
    )

    val result =
      EventExpectation
        .name("db.query")
        .where("must be an UPDATE")(
          _.attributes.elements.exists(attribute => attribute == Attribute("db.operation", "UPDATE"))
        )
        .check(event)

    val mismatch = result.left.toOption.get.head

    assertEquals(result, Left(NonEmptyList.one(EventExpectation.Mismatch.PredicateMismatch(Some("must be an UPDATE")))))
    assertEquals(mismatch.message, "event predicate returned false: must be an UPDATE")
  }

  private def eventData(
      name: String,
      timestamp: FiniteDuration,
      attributes: Attributes
  ): EventData =
    EventData(
      name = name,
      timestamp = timestamp,
      attributes = LimitedData.attributes(16, 128).appendAll(attributes)
    )
}
