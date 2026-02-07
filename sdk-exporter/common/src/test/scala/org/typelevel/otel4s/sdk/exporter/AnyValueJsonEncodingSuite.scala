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

package org.typelevel.otel4s.sdk.exporter

import org.typelevel.otel4s.AnyValue

import java.nio.charset.StandardCharsets
import java.util.Base64

class AnyValueJsonEncodingSuite extends munit.FunSuite {
  import org.typelevel.otel4s.sdk.exporter.otlp.AnyValueJsonEncoding.encode

  test("AnyValue primitives follow non-OTLP representation") {
    assertEquals(encode(AnyValue.string("hello world")), "hello world")
    assertEquals(encode(AnyValue.string("")), "")
    assertEquals(encode(AnyValue.boolean(true)), "true")
    assertEquals(encode(AnyValue.boolean(false)), "false")
    assertEquals(encode(AnyValue.long(42L)), "42")
    assertEquals(encode(AnyValue.long(-123L)), "-123")
    assertEquals(encode(AnyValue.double(3.14159)), "3.14159")
    assertEquals(encode(AnyValue.double(Double.NaN)), "NaN")
    assertEquals(encode(AnyValue.double(Double.PositiveInfinity)), "Infinity")
    assertEquals(encode(AnyValue.double(Double.NegativeInfinity)), "-Infinity")
    assertEquals(encode(AnyValue.empty), "null")

    val bytes = "hello world".getBytes(StandardCharsets.UTF_8)
    assertEquals(
      encode(AnyValue.bytes(bytes)),
      Base64.getEncoder.encodeToString(bytes)
    )
  }

  test("AnyValue arrays use JSON with nested rules") {
    val nestedBytes = AnyValue.bytes("hello world".getBytes(StandardCharsets.UTF_8))
    val value = AnyValue.seq(
      Seq(
        AnyValue.long(1L),
        AnyValue.double(Double.NegativeInfinity),
        AnyValue.string("a"),
        AnyValue.boolean(true),
        AnyValue.map(Map("nested" -> nestedBytes)),
        AnyValue.empty
      )
    )

    val expected = s"""[1,"-Infinity","a",true,{"nested":"${Base64.getEncoder.encodeToString(
        "hello world".getBytes(StandardCharsets.UTF_8)
      )}"},null]"""

    assertEquals(encode(value), expected)
  }

  test("AnyValue maps use JSON with stable key ordering") {
    val value = AnyValue.map(
      Map(
        "b" -> AnyValue.long(2L),
        "a" -> AnyValue.double(Double.NaN),
        "c" -> AnyValue.seq(Seq(AnyValue.long(3L), AnyValue.empty))
      )
    )

    assertEquals(encode(value), """{"a":"NaN","b":2,"c":[3,null]}""")
  }

  test("Value as string - strings") {
    val cases = List(
      "hello" -> "hello",
      "" -> "",
      "line1\nline2\ttab" -> "line1\nline2\ttab",
      "say \"hello\"" -> "say \"hello\"",
      "path\\to\\file" -> "path\\to\\file",
      "\u0000\u0001\u001F" -> "\u0000\u0001\u001F",
      "Hello \u4e16\u754c \ud83c\udf0d" -> "Hello \u4e16\u754c \ud83c\udf0d"
    )

    cases.foreach { case (input, expected) =>
      assertEquals(encode(AnyValue.string(input)), expected)
    }
  }

  test("Value as string - booleans") {
    val cases = List(true -> "true", false -> "false")
    cases.foreach { case (input, expected) =>
      assertEquals(encode(AnyValue.boolean(input)), expected)
    }
  }

  test("Value as string - longs") {
    val cases = List(
      42L -> "42",
      -123L -> "-123",
      0L -> "0",
      Long.MaxValue -> "9223372036854775807",
      Long.MinValue -> "-9223372036854775808"
    )
    cases.foreach { case (input, expected) =>
      assertEquals(encode(AnyValue.long(input)), expected)
    }
  }

  test("Value as string - doubles") {
    val cases = List(
      3.14 -> "3.14",
      -2.5 -> "-2.5",
      0.1 -> "0.1",
      -0.1 -> "-0.1",
      Double.NaN -> "NaN",
      Double.PositiveInfinity -> "Infinity",
      Double.NegativeInfinity -> "-Infinity"
    )
    cases.foreach { case (input, expected) =>
      assertEquals(encode(AnyValue.double(input)), expected)
    }
  }

  test("Value as string - bytes") {
    val regularBytes = Array[Byte](0, 1, 2, Byte.MaxValue, Byte.MinValue)
    val cases = List(
      Array.emptyByteArray -> "",
      regularBytes -> Base64.getEncoder.encodeToString(regularBytes)
    )
    cases.foreach { case (input, expected) =>
      assertEquals(encode(AnyValue.bytes(input)), expected)
    }
  }

  test("Value as string - empty") {
    assertEquals(encode(AnyValue.empty), "null")
  }

