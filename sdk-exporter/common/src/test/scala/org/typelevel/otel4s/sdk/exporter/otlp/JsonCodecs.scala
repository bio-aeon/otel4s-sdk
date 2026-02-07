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

import io.circe.Encoder
import io.circe.Json
import io.circe.syntax._
import org.typelevel.otel4s.sdk.common.InstrumentationScope
import scodec.bits.ByteVector

trait JsonCodecs {

  implicit val attributeEncoder: Encoder[Attribute[_]] =
    Encoder.instance { attribute =>
      val value = attribute.key.`type` match {
        case AttributeType.AnyValue =>
          attribute.value.asInstanceOf[AnyValue].asJson

        case _ =>
          Json.obj(
            attributeTypeName(attribute.key.`type`) := attributeValue(
              attribute.key.`type`,
              attribute.value
            )
          )
      }

      Json.obj(
        "key" := attribute.key.name,
        "value" := value
      )
    }

  implicit val attributesEncoder: Encoder[Attributes] =
    Encoder[List[Attribute[_]]].contramap(_.toList)

  implicit val resourceEncoder: Encoder[TelemetryResource] =
    Encoder.instance { resource =>
      Json
        .obj(
          "attributes" := resource.attributes
        )
        .dropEmptyValues
    }

  implicit val instrumentationScopeEncoder: Encoder[InstrumentationScope] =
    Encoder.instance { scope =>
      Json
        .obj(
          "name" := scope.name,
          "version" := scope.version,
          "attributes" := scope.attributes
        )
        .dropNullValues
        .dropEmptyValues
    }

  implicit val anyValueEncoder: Encoder[AnyValue] =
    new Encoder[AnyValue] { self =>
      def apply(anyValue: AnyValue): Json =
        anyValue match {
          case _: AnyValue.EmptyValue             => Json.obj()
          case string: AnyValue.StringValue       => Json.obj("stringValue" := string.value)
          case boolean: AnyValue.BooleanValue     => Json.obj("boolValue" := boolean.value)
          case long: AnyValue.LongValue           => Json.obj("intValue" := long.value.toString)
          case double: AnyValue.DoubleValue       => Json.obj("doubleValue" := double.value)
          case byteArray: AnyValue.ByteArrayValue => Json.obj("bytesValue" := ByteVector(byteArray.value).toBase64)
          case seq: AnyValue.SeqValue             =>
            Json.obj("arrayValue" := Json.obj("values" := seq.value.map(self.apply)).dropEmptyValues)

          case map: AnyValue.MapValue =>
            Json.obj(
              "kvlistValue" := Json
                .obj("values" := map.value.map { case (k, v) =>
                  Json.obj("key" := k, "value" := self.apply(v))
                })
                .dropEmptyValues
            )
        }
    }

  private def attributeValue(
      attributeType: AttributeType[_],
      value: Any
  ): Json = {
    def primitive[A: Encoder]: Json =
      Encoder[A].apply(value.asInstanceOf[A])

    def seq[A: Encoder](attributeType: AttributeType[A]): Json = {
      val typeName = attributeTypeName(attributeType)
      val values = value.asInstanceOf[Seq[A]]
      Json.obj("values" := values.map(value => Json.obj(typeName := value)))
    }

    implicit val longEncoder: Encoder[Long] =
      Encoder[String].contramap(_.toString)

    attributeType match {
      case AttributeType.Boolean    => primitive[Boolean]
      case AttributeType.Double     => primitive[Double]
      case AttributeType.String     => primitive[String]
      case AttributeType.Long       => primitive[Long]
      case AttributeType.BooleanSeq => seq[Boolean](AttributeType.Boolean)
      case AttributeType.DoubleSeq  => seq[Double](AttributeType.Double)
      case AttributeType.StringSeq  => seq[String](AttributeType.String)
      case AttributeType.LongSeq    => seq[Long](AttributeType.Long)
      case AttributeType.AnyValue   => value.asInstanceOf[AnyValue].asJson
    }
  }

  private def attributeTypeName(attributeType: AttributeType[_]): String =
    attributeType match {
      case AttributeType.Boolean    => "boolValue"
      case AttributeType.Double     => "doubleValue"
      case AttributeType.String     => "stringValue"
      case AttributeType.Long       => "intValue"
      case AttributeType.BooleanSeq => "arrayValue"
      case AttributeType.DoubleSeq  => "arrayValue"
      case AttributeType.StringSeq  => "arrayValue"
      case AttributeType.LongSeq    => "arrayValue"
      case AttributeType.AnyValue   => "value"
    }

}

object JsonCodecs extends JsonCodecs
