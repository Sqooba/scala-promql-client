package io.sqooba.oss.utils

import io.sqooba.oss.promql.PrometheusService
import io.sqooba.oss.promql.PrometheusService.PrometheusService
import io.sqooba.oss.promql.metrics.PrometheusInsertMetric
import zio.duration.durationInt
import zio.{ IO, ZIO }

import java.time.Instant
import scala.concurrent.duration.FiniteDuration

object Utils {

  def randomOtherLabelTS(
    start: Instant,
    end: Instant,
    tags: Map[String, String],
    step: FiniteDuration,
    num: Integer
  ): ZIO[PrometheusService, Throwable, PrometheusInsertMetric] =
    Range(0, num).map { i =>
      val tag_set =
        tags.map(lv => (if (lv._1 == "__name__") lv._1 else lv._1.concat(i.toString), lv._2.concat(i.toString)))
      insertFakePercentage(start, end, tag_set, step)
    }.reduce((ts1, ts2) => ts1 *> ts2)

  def insertFakePercentage(
    start: Instant,
    end: Instant,
    tags: Map[String, String],
    step: FiniteDuration
  ): ZIO[PrometheusService, Throwable, PrometheusInsertMetric] = {
    require(tags.keySet.contains("__name__"))
    require(start.plusSeconds(step.toSeconds).isBefore(end))

    val min = 0
    val max = 100

    val (timestamp, values) = randomValues(min, max, start, end, step)
    val metrics = PrometheusInsertMetric(
      tags,
      values,
      timestamp
    )
    // Thread.sleep is ugly, but data might take some time before being available in Prometheus.
    // We can't use ZIO.sleep because the test clock is provided and must be managed manually
    PrometheusService.put(Seq(metrics)) *> IO.effect(Thread.sleep(5.seconds.toMillis)) *> IO.succeed(metrics)
  }

  private def randomValues(
    min: Int,
    max: Int,
    start: Instant,
    end: Instant,
    step: FiniteDuration
  ): (Seq[Long], Seq[Double]) = {
    val numberOfPoints =
      ((end.toEpochMilli - start.toEpochMilli) / step.toMillis).toInt
    val points = (0 until numberOfPoints)
      .map(idx => (start.plusMillis(idx * step.toMillis), randomBetween(min, max)))
    (points.map(_._1.toEpochMilli), points.map(_._2.toDouble))
  }

  // scala.util.Random.between does not exist in 2.12
  private def randomBetween(start: Int, end: Int) = {
    val rnd = new scala.util.Random
    start + rnd.nextInt((end - start) + 1)
  }
}
