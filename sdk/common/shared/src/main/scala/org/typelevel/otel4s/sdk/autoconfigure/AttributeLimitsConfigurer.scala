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

package org.typelevel.otel4s.sdk.autoconfigure

private[sdk] object AttributeLimitsConfigurer {

  object ConfigKeys {
    val MaxNumberOfAttributes: Config.Key[Int] =
      Config.Key("otel.attribute.count.limit")

    val MaxAttributeValueLength: Config.Key[Int] =
      Config.Key("otel.attribute.value.length.limit")
  }

  def maxNumberOfAttributes(
      config: Config,
      specific: Config.Key[Int]
  ): Either[ConfigurationError, Option[Int]] =
    firstDefined(config, specific, ConfigKeys.MaxNumberOfAttributes)

  def maxAttributeValueLength(
      config: Config,
      specific: Config.Key[Int]
  ): Either[ConfigurationError, Option[Int]] =
    firstDefined(config, specific, ConfigKeys.MaxAttributeValueLength)

  private def firstDefined[A: Config.Reader](
      config: Config,
      first: Config.Key[A],
      second: Config.Key[A]
  ): Either[ConfigurationError, Option[A]] =
    config.get(first).flatMap {
      case some @ Some(_) => Right(some)
      case None           => config.get(second)
    }
}
