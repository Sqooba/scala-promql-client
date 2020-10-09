package io.sqooba.oss.promq

import java.time.Instant

import io.sqooba.oss.promq.metrics._

import scala.concurrent.duration.FiniteDuration

object PrometheusTestUtils {

  def createInsertPoint(values: Seq[Double] = Seq(), ts: Seq[Long] = Seq()): PrometheusInsertMetric =
    PrometheusInsertMetric(
      MetricHeaders(
        "Test_Series",
        "job",
        "testInstance"
      ),
      values = values,
      timestamps = ts
    )

  def createSuccessResponse(start: Instant, values: Seq[String], step: FiniteDuration): SuccessResponse =
    SuccessResponse(
      MatrixResponseData(
        List(
          MatrixMetric(
            MetricHeaders("WGRI_W_10m_Avg", "", "", Map("t_id" -> "115")),
            values.zipWithIndex.map {
              case (value, idx) => (start.plusSeconds(idx * step.toSeconds), value)
            }.toList
          )
        )
      ),
      None
    )
}
