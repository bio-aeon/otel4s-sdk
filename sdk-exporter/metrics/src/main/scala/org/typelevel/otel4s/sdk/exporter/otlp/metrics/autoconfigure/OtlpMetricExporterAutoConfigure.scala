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

package org.typelevel.otel4s.sdk.exporter.otlp.metrics.autoconfigure

import cats.effect.Async
import cats.effect.Resource
import cats.effect.syntax.resource._
import fs2.compression.Compression
import fs2.io.net.Network
import org.http4s.Headers
import org.http4s.client.Client
import org.typelevel.otel4s.sdk.autoconfigure.AutoConfigure
import org.typelevel.otel4s.sdk.autoconfigure.Config
import org.typelevel.otel4s.sdk.autoconfigure.ConfigurationError
import org.typelevel.otel4s.sdk.common.Diagnostic
import org.typelevel.otel4s.sdk.exporter.otlp.autoconfigure.OtlpClientAutoConfigure
import org.typelevel.otel4s.sdk.exporter.otlp.metrics.MetricsProtoEncoder
import org.typelevel.otel4s.sdk.exporter.otlp.metrics.OtlpMetricExporter
import org.typelevel.otel4s.sdk.metrics.data.MetricData
import org.typelevel.otel4s.sdk.metrics.exporter.AggregationSelector
import org.typelevel.otel4s.sdk.metrics.exporter.AggregationTemporalitySelector
import org.typelevel.otel4s.sdk.metrics.exporter.CardinalityLimitSelector
import org.typelevel.otel4s.sdk.metrics.exporter.MetricExporter

import java.util.Locale

/** Autoconfigures OTLP [[org.typelevel.otel4s.sdk.metrics.exporter.MetricExporter MetricExporter]].
  *
  * @see
  *   [[OtlpClientAutoConfigure]] for OTLP client configuration
  *
  * @see
  *   [[https://opentelemetry.io/docs/languages/sdk-configuration/otlp-exporter/#otel_exporter_otlp_protocol]]
  */
private final class OtlpMetricExporterAutoConfigure[
    F[_]: Async: Network: Compression: Diagnostic
](customClient: Option[Client[F]])
    extends AutoConfigure.WithHint[F, MetricExporter[F]](
      "OtlpMetricExporter",
      OtlpMetricExporterAutoConfigure.ConfigKeys.All
    )
    with AutoConfigure.Named.Unsealed[F, MetricExporter[F]] {
  import OtlpMetricExporterAutoConfigure.ConfigKeys

  def name: String = "otlp"

  protected def fromConfig(config: Config): Resource[F, MetricExporter[F]] = {
    import MetricsProtoEncoder.exportMetricsRequest
    import MetricsProtoEncoder.jsonPrinter
    import MetricsProtoEncoder.grpcResponse

    val defaults = OtlpClientAutoConfigure.Defaults(
      OtlpMetricExporter.Defaults.Protocol,
      OtlpMetricExporter.Defaults.HttpEndpoint,
      OtlpMetricExporter.Defaults.HttpEndpoint.path.toString,
      Headers.empty,
      OtlpMetricExporter.Defaults.Timeout,
      OtlpMetricExporter.Defaults.Compression
    )

    for {
      temporality <- Async[F]
        .fromEither(
          config.getOrElse(
            ConfigKeys.TemporalityPreference,
            AggregationTemporalitySelector.alwaysCumulative
          )
        )
        .toResource
      defaultAggregation <- Async[F]
        .fromEither(
          config.getOrElse(
            ConfigKeys.DefaultHistogramAggregation,
            AggregationSelector.default
          )
        )
        .toResource
      client <- OtlpClientAutoConfigure
        .metrics[F, MetricData](defaults, customClient)
        .configure(config)
    } yield new OtlpMetricExporter[F](
      client,
      temporality,
      defaultAggregation,
      CardinalityLimitSelector.default
    )
  }

  private implicit val aggregationTemporalityReader: Config.Reader[AggregationTemporalitySelector] =
    Config.Reader.decodeWithHint("AggregationTemporalitySelector") { s =>
      s.toLowerCase(Locale.ROOT) match {
        case "cumulative" =>
          Right(AggregationTemporalitySelector.alwaysCumulative)

        case "delta" =>
          Right(AggregationTemporalitySelector.deltaPreferred)

        case "lowmemory" =>
          Right(AggregationTemporalitySelector.lowMemory)

        case _ =>
          Left(
            ConfigurationError(
              s"Unrecognized aggregation temporality preference [$s]. Supported options [cumulative, delta, lowmemory]"
            )
          )
      }
    }

  private implicit val defaultAggregationReader: Config.Reader[AggregationSelector] =
    Config.Reader.decodeWithHint("AggregationSelector") { s =>
      s.toLowerCase(Locale.ROOT) match {
        case "explicit_bucket_histogram" =>
          Right(AggregationSelector.default)

        case "base2_exponential_bucket_histogram" =>
          Left(
            ConfigurationError(
              "Unrecognized default histogram aggregation [base2_exponential_bucket_histogram]. Supported options [explicit_bucket_histogram]"
            )
          )

        case _ =>
          Left(
            ConfigurationError(
              s"Unrecognized default histogram aggregation [$s]. Supported options [explicit_bucket_histogram]"
            )
          )
      }
    }
}

