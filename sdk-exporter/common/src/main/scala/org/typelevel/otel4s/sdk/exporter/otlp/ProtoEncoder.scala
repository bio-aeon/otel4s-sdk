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

package org.typelevel.otel4s
package sdk
package exporter.otlp

import com.google.protobuf.ByteString
import io.circe.Json
import org.typelevel.otel4s.sdk.common.InstrumentationScope
import org.typelevel.otel4s.sdk.exporter.proto.common.{AnyValue => AnyValueProto}
import org.typelevel.otel4s.sdk.exporter.proto.common.{InstrumentationScope => ScopeProto}
import org.typelevel.otel4s.sdk.exporter.proto.common.ArrayValue
import org.typelevel.otel4s.sdk.exporter.proto.common.KeyValue
import org.typelevel.otel4s.sdk.exporter.proto.common.KeyValueList
import org.typelevel.otel4s.sdk.exporter.proto.resource.{Resource => ResourceProto}
import scalapb.GeneratedMessage
import scalapb_circe.Printer

/** @see
  *   [[https://github.com/open-telemetry/opentelemetry-proto/blob/v1.0.0/opentelemetry/proto/common/v1/common.proto]]
  */
private[otlp] trait ProtoEncoder[-A, +P] {
  def encode(a: A): P
}

private[otlp] object ProtoEncoder {

  type Message[A] = ProtoEncoder[A, GeneratedMessage]

  def encode[A, P](a: A)(implicit ev: ProtoEncoder[A, P]): P =
    ev.encode(a)

  def toByteArray[A, P <: GeneratedMessage](a: A)(implicit
      ev: ProtoEncoder[A, P]
  ): Array[Byte] =
    ev.encode(a).toByteArray

  def toJson[A, P <: GeneratedMessage](a: A)(implicit
      ev: ProtoEncoder[A, P],
      printer: Printer
  ): Json =
    printer.toJson(ev.encode(a))

  // a preconfigured printer, different implementations may override some internal methods
  // see SpansProtoEncoder
  class JsonPrinter
      extends Printer(
        includingDefaultValueFields = false,
        formattingLongAsNumber = false,
        formattingEnumsAsNumber = true
      )

  implicit val anyValueEncoder: ProtoEncoder[AnyValue, AnyValueProto] = {
    new ProtoEncoder[AnyValue, AnyValueProto] { self =>
      def encode(anyValue: AnyValue): AnyValueProto = {
        val value = anyValue match {
          case string: AnyValue.StringValue =>
            AnyValueProto.Value.StringValue(string.value)

          case boolean: AnyValue.BooleanValue =>
            AnyValueProto.Value.BoolValue(boolean.value)

          case long: AnyValue.LongValue =>
            AnyValueProto.Value.IntValue(long.value)

          case double: AnyValue.DoubleValue =>
            AnyValueProto.Value.DoubleValue(double.value)

          case AnyValue.ByteArrayValueImpl(byteArray) =>
            AnyValueProto.Value.BytesValue(ByteString.copyFrom(byteArray))

          case seq: AnyValue.SeqValue =>
            AnyValueProto.Value.ArrayValue(ArrayValue(seq.value.map(self.encode)))

          case map: AnyValue.MapValue =>
            AnyValueProto.Value.KvlistValue(KeyValueList(map.value.map { case (k, v) =>
              KeyValue(k, Some(self.encode(v)))
            }.toSeq))

          case AnyValue.EmptyValueImpl =>
            AnyValueProto.Value.Empty
        }

        AnyValueProto(value)
      }
    }
  }

  implicit val attributeEncoder: ProtoEncoder[Attribute[_], KeyValue] = { att =>
    import AnyValueProto.Value

    def primitive[A](lift: A => Value): AnyValueProto =
      AnyValueProto(lift(att.value.asInstanceOf[A]))

    def seq[A](lift: A => Value): AnyValueProto = {
      val values = att.value.asInstanceOf[Seq[A]]
      AnyValueProto(Value.ArrayValue(ArrayValue(values.map(value => AnyValueProto(lift(value))))))
    }

    val value = att.key.`type` match {
      case AttributeType.Boolean    => primitive[Boolean](Value.BoolValue.apply)
      case AttributeType.Double     => primitive[Double](Value.DoubleValue.apply)
      case AttributeType.String     => primitive[String](Value.StringValue.apply)
      case AttributeType.Long       => primitive[Long](Value.IntValue.apply)
      case AttributeType.BooleanSeq => seq[Boolean](Value.BoolValue.apply)
      case AttributeType.DoubleSeq  => seq[Double](Value.DoubleValue.apply)
      case AttributeType.StringSeq  => seq[String](Value.StringValue.apply)
      case AttributeType.LongSeq    => seq[Long](Value.IntValue.apply)
      case AttributeType.AnyValue   => anyValueEncoder.encode(att.value.asInstanceOf[AnyValue])
    }

    KeyValue(att.key.name, Some(value))
  }

  implicit val attributesEncoder: ProtoEncoder[Attributes, Seq[KeyValue]] = { attr =>
    attr.toSeq.map(a => encode[Attribute[_], KeyValue](a))
  }

  implicit val telemetryResourceEncoder: ProtoEncoder[TelemetryResource, ResourceProto] = { resource =>
    ResourceProto(attributes = encode(resource.attributes))
  }

  implicit val instrumentationScopeEncoder: ProtoEncoder[InstrumentationScope, ScopeProto] = { scope =>
    ScopeProto(
      name = scope.name,
      version = scope.version.getOrElse(""),
      attributes = encode(scope.attributes)
    )
  }

}
