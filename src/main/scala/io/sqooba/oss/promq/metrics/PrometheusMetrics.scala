package io.sqooba.oss.promq.metrics

import java.time.Instant
import io.circe.Decoder
import io.circe.HCursor
import scala.util.Try

final case class MatrixMetric(metric: Map[String, String], values: List[(Instant, String)])
final case class VectorMetric(metric: Map[String, String], value: (Instant, String))

object PrometheusMetrics {

  implicit val msInstantDecode: Decoder[Instant] = Decoder.decodeDouble.emapTry { value =>
    Try(Instant.ofEpochMilli((value * 1000).toLong))
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
