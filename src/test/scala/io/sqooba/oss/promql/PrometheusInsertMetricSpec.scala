package io.sqooba.oss.promql

import io.circe.syntax._
import io.circe.parser.decode
import PrometheusTestUtils._
import io.sqooba.oss.promql.metrics.PrometheusInsertMetric
import zio.test.Assertion.{ equalTo, hasField, isSome }
import zio.test._

object PrometheusInsertMetricSpec extends DefaultRunnableSpec {

  val spec = suite("PrometheusInsertMetric")(
    suite("decoder")(
      test("restore the tags") {
        val point         = createInsertPoint()
        val enhancedPoint = point.copy(metric = Map("CustomTags" -> "CustomValue"))
        val encoded       = enhancedPoint.asJson

        assert(
          decode[PrometheusInsertMetric](encoded.toString()).toOption
        )(
          isSome(
            hasField("tags", _.metric("CustomTags"), equalTo("CustomValue"))
          )
        )
      }
    )
  )
}
