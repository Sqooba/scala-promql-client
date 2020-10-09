package io.sqooba.oss.promq

import java.time.Instant

import scala.annotation.tailrec
import scala.concurrent.duration.{ DurationInt, FiniteDuration }

sealed trait PrometheusQuery

case class InstantQuery(query: String, time: Option[Instant], timeout: Option[Int]) extends PrometheusQuery

case class RangeQuery(query: String, start: Instant, end: Instant, step: Int, timeout: Option[Int])
    extends PrometheusQuery {
  def withDuration(duration: FiniteDuration): RangeQuery = copy(end = start.plusSeconds(duration.toSeconds))
  def shift(duration: FiniteDuration): RangeQuery =
    copy(start = start.plusSeconds(duration.toSeconds), end = end.plusSeconds(duration.toSeconds))
}

// The following queries are not yet supported by the client
case class SeriesQuery(matches: Seq[String], start: Instant, end: Instant)               extends PrometheusQuery
case class LabelsQuery(start: Option[Instant], end: Option[Instant])                     extends PrometheusQuery
case class LabelValuesQuery(label: String, start: Option[Instant], end: Option[Instant]) extends PrometheusQuery

object PrometheusQuery {

  /**
   * In scala 2.12, Product has no 'productElementNames', so we need to be creative
   */
  implicit def formEncode[T <: PrometheusQuery](query: T with Product): Map[String, String] =
    // Betting on the fact that declared fields and productIterator are in the same order :/
    // Hoping Guillaume's tests are comprehensive here...
    (query.getClass.getDeclaredFields.map(_.getName).toList zip query.productIterator.toList).filter {
      case (_, v: Option[Any]) => v.nonEmpty
      case _                   => true
    }.map {
      case (k, Some(v)) => (k, v.toString)
      case (k, v)       => (k, v.toString)
    }.toMap
}

object RangeQuery {

  /**
   * Must be set to the maxPointsPerTimeseries configuration from VictoriaMetrics
   * Otherwise it will return some 422 because of the number of datapoints
   *  See  https://github.com/VictoriaMetrics/VictoriaMetrics/issues/77
   *
   *  We are doing -1 because if end = start + x * step then the server returns us
   *  an additional point (in includes the last point). Hence we include this margin
   */
  val MAX_SAMPLING: Int = 4 - 1

  private[promq] def splitRangeQuery(query: RangeQuery, maxSample: Int = MAX_SAMPLING): List[RangeQuery] = {
    val step         = query.step // in second
    val end          = query.end.getEpochSecond
    val samplingTime = (step * maxSample).seconds

    @tailrec
    def splitIntervals(acc: Seq[RangeQuery], curr: RangeQuery): Seq[RangeQuery] =
      if (curr.start.getEpochSecond >= end) { acc }
      else if (curr.end.getEpochSecond >= end) { acc :+ curr.copy(end = query.end) }
      else { splitIntervals(acc :+ curr, curr.shift(samplingTime)) }
    splitIntervals(Seq(), query.withDuration(samplingTime)).toList
  }
}
