package io.sqooba.oss.promq.metrics

import java.time.Instant
import scala.util.Try
import io.circe.Decoder
import io.circe.HCursor

final case class MatrixMetric(metric: Map[String, String], values: List[(Instant, String)])
final case class VectorMetric(metric: Map[String, String], value: (Instant, String))

object PrometheusMetrics {
  // Prometheus is cutting the timestamp at the second, not ms
  implicit val secondInstantDecode: Decoder[Instant] = Decoder.decodeLong.emapTry { value =>
    Try(Instant.ofEpochSecond(value))
  }

  // This decoder is required because the `metric` field might be empty.
  // We don't want to introduce an option here because it makes the code a lot more verbose
  // when using the library
  implicit val vectorMetricDecoder: Decoder[VectorMetric] = new Decoder[VectorMetric] {
    final def apply(c: HCursor): Decoder.Result[VectorMetric] =
      for {
        value  <- c.downField("value").as[(Instant, String)]
        metric <- c.downField("metric").as[Option[Map[String, String]]]
      } yield {
        VectorMetric(metric.getOrElse(Map()), value)
      }
  }

  implicit val matrixMetricDecoder: Decoder[MatrixMetric] = new Decoder[MatrixMetric] {
    final def apply(c: HCursor): Decoder.Result[MatrixMetric] =
      for {
        value  <- c.downField("values").as[List[(Instant, String)]]
        metric <- c.downField("metric").as[Option[Map[String, String]]]
      } yield {
        MatrixMetric(metric.getOrElse(Map()), value)
      }
  }
}
