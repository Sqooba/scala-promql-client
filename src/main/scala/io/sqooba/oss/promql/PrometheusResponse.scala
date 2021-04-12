package io.sqooba.oss.promql

import java.time.Instant
import cats.syntax.functor._
import io.circe.{ Decoder, DecodingFailure, HCursor }
import io.circe.generic.auto._
import io.sqooba.oss.promql.metrics.Label.stringDecode
import metrics._

import scala.util.{ Failure, Success, Try }

/**
 * This file contains all the different response data types that can be retrieved from
 * VictoriaMetrics. All the formats are described in
 * https://prometheus.io/docs/prometheus/latest/querying/api/.
 *
 * @note Query errors are already handled in the client.
 */
sealed trait ResponseData

/**
 * A class to represent the results of a range_query to VictoriaMetrics
 * It will contains a sequence of metrics, associated with their headers (name and tag)
 */
final case class MatrixResponseData(result: List[MatrixMetric]) extends ResponseData {

  /**
   * Here we have a specific case to handle. When we split queries, the last point of one query might be the same (will be)
   * the same as the first of the following query (prometheus's ranges are inclusive) *
   */
  def merge(other: MatrixResponseData): MatrixResponseData =
    MatrixResponseData(
      (result ++ other.result)
        .groupBy(_.metric)
        .map {
          case (headers, values) =>
            MatrixMetric(
              headers,
              // We don't want to duplicate those points because we want the query splitting to be as transparent as possible
              // In order to be as explicit as possible, pattern matching is used, but we might use a mutable LinkedHashSet
              // It should provide O(1) complexity for insert and duplication search
              // In case of conflict on the value, the last one is kept
              values.map(_.values).foldLeft(List(): List[(Instant, String)]) {
                case (accHead :+ accLast, first :: values) if accLast._1 == first._1 => (accHead :+ first) ++ values
                case (acc, values)                                                   => acc ++ values
              }
            )
        }
        .toList
    )
}

/**
 * This class will contains a list a single pairs (timestamp, value) for the queried metrics
 */
final case class VectorResponseData(result: List[VectorMetric]) extends ResponseData

/**
 * Represents a single datapoint (with no headers) with a numeric value
 */
final case class ScalarResponseData(result: (Instant, Double)) extends ResponseData

/**
 * Represents a single datapoint (with no headers) with a string value
 */
final case class StringResponseData(result: (Instant, String)) extends ResponseData

/**
 * Represents a list of Strings (e.g. labels, label values )
 */
final case class StringListResponseData(data: List[String]) extends ResponseData

/**
 * Represents a list of String identifier pairs. (e.g. series queries)
 */
final case class MetricListResponseData(data: List[Map[String, String]]) extends ResponseData

/**
 */
final case class EmptyResponseData() extends ResponseData

object ResponseData {

  // WARNING: Don't remove this import even if IntelliJ considers it unused. It's needed
  // by circe's decoding.
  // scalastyle:off
  import metrics.PrometheusMetrics._
  import metrics.Scalar._
  // scalastyle:on

  /**
   * The response need to be decoded with respect to the `resultType` field of the received JSON
   * This field will describe the kind of results that will be contained in the response
   */
  implicit val decodePrometheusResponseData: Decoder[ResponseData] = Decoder.instance { h =>
    if (h.downField("resultType").succeeded) {
      val dataDecoder =
        h
          .downField("resultType")
          .as[String]
          .flatMap {
            case "matrix" => Right(Decoder[MatrixResponseData].widen)
            case "vector" => Right(Decoder[VectorResponseData].widen)
            case "scalar" => Right(Decoder[ScalarResponseData].widen)
            case "string" => Right(Decoder[StringResponseData].widen)
            case _        => Left(DecodingFailure("Unable to find decoder", Nil))
          }
      dataDecoder.flatMap(
        _.apply(h)
      )

    } else {
      /* to treat the current Cursor position as an Array using case classes and DerivedDecoder , we
         have to move up to the top level and then descend into the "data" array.
       */
      val upCursor    = h.up.asInstanceOf[HCursor]
      val arrayCursor = h.values;

      val dataDecoder = {
        if (arrayCursor.get.isEmpty) {
          Right(Decoder[EmptyResponseData].widen)
        } else if (arrayCursor.get.head.isObject) {
          Right(Decoder[MetricListResponseData].widen)
        } else if (arrayCursor.get.head.isString) {
          Right(Decoder[StringListResponseData].widen)
        } else {
          Left(DecodingFailure("Error decoding `data response as a List of Something", Nil))
        }
      }

      dataDecoder.flatMap(
        _.apply(upCursor)
      )
    }
  }
}

// Private trait because only the client should deal with this and then return data
// types (see below).
sealed trait PrometheusResponse

case class SuccessResponse(
  data: ResponseData,
  warnings: Option[Seq[String]]
) extends PrometheusResponse {

  /**
   * It is often helpful being able to merge responses together, this method is taking care of performing this task
   * Some responses can't be merged, this is why this method might end up returning a Failure instead of a Success
   * @param other  The response to merge
   * @return either a success containing the merged responses, or a failure if no merge was possible
   */
  def merge(other: SuccessResponse): Try[SuccessResponse] =
    (data, other.data) match {
      case (x @ MatrixResponseData(_), y @ MatrixResponseData(_)) =>
        val data = x.merge(y)
        val allWarnings = (warnings, other.warnings) match {
          case (Some(a), Some(b)) => Some(a ++ b)
          case (Some(a), None)    => Some(a)
          case (None, Some(a))    => Some(a)
          case (None, None)       => None
        }

        Success(SuccessResponse(data, allWarnings))
      case _ => Failure(new Exception("Unable to merge responses"))
    }
}

case class ErrorResponse(
  data: Option[ResponseData],
  errorType: String,
  error: String,
  warnings: Option[Seq[String]]
) extends PrometheusResponse

object PrometheusResponse {

  /**
   * The response need to be decoded with respect to the `status` field of the received
   * JSON It can either be an error or a success, in case of an error it will contain
   * only query or results error. Protocol and transport errors need to be handled
   * before the decoding
   */
  implicit val decodePromResponse: Decoder[PrometheusResponse] = Decoder.instance { h =>
    val responseDecoder = h
      .downField("status")
      .as[String]
      .flatMap {
        case "success"      => Right(Decoder[SuccessResponse].widen)
        case "error"        => Right(Decoder[ErrorResponse].widen)
        case status: String => Left(DecodingFailure(f"Unable to find decoder for status $status.", Nil))
      }
    responseDecoder.flatMap(_.apply(h))
  }
}
