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

import cats.effect.IO
import cats.syntax.either._
import cats.syntax.foldable._
import fs2.io.compression._
import munit.CatsEffectSuite
import org.typelevel.otel4s.sdk.autoconfigure.Config
import org.typelevel.otel4s.sdk.metrics.InstrumentType
import org.typelevel.otel4s.sdk.metrics.data.AggregationTemporality

class OtlpMetricExporterAutoConfigureSuite extends CatsEffectSuite {

  test("load from the config - empty config - load default") {
    val config = Config.ofProps(Map.empty)

    val expected =
      "OtlpMetricExporter{client=OtlpClient{" +
        "protocol=http/protobuf, " +
        "endpoint=http://localhost:4318/v1/metrics, " +
        "timeout=10 seconds, " +
        "compression=none, " +
        "headers={}}}"

    OtlpMetricExporterAutoConfigure[IO]
      .configure(config)
      .use(exporter => IO(assertEquals(exporter.name, expected)))
  }

  test("load from the config - empty string - load default") {
    val config = Config.ofProps(
      Map(
        "otel.exporter.otlp.protocol" -> "",
        "otel.exporter.otlp.metrics.protocol" -> ""
      )
    )

    val expected =
      "OtlpMetricExporter{client=OtlpClient{" +
        "protocol=http/protobuf, " +
        "endpoint=http://localhost:4318/v1/metrics, " +
        "timeout=10 seconds, " +
        "compression=none, " +
        "headers={}}}"

    OtlpMetricExporterAutoConfigure[IO]
      .configure(config)
      .use(exporter => IO(assertEquals(exporter.name, expected)))
  }

  test("load from the config - prioritize 'metrics' properties") {
    val config = Config.ofProps(
      Map(
        "otel.exporter.otlp.protocol" -> "http/protobuf",
        "otel.exporter.otlp.metrics.protocol" -> "http/json"
      )
    )

    val expected =
      "OtlpMetricExporter{client=OtlpClient{" +
        "protocol=http/json, " +
        "endpoint=http://localhost:4318/v1/metrics, " +
        "timeout=10 seconds, " +
        "compression=none, " +
        "headers={}}}"

    OtlpMetricExporterAutoConfigure[IO]
      .configure(config)
      .use(exporter => IO(assertEquals(exporter.name, expected)))
  }

  test("load from the config - generate a valid endpoint") {
    val endpoints = List(
      "http://localhost:4318",
      "http://localhost:4318/"
    )

    val expected =
      "OtlpMetricExporter{client=OtlpClient{" +
        "protocol=http/protobuf, " +
        "endpoint=http://localhost:4318/v1/metrics, " +
        "timeout=10 seconds, " +
        "compression=none, " +
        "headers={}}}"

    endpoints.traverse_ { endpoint =>
      val config = Config.ofProps(
        Map("otel.exporter.otlp.endpoint" -> endpoint)
      )

      OtlpMetricExporterAutoConfigure[IO]
        .configure(config)
        .use(exporter => IO(assertEquals(exporter.name, expected)))
    }
  }

  test("load from the config - unknown protocol - fail") {
    val config = Config.ofProps(Map("otel.exporter.otlp.protocol" -> "mqtt"))

    OtlpMetricExporterAutoConfigure[IO]
      .configure(config)
      .use_
      .attempt
      .map(_.leftMap(_.getMessage))
      .assertEquals(
        Left("""Cannot autoconfigure [OtlpClient].
               |Cause: Unrecognized protocol [mqtt]. Supported options [http/json, http/protobuf, grpc].
               |Config:
               |1) `otel.exporter.otlp.compression` - N/A
               |2) `otel.exporter.otlp.endpoint` - N/A
               |3) `otel.exporter.otlp.headers` - N/A
               |4) `otel.exporter.otlp.metrics.compression` - N/A
               |5) `otel.exporter.otlp.metrics.endpoint` - N/A
               |6) `otel.exporter.otlp.metrics.headers` - N/A
               |7) `otel.exporter.otlp.metrics.protocol` - N/A
               |8) `otel.exporter.otlp.metrics.timeout` - N/A
               |9) `otel.exporter.otlp.protocol` - mqtt
               |10) `otel.exporter.otlp.timeout` - N/A""".stripMargin)
      )
  }

