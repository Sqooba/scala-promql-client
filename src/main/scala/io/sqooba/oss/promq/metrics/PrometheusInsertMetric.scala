package io.sqooba.oss.promq.metrics

import io.circe.Encoder
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }
import io.circe.Decoder

/*
 * According to the documentation the format of the data in the /api/v1/{import/export} is not
 * the same as the one returned by the query and query_range endpoint
 * This class is taking care of representing the import/export structure as well as its serialization
 */
case class PrometheusInsertMetric(metric: MetricHeaders, values: Seq[Double], timestamps: Seq[Long])

object PrometheusInsertMetric {
  // scalastyle:off
  import PrometheusMetrics._
  // scalastyle:on

  // We need that in order for the custom encoder of MetricHeaders to be used by Circe
  implicit val encodeInsertMetric: Encoder[PrometheusInsertMetric] = deriveEncoder[PrometheusInsertMetric]

  // We need that in order for the custom decoder of MetricHeaders to be used by Circe
  implicit val decodeInsertMetric: Decoder[PrometheusInsertMetric] = deriveDecoder[PrometheusInsertMetric]

}
