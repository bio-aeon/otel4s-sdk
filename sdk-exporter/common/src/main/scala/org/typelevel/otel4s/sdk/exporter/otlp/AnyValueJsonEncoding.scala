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

package org.typelevel.otel4s.sdk.exporter.otlp

import io.circe.Encoder
import io.circe.Json
import org.typelevel.otel4s.AnyValue

import java.util.Base64

private[exporter] object AnyValueJsonEncoding {

  def encode(value: AnyValue): String =
    value match {
      case string: AnyValue.StringValue =>
        string.value

      case boolean: AnyValue.BooleanValue =>
        boolean.value.toString

      case long: AnyValue.LongValue =>
        long.value.toString

      case double: AnyValue.DoubleValue =>
        val value = double.value
        if (value.isNaN) "NaN"
        else if (value.isPosInfinity) "Infinity"
        else if (value.isNegInfinity) "-Infinity"
        else value.toString

      case AnyValue.ByteArrayValueImpl(bytes) =>
        if (bytes.isEmpty) "" else Base64.getEncoder.encodeToString(bytes)

      case other =>
        anyValueEncoder(other).noSpaces
    }

  private implicit val anyValueEncoder: Encoder[AnyValue] = {
    new Encoder[AnyValue] { self =>
      def apply(anyValue: AnyValue): Json =
        anyValue match {
          case string: AnyValue.StringValue =>
            Json.fromString(string.value)

          case boolean: AnyValue.BooleanValue =>
            Json.fromBoolean(boolean.value)

          case long: AnyValue.LongValue =>
            Json.fromLong(long.value)

          case double: AnyValue.DoubleValue =>
            val value = double.value
            if (value.isNaN) Json.fromString("NaN")
            else if (value.isPosInfinity) Json.fromString("Infinity")
            else if (value.isNegInfinity) Json.fromString("-Infinity")
            else Json.fromDoubleOrNull(value)

          case AnyValue.ByteArrayValueImpl(bytes) =>
            if (bytes.isEmpty) Json.Null else Json.fromString(Base64.getEncoder.encodeToString(bytes))

          case seq: AnyValue.SeqValue =>
            Json.fromValues(seq.value.map(self.apply))

          case map: AnyValue.MapValue =>
            Json.fromFields(
              map.value.toList.sortBy(_._1).map { case (key, value) =>
                key -> self.apply(value)
              }
            )

          case _: AnyValue.EmptyValue =>
            Json.Null
        }
    }
  }

}