  test("load from the config - temporality preference - delta") {
    val config = Config.ofProps(
      Map(
        "otel.exporter.otlp.metrics.temporality.preference" -> "DeLtA"
      )
    )

    OtlpMetricExporterAutoConfigure[IO]
      .configure(config)
      .use { exporter =>
        IO {
          assertEquals(
            exporter.aggregationTemporalitySelector.select(InstrumentType.Counter),
            AggregationTemporality.Delta
          )
          assertEquals(
            exporter.aggregationTemporalitySelector.select(InstrumentType.UpDownCounter),
            AggregationTemporality.Cumulative
          )
          assertEquals(
            exporter.aggregationTemporalitySelector.select(InstrumentType.ObservableCounter),
            AggregationTemporality.Delta
          )
        }
      }
  }

  test("load from the config - temporality preference - lowmemory") {
    val config = Config.ofProps(
      Map(
        "otel.exporter.otlp.metrics.temporality.preference" -> "LOWMEMORY"
      )
    )

    OtlpMetricExporterAutoConfigure[IO]
      .configure(config)
      .use { exporter =>
        IO {
          assertEquals(
            exporter.aggregationTemporalitySelector.select(InstrumentType.Counter),
            AggregationTemporality.Delta
          )
          assertEquals(
            exporter.aggregationTemporalitySelector.select(InstrumentType.ObservableCounter),
            AggregationTemporality.Cumulative
          )
        }
      }
  }

  test("load from the config - invalid temporality preference - fail") {
    val config = Config.ofProps(
      Map(
        "otel.exporter.otlp.metrics.temporality.preference" -> "fast"
      )
    )

    OtlpMetricExporterAutoConfigure[IO]
      .configure(config)
      .use_
      .attempt
      .map(_.leftMap(_.getMessage))
      .assertEquals(
        Left("""Cannot autoconfigure [OtlpMetricExporter].
               |Cause: Unrecognized aggregation temporality preference [fast]. Supported options [cumulative, delta, lowmemory].
               |Config:
               |1) `otel.exporter.otlp.metrics.default.histogram.aggregation` - N/A
               |2) `otel.exporter.otlp.metrics.temporality.preference` - fast""".stripMargin)
      )
  }

  test("load from the config - histogram aggregation explicit bucket") {
    val config = Config.ofProps(
      Map(
        "otel.exporter.otlp.metrics.default.histogram.aggregation" -> "EXPLICIT_BUCKET_HISTOGRAM"
      )
    )

    OtlpMetricExporterAutoConfigure[IO]
      .configure(config)
      .use_ // explicit_bucket_histogram maps to the SDK default histogram aggregation
  }

  test("load from the config - histogram aggregation base2 exponential - fail") {
    val config = Config.ofProps(
      Map(
        "otel.exporter.otlp.metrics.default.histogram.aggregation" -> "base2_exponential_bucket_histogram"
      )
    )

    OtlpMetricExporterAutoConfigure[IO]
      .configure(config)
      .use_
      .attempt
      .map(_.leftMap(_.getMessage))
      .assertEquals(
        Left("""Cannot autoconfigure [OtlpMetricExporter].
               |Cause: Unrecognized default histogram aggregation [base2_exponential_bucket_histogram]. Supported options [explicit_bucket_histogram].
               |Config:
               |1) `otel.exporter.otlp.metrics.default.histogram.aggregation` - base2_exponential_bucket_histogram
               |2) `otel.exporter.otlp.metrics.temporality.preference` - N/A""".stripMargin)
      )
  }

}
