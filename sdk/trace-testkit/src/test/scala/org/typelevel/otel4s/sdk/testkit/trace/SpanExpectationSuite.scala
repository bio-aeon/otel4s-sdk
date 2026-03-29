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
import org.typelevel.otel4s.sdk.TelemetryResource
import org.typelevel.otel4s.sdk.common.InstrumentationScope
import org.typelevel.otel4s.sdk.data.LimitedData
import org.typelevel.otel4s.sdk.testkit.AttributesExpectation
import org.typelevel.otel4s.sdk.testkit.InstrumentationScopeExpectation
import org.typelevel.otel4s.sdk.testkit.TelemetryResourceExpectation
import org.typelevel.otel4s.sdk.trace.data.EventData
import org.typelevel.otel4s.sdk.trace.data.LinkData
import org.typelevel.otel4s.sdk.trace.data.SpanData
import org.typelevel.otel4s.sdk.trace.data.StatusData
import org.typelevel.otel4s.trace.SpanContext
import org.typelevel.otel4s.trace.SpanKind
import scodec.bits.ByteVector

import scala.concurrent.duration._

class SpanExpectationSuite extends FunSuite {

  test("any matches any span") {
    assertEquals(
      SpanExpectation.any.check(span(name = "GET /users", kind = SpanKind.Server, status = StatusData.Ok)),
      Right(())
    )
  }

  test("matches common span fields and nested expectations") {
    val parent = spanContext("0af7651916cd43dd8448eb211c80319c", "0000000000000001")
    val actual = span(
      name = "GET /users",
      parent = Some(parent),
      kind = SpanKind.Server,
      status = StatusData.Ok,
      attributes = Attributes(Attribute("http.method", "GET")),
      events = Vector(event("exception", 2.seconds)),
      links = Vector(link("0af7651916cd43dd8448eb211c80319c", "b7ad6b7169203331")),
      scope = InstrumentationScope("service", Some("1.0.0"), None, Attributes.empty),
      resource = TelemetryResource(Attributes(Attribute("service.name", "svc")), None)
    )

    assertEquals(
      SpanExpectation
        .server("GET /users")
        .status(StatusExpectation.ok)
        .parentSpanContext(SpanContextExpectation.any.spanIdHex(parent.spanIdHex))
        .attributesSubset(Attribute("http.method", "GET"))
        .containsEvents(EventExpectation.name("exception"))
        .containsLinks(LinkExpectation.any)
        .scope(InstrumentationScopeExpectation.name("service").version("1.0.0"))
        .resource(TelemetryResourceExpectation.any.attributesSubset(Attribute("service.name", "svc")))
        .hasEnded
        .check(actual),
      Right(())
    )
  }

  test("reports nested mismatches") {
    val actual = span(name = "GET /users", kind = SpanKind.Server, status = StatusData.Unset)

    assertEquals(
      SpanExpectation
        .client("POST /users")
        .status(StatusExpectation.error.description("timeout"))
        .check(actual),
      Left(
        NonEmptyList.of(
          SpanExpectation.Mismatch.NameMismatch("POST /users", "GET /users"),
          SpanExpectation.Mismatch.KindMismatch(SpanKind.Client, SpanKind.Server),
          SpanExpectation.Mismatch.StatusMismatch(
            NonEmptyList.of(
              StatusExpectation.Mismatch
                .CodeMismatch(org.typelevel.otel4s.trace.StatusCode.Error, org.typelevel.otel4s.trace.StatusCode.Unset),
              StatusExpectation.Mismatch.DescriptionMismatch(Some("timeout"), None)
            )
          )
        )
      )
    )
  }

  test("matches exact span and parent contexts") {
    val actualParent = spanContext("0af7651916cd43dd8448eb211c80319c", "0000000000000001")
    val actual = span(
      name = "GET /users",
      parent = Some(actualParent),
      kind = SpanKind.Server,
      status = StatusData.Ok
    )

    assertEquals(
      SpanExpectation.any
        .spanContextExact(actual.spanContext)
        .parentSpanContextExact(actualParent)
        .check(actual),
      Right(())
    )
  }

  test("reports parent span context mismatches") {
    val actualWithParent = span(
      name = "GET /users",
      parent = Some(spanContext("0af7651916cd43dd8448eb211c80319c", "0000000000000001")),
      kind = SpanKind.Server,
      status = StatusData.Ok
    )
    val actualWithoutParent = span(name = "GET /users", kind = SpanKind.Server, status = StatusData.Ok)

    assertEquals(
      SpanExpectation.any.noParentSpanContext.check(actualWithParent),
      Left(
        NonEmptyList.one(SpanExpectation.Mismatch.UnexpectedParentSpanContext(actualWithParent.parentSpanContext.get))
      )
    )
    assertEquals(
      SpanExpectation.any.parentSpanContext(SpanContextExpectation.any).check(actualWithoutParent),
      Left(NonEmptyList.one(SpanExpectation.Mismatch.MissingParentSpanContext))
    )
  }