  test("Value as string - arrays") {
    val cases = List(
      AnyValue.seq(Seq.empty) -> "[]",
      AnyValue.seq(Seq(AnyValue.string("test"))) -> """["test"]""",
      AnyValue.seq(Seq(AnyValue.string("a"), AnyValue.string("b"), AnyValue.string("c"))) -> """["a","b","c"]""",
      AnyValue.seq(Seq(AnyValue.long(1L), AnyValue.long(2L), AnyValue.long(3L))) -> "[1,2,3]",
      AnyValue.seq(
        Seq(
          AnyValue.string("string"),
          AnyValue.long(42L),
          AnyValue.double(3.14),
          AnyValue.boolean(true),
          AnyValue.boolean(false),
          AnyValue.empty
        )
      ) -> """["string",42,3.14,true,false,null]""",
      AnyValue.seq(
        Seq(
          AnyValue.string("outer"),
          AnyValue.seq(Seq(AnyValue.string("inner1"), AnyValue.string("inner2"))),
          AnyValue.long(42L)
        )
      ) -> """["outer",["inner1","inner2"],42]""",
      AnyValue.seq(
        Seq(
          AnyValue.seq(
            Seq(
              AnyValue.seq(
                Seq(
                  AnyValue.seq(
                    Seq(
                      AnyValue.seq(Seq(AnyValue.string("deep")))
                    )
                  )
                )
              )
            )
          ),
          AnyValue.string("shallow")
        )
      ) -> """[[[[["deep"]]]],"shallow"]"""
    )

    cases.foreach { case (input, expected) =>
      assertEquals(encode(input), expected)
    }
  }

  test("Value as string - maps") {
    val linkedMap = Map("key1" -> AnyValue.string("value1"), "key2" -> AnyValue.long(42L))

    val cases = List(
      AnyValue.map(Map.empty) -> "{}",
      AnyValue.map(Map("key" -> AnyValue.string("value"))) -> """{"key":"value"}""",
      AnyValue.map(
        Map(
          "name" -> AnyValue.string("Alice"),
          "age" -> AnyValue.long(30L),
          "active" -> AnyValue.boolean(true)
        )
      ) -> """{"active":true,"age":30,"name":"Alice"}""",
      AnyValue.map(
        Map(
          "outer" -> AnyValue.string("value"),
          "inner" -> AnyValue.map(Map("nested1" -> AnyValue.string("a"), "nested2" -> AnyValue.string("b")))
        )
      ) -> """{"inner":{"nested1":"a","nested2":"b"},"outer":"value"}""",
      AnyValue.map(
        Map(
          "name" -> AnyValue.string("test"),
          "items" -> AnyValue.seq(Seq(AnyValue.long(1L), AnyValue.long(2L), AnyValue.long(3L)))
        )
      ) -> """{"items":[1,2,3],"name":"test"}""",
      AnyValue.map(
        Map(
          "string" -> AnyValue.string("text"),
          "long" -> AnyValue.long(42L),
          "double" -> AnyValue.double(3.14),
          "bool" -> AnyValue.boolean(true),
          "empty" -> AnyValue.empty,
          "bytes" -> AnyValue.bytes(Array[Byte](1, 2)),
          "array" -> AnyValue.seq(Seq(AnyValue.string("a"), AnyValue.string("b")))
        )
      ) -> """{"array":["a","b"],"bool":true,"bytes":"AQI=","double":3.14,"empty":null,"long":42,"string":"text"}""",
      AnyValue.map(linkedMap) -> """{"key1":"value1","key2":42}""",
      AnyValue.map(
        Map(
          "key with spaces" -> AnyValue.string("value1"),
          "key\"with\"quotes" -> AnyValue.string("value2"),
          "key\nwith\nnewlines" -> AnyValue.string("value3")
        )
      ) -> """{"key\nwith\nnewlines":"value3","key with spaces":"value1","key\"with\"quotes":"value2"}"""
    )

    cases.foreach { case (input, expected) =>
      assertEquals(encode(input), expected)
    }
  }

  test("Value as string - complex nested structure") {
    val complexValue = AnyValue.map(
      Map(
        "user" -> AnyValue.string("Alice"),
        "scores" -> AnyValue.seq(
          Seq(
            AnyValue.long(95L),
            AnyValue.double(87.5),
            AnyValue.long(92L),
            AnyValue.double(Double.NaN),
            AnyValue.double(Double.PositiveInfinity)
          )
        ),
        "passed" -> AnyValue.boolean(true),
        "metadata" -> AnyValue.map(
          Map(
            "timestamp" -> AnyValue.long(1234567890L),
            "tags" -> AnyValue.seq(
              Seq(AnyValue.string("important"), AnyValue.string("reviewed"), AnyValue.string("final"))
            )
          )
        )
      )
    )

    assertEquals(
      encode(complexValue),
      """{"metadata":{"tags":["important","reviewed","final"],"timestamp":1234567890},"passed":true,"scores":[95,87.5,92,"NaN","Infinity"],"user":"Alice"}"""
    )
  }

  test("Value as string - edge cases") {
    val cases = List(
      AnyValue.map(Map("" -> AnyValue.string("value"))) -> """{"":"value"}""",
      AnyValue.seq(Seq(AnyValue.empty, AnyValue.empty, AnyValue.empty)) -> "[null,null,null]",
      AnyValue.seq(
        Seq(
          AnyValue.map(Map("id" -> AnyValue.long(1L), "name" -> AnyValue.string("A"))),
          AnyValue.map(Map("id" -> AnyValue.long(2L), "name" -> AnyValue.string("B"))),
          AnyValue.map(Map("id" -> AnyValue.long(3L), "name" -> AnyValue.string("C")))
        )
      ) -> """[{"id":1,"name":"A"},{"id":2,"name":"B"},{"id":3,"name":"C"}]""",
      AnyValue.map(
        Map(
          "data" -> AnyValue.string("test"),
          "items" -> AnyValue.seq(Seq.empty)
        )
      ) -> """{"data":"test","items":[]}""",
      AnyValue.map(
        Map(
          "data" -> AnyValue.string("test"),
          "metadata" -> AnyValue.map(Map.empty)
        )
      ) -> """{"data":"test","metadata":{}}"""
    )

    cases.foreach { case (input, expected) =>
      assertEquals(encode(input), expected)
    }
  }

}
