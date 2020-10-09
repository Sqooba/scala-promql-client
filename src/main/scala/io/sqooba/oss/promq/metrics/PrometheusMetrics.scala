package io.sqooba.oss.promq.metrics

import java.time.Instant
import io.circe.Decoder
import scala.util.Try
import io.circe.optics.JsonPath.root
import io.circe.Encoder

case class MetricHeaders(`__name__`: String, job: String, instance: String, tags: Map[String, String] = Map())
case class MatrixMetric(metric: MetricHeaders, values: List[(Instant, String)])
case class VectorMetric(metric: MetricHeaders, value: (Instant, String))

object PrometheusMetrics {

  // Prometheus is cutting the timestamp at the second, not ms
  implicit val secondInstantDecode: Decoder[Instant] = Decoder.decodeLong.emapTry { value =>
    Try(Instant.ofEpochSecond(value))
  }

  implicit val headersDecode: Decoder[MetricHeaders] = Decoder.instance { h =>
    val mandatory = Seq("__name__", "job", "instance")
    val baseDecoder =
      Decoder.forProduct3("__name__", "job", "instance")((name: String, job: String, instance: String) =>
        MetricHeaders(name, job, instance)
      )
    val headers = baseDecoder.apply(h)

    // We now decode the additional field that might be present and create a map of tag from them
    val additional = h.keys.map(_.filterNot(mandatory.contains(_)))
    val decodedTags = additional.map { tagsKey =>
      val mapped = tagsKey.map(tag => tag -> h.downField(tag).as[String])
      mapped.collect {
        case (tag, result) if result.isRight => (tag, result.toOption.get)
      }.toMap
    }

    decodedTags.fold(headers)(tags => headers.map(_.copy(tags = tags)))
  }

  // scalastyle:off
  import io.circe.generic.semiauto._
  // scalastyle:on

  /**
   * We need to flatten the `tags` key inside the JSON of MetricHeaders
   */
  implicit val headersEncode: Encoder[MetricHeaders] = Encoder.instance { headers =>
    val originalEncoder = deriveEncoder[MetricHeaders]
    originalEncoder.mapJson { original =>
      val _metric = root.json
      val _tags   = root.tags.json
      (for {
        metric <- _metric.getOption(original)
        tags   <- _tags.getOption(original)
      } yield {
        val flattenedMetrics =
          metric.deepMerge(tags).mapObject(_.remove("tags")) // flatten and remove the `tags` from `metric`
        _metric.modify(_ => flattenedMetrics)(original)      // replace the old `metric` by the new one
      }).getOrElse(original)

    }(headers)
  }
}
