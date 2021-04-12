package io.sqooba.oss.promql

import sttp.model.Method

import java.time.Instant
import scala.annotation.tailrec
import scala.concurrent.duration.FiniteDuration

import scala.collection.immutable

// scalastyle:off multiple.string.literals
sealed trait PrometheusQuery {
  def formEncode(): immutable.Seq[(String, String)]
  def httpMethod(): Method = Method.POST
}

/**
 * A query to get the value at a single point in time.
 *
 * @param query
 * @param time
 * @param timeout the finest resolution is seconds
 */
final case class InstantQuery(
  query: String,
  time: Option[Instant],
  timeout: Option[FiniteDuration]
) extends PrometheusQuery {
  override def formEncode(): immutable.Seq[(String, String)] =
    immutable
      .Seq[Option[(String, String)]](
        Some(("query", query)),
        time.map(t => ("time", t.toString)),
        timeout.map(t => ("timeout", s"${t.toSeconds}s"))
      )
      .collect {
        case Some((k, v)) => (k, v)
      }
}

/**
 * Query to get the values over a range of time.
 * @param query
 * @param start
 * @param end
 * @param step     the finest resolution is seconds
 * @param timeout  the finest resolution is seconds
 */
case class RangeQuery(
  query: String,
  start: Instant,
  end: Instant,
  step: FiniteDuration,
  timeout: Option[FiniteDuration]
) extends PrometheusQuery {
  override def formEncode(): immutable.Seq[(String, String)] =
    immutable
      .Seq[Option[(String, String)]](
        Some(("query", query)),
        Some(("start", start.toString)),
        Some(("end", end.toString)),
        Some(("step", s"${step.toSeconds}s")),
        timeout.map(t => ("timeout", s"${t.toSeconds}s"))
      )
      .collect {
        case Some((k, v)) => (k, v)
      }
  def withDuration(duration: FiniteDuration): RangeQuery = copy(end = start.plusSeconds(duration.toSeconds))
  def shift(duration: FiniteDuration): RangeQuery =
    copy(start = start.plusSeconds(duration.toSeconds), end = end.plusSeconds(duration.toSeconds))
}

case class SeriesQuery(matches: collection.Seq[String], start: Instant, end: Instant) extends PrometheusQuery {
  override def formEncode(): immutable.Seq[(String, String)] = {
    // there have to be multiple arguments with the same name, and brackets..

    val xs = immutable.Seq(
      ("start", start.toString),
      ("end", end.toString)
    )
    xs ++ (matches.map(s => ("match[]", s)))
  }
}

case class LabelsQuery(matches: Option[collection.Seq[String]], start: Option[Instant], end: Option[Instant])
    extends PrometheusQuery {
  override def formEncode(): immutable.Seq[(String, String)] =
    immutable
      .Seq[Option[(String, String)]](
        start.map(s => ("start", s.toString)),
        end.map(e => ("end", e.toString))
      )
      .collect {
        case Some((k, v)) => (k, v)
      } ++ (matches.getOrElse(Seq()).map(s => ("match[]", s)))

}

case class LabelValuesQuery(
  label: String,
  matches: Option[collection.Seq[String]],
  start: Option[Instant],
  end: Option[Instant]
) extends PrometheusQuery {

  override def httpMethod(): Method = Method.GET

  override def formEncode(): immutable.Seq[(String, String)] =
    immutable
      .Seq[Option[(String, String)]](
        Some(("label", label)),
        start.map(s => ("start", s.toString)),
        end.map(e => ("end", e.toString))
      )
      .collect {
        case Some((k, v)) => (k, v)
      } ++ (matches.getOrElse(Seq()).map(s => ("match[]", s)))
}

object LabelValuesQuery {
  def apply(label: String, start: Option[Instant], end: Option[Instant]): LabelValuesQuery =
    LabelValuesQuery(label, None, start, end)

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
  val DEFAULT_MAX_SAMPLING: Int = 30000

  private[promql] def splitRangeQuery(query: RangeQuery, maxSample: Int = DEFAULT_MAX_SAMPLING): List[RangeQuery] = {
    val end          = query.end.getEpochSecond
    val samplingTime = (query.step * (maxSample - 1).toLong)

    @tailrec
    def splitIntervals(acc: Seq[RangeQuery], curr: RangeQuery): Seq[RangeQuery] =
      if (curr.start.getEpochSecond >= end) {
        acc
      } else if (curr.end.getEpochSecond >= end) {
        acc :+ curr.copy(end = query.end)
      } else {
        splitIntervals(acc :+ curr, curr.shift(samplingTime))
      }
    splitIntervals(Seq(), query.withDuration(samplingTime)).toList
  }
}