object OtlpMetricExporterAutoConfigure {
  private object ConfigKeys {
    val TemporalityPreference: Config.Key[AggregationTemporalitySelector] =
      Config.Key("otel.exporter.otlp.metrics.temporality.preference")

    val DefaultHistogramAggregation: Config.Key[AggregationSelector] =
      Config.Key("otel.exporter.otlp.metrics.default.histogram.aggregation")

    val All: Set[Config.Key[_]] =
      Set(TemporalityPreference, DefaultHistogramAggregation)
  }

  /** Autoconfigures OTLP [[org.typelevel.otel4s.sdk.metrics.exporter.MetricExporter MetricExporter]].
    *
    * The configuration depends on the `otel.exporter.otlp.protocol` or `otel.exporter.otlp.metrics.protocol`.
    *
    * Supported protocols:
    *   - `grpc`
    *   - `http/json`
    *   - `http/protobuf`
    *
    * @see
    *   `OtlpHttpClientAutoConfigure` for the configuration details of the OTLP HTTP client
    */
  def apply[
      F[_]: Async: Network: Compression: Diagnostic
  ]: AutoConfigure.Named[F, MetricExporter[F]] =
    new OtlpMetricExporterAutoConfigure[F](None)

  /** Autoconfigures OTLP [[org.typelevel.otel4s.sdk.metrics.exporter.MetricExporter MetricExporter]] using the given
    * client.
    *
    * The configuration depends on the `otel.exporter.otlp.protocol` or `otel.exporter.otlp.metrics.protocol`.
    *
    * Supported protocols:
    *   - `grpc`
    *   - `http/json`
    *   - `http/protobuf`
    *
    * @see
    *   `OtlpHttpClientAutoConfigure` for the configuration details of the OTLP HTTP client
    *
    * @note
    *   the 'timeout' and 'tlsContext' settings will be ignored. You must preconfigure the client manually.
    *
    * @example
    *   {{{
    * import java.net.{InetSocketAddress, ProxySelector}
    * import java.net.http.HttpClient
    * import org.http4s.jdkhttpclient.JdkHttpClient
    *
    * val jdkHttpClient = HttpClient
    *   .newBuilder()
    *   .proxy(ProxySelector.of(InetSocketAddress.createUnresolved("localhost", 3312)))
    *   .build()
    *
    * OpenTelemetrySdk.autoConfigured[IO](
    *   _.addMetricExporterConfigurer(
    *     OtlpMetricExporterAutoConfigure.customClient[IO](JdkHttpClient(jdkHttpClient))
    *   )
    * )
    *   }}}
    *
    * @param client
    *   the custom http4s client to use
    */
  def customClient[
      F[_]: Async: Network: Compression: Diagnostic
  ](client: Client[F]): AutoConfigure.Named[F, MetricExporter[F]] =
    new OtlpMetricExporterAutoConfigure[F](Some(client))

}
