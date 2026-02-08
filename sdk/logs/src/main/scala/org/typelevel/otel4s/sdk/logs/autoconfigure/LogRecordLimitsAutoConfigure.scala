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

package org.typelevel.otel4s.sdk.logs.autoconfigure

import cats.effect.MonadCancelThrow
import cats.effect.Resource
import org.typelevel.otel4s.sdk.autoconfigure.AttributeLimitsConfigurer
import org.typelevel.otel4s.sdk.autoconfigure.AutoConfigure
import org.typelevel.otel4s.sdk.autoconfigure.Config
import org.typelevel.otel4s.sdk.logs.LogRecordLimits

import scala.util.chaining._

/** Autoconfigures [[LogRecordLimits]].
  *
  * The configuration options:
  * {{{
  * | System property                             | Environment variable                        | Description                                                       |
  * |---------------------------------------------|---------------------------------------------|-------------------------------------------------------------------|
  * | otel.logrecord.attribute.count.limit        | OTEL_LOGRECORD_ATTRIBUTE_COUNT_LIMIT        | The maximum allowed log record attribute count. Default is `128`. |
  * | otel.logrecord.attribute.value.length.limit | OTEL_LOGRECORD_ATTRIBUTE_VALUE_LENGTH_LIMIT | The maximum allowed attribute value size. No limit by default.    |
  * | otel.attribute.count.limit                  | OTEL_ATTRIBUTE_COUNT_LIMIT                  | Global fallback for attribute count limit.                        |
  * | otel.attribute.value.length.limit           | OTEL_ATTRIBUTE_VALUE_LENGTH_LIMIT           | Global fallback for attribute value length limit.                 |
  * }}}
  *
  * @see
  *   [[https://opentelemetry.io/docs/languages/java/sdk/#loglimits]]
  *
  * @see
  *   [[https://opentelemetry.io/docs/languages/java/configuration/#properties-general]]
  *
  * @see
  *   [[https://opentelemetry.io/docs/specs/otel/configuration/sdk-environment-variables/#logrecord-limits]]
  */
private final class LogRecordLimitsAutoConfigure[F[_]: MonadCancelThrow]
    extends AutoConfigure.WithHint[F, LogRecordLimits](
      "LogRecordLimits",
      LogRecordLimitsAutoConfigure.ConfigKeys.All
    ) {

  import LogRecordLimitsAutoConfigure.ConfigKeys

  def fromConfig(config: Config): Resource[F, LogRecordLimits] = {
    def configure =
      for {
        maxNumberOfAttributes <- AttributeLimitsConfigurer.maxNumberOfAttributes(
          config,
          ConfigKeys.MaxNumberOfAttributes
        )
        maxAttributeValueLength <- AttributeLimitsConfigurer.maxAttributeValueLength(
          config,
          ConfigKeys.MaxAttributeValueLength
        )
      } yield LogRecordLimits.builder
        .pipe(b => maxNumberOfAttributes.foldLeft(b)(_.withMaxNumberOfAttributes(_)))
        .pipe(b => maxAttributeValueLength.foldLeft(b)(_.withMaxAttributeValueLength(_)))
        .build

    Resource.eval(MonadCancelThrow[F].fromEither(configure))
  }
}

private[sdk] object LogRecordLimitsAutoConfigure {

  private object ConfigKeys {
    val MaxNumberOfAttributes: Config.Key[Int] =
      Config.Key("otel.logrecord.attribute.count.limit")

    val MaxAttributeValueLength: Config.Key[Int] =
      Config.Key("otel.logrecord.attribute.value.length.limit")

    val All: Set[Config.Key[_]] =
      Set(
        MaxNumberOfAttributes,
        MaxAttributeValueLength,
        AttributeLimitsConfigurer.ConfigKeys.MaxNumberOfAttributes,
        AttributeLimitsConfigurer.ConfigKeys.MaxAttributeValueLength
      )
  }

  /** Returns [[AutoConfigure]] that configures the [[LogRecordLimits]].
    *
    * The configuration options:
    * {{{
    * | System property                             | Environment variable                        | Description                                                       |
    * |---------------------------------------------|---------------------------------------------|-------------------------------------------------------------------|
    * | otel.logrecord.attribute.count.limit        | OTEL_LOGRECORD_ATTRIBUTE_COUNT_LIMIT        | The maximum allowed log record attribute count. Default is `128`. |
    * | otel.logrecord.attribute.value.length.limit | OTEL_LOGRECORD_ATTRIBUTE_VALUE_LENGTH_LIMIT | The maximum allowed attribute value size. No limit by default.    |
    * | otel.attribute.count.limit                  | OTEL_ATTRIBUTE_COUNT_LIMIT                  | Global fallback for attribute count limit.                        |
    * | otel.attribute.value.length.limit           | OTEL_ATTRIBUTE_VALUE_LENGTH_LIMIT           | Global fallback for attribute value length limit.                 |
    * }}}
    *
    * @see
    *   [[https://opentelemetry.io/docs/languages/java/sdk/#loglimits]]
    *
    * @see
    *   [[https://opentelemetry.io/docs/languages/java/configuration/#properties-general]]
    *
    * @see
    *   [[https://opentelemetry.io/docs/specs/otel/configuration/sdk-environment-variables/#logrecord-limits]]
    */
  def apply[F[_]: MonadCancelThrow]: AutoConfigure[F, LogRecordLimits] =
    new LogRecordLimitsAutoConfigure[F]

}