  test("checks timestamps and ended state including missing end timestamps") {
    val actual = span(
      name = "GET /users",
      kind = SpanKind.Server,
      status = StatusData.Ok,
      endTimestamp = None
    )

    assertEquals(
      SpanExpectation.any.startTimestamp(1.second).endTimestamp(None).hasNotEnded.check(actual),
      Right(())
    )
    assertEquals(
      SpanExpectation.any.endTimestamp(3.seconds).hasEnded.check(actual),
      Left(
        NonEmptyList.of(
          SpanExpectation.Mismatch.EndTimestampMismatch(Some(3.seconds), None),
          SpanExpectation.Mismatch.EndedMismatch(true, false)
        )
      )
    )
  }

  test("checks exact attributes events and links with collection clues") {
    val actual = span(
      name = "GET /users",
      kind = SpanKind.Server,
      status = StatusData.Ok,
      attributes = Attributes(Attribute("http.method", "GET")),
      events = Vector(event("message", 1.second), event("extra", 2.seconds)),
      links = Vector(
        link("0af7651916cd43dd8448eb211c80319c", "b7ad6b7169203331"),
        link("0af7651916cd43dd8448eb211c80319c", "0000000000000001")
      )
    )

    assertEquals(
      SpanExpectation.any
        .attributesExact(Attribute("http.method", "GET"))
        .exactlyEvents(EventExpectation.name("message"), EventExpectation.name("extra"))
        .exactlyLinks(
          LinkExpectation.any.spanContext(SpanContextExpectation.any.spanIdHex("b7ad6b7169203331")),
          LinkExpectation.any.spanContext(SpanContextExpectation.any.spanIdHex("0000000000000001"))
        )
        .check(actual),
      Right(())
    )

    assertEquals(
      SpanExpectation.any.attributesEmpty
        .events(EventSetExpectation.exactly(EventExpectation.name("message")).clue("event list"))
        .links(
          LinkSetExpectation
            .exactly(LinkExpectation.any.spanContext(SpanContextExpectation.any.spanIdHex("b7ad6b7169203331")))
            .clue("link list")
        )
        .check(actual),
      Left(
        NonEmptyList.of(
          SpanExpectation.Mismatch.AttributesMismatch(
            NonEmptyList.one(AttributesExpectation.Mismatch.UnexpectedAttribute(Attribute("http.method", "GET")))
          ),
          SpanExpectation.Mismatch.EventsMismatch(
            NonEmptyList.one(
              EventSetExpectation.Mismatch.CluedMismatch(
                "event list",
                NonEmptyList.one(EventSetExpectation.Mismatch.UnexpectedEvent(1))
              )
            ),
            Some("event list")
          ),
          SpanExpectation.Mismatch.LinksMismatch(
            NonEmptyList.one(
              LinkSetExpectation.Mismatch.CluedMismatch(
                "link list",
                NonEmptyList.one(LinkSetExpectation.Mismatch.UnexpectedLink(1))
              )
            ),
            Some("link list")
          )
        )
      )
    )
  }

  test("scopeName augments scope expectations and where reports predicate clues") {
    val actual = span(
      name = "GET /users",
      kind = SpanKind.Server,
      status = StatusData.Ok,
      scope = InstrumentationScope("service", Some("1.0.0"), None, Attributes.empty)
    )

    assertEquals(
      SpanExpectation.any
        .scope(InstrumentationScopeExpectation.name("ignored").version("1.0.0"))
        .scopeName("service")
        .check(actual),
      Right(())
    )
    assertEquals(
      SpanExpectation.any.where("must be a client span")(_.kind == SpanKind.Client).check(actual),
      Left(NonEmptyList.one(SpanExpectation.Mismatch.PredicateMismatch(Some("must be a client span"))))
    )
  }

  private def span(
      name: String,
      parent: Option[SpanContext] = None,
      kind: SpanKind,
      status: StatusData,
      attributes: Attributes = Attributes.empty,
      events: Vector[EventData] = Vector.empty,
      links: Vector[LinkData] = Vector.empty,
      scope: InstrumentationScope = InstrumentationScope("scope", None, None, Attributes.empty),
      resource: TelemetryResource = TelemetryResource.empty,
      endTimestamp: Option[FiniteDuration] = Some(3.seconds)
  ): SpanData =
    SpanData(
      name = name,
      spanContext = spanContext("0af7651916cd43dd8448eb211c80319c", "0102030405060708"),
      parentSpanContext = parent,
      kind = kind,
      startTimestamp = 1.second,
      endTimestamp = endTimestamp,
      status = status,
      attributes = LimitedData.attributes(16, 64).appendAll(attributes),
      events = LimitedData.vector[EventData](16).appendAll(events),
      links = LimitedData.vector[LinkData](16).appendAll(links),
      instrumentationScope = scope,
      resource = resource
    )

  private def event(name: String, timestamp: FiniteDuration): EventData =
    EventData(name, timestamp, LimitedData.attributes(8, 32))

  private def link(traceId: String, spanId: String): LinkData =
    LinkData(spanContext(traceId, spanId), LimitedData.attributes(8, 32))

  private def spanContext(traceId: String, spanId: String): SpanContext =
    SpanContext(
      traceId = ByteVector.fromValidHex(traceId),
      spanId = ByteVector.fromValidHex(spanId),
      traceFlags = org.typelevel.otel4s.trace.TraceFlags.Default,
      traceState = org.typelevel.otel4s.trace.TraceState.empty,
      remote = false
    )
}
