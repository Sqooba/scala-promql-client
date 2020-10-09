package io.sqooba.oss.promq

import io.circe.syntax._
import io.circe.parser.decode
import io.circe.optics.JsonPath.root
import PrometheusTestUtils._
import io.sqooba.oss.promq.metrics.PrometheusInsertMetric
import zio.test.Assertion.{ equalTo, hasField, isNone, isSome }
import zio.test._

object PrometheusInsertMetricSpec extends DefaultRunnableSpec {

  val spec = suite("PrometheusInsertMetric")(
    suite("encoder")(
      test("flatten the tags field") {
        val point         = createInsertPoint()
        val enhancedPoint = point.copy(metric = point.metric.copy(tags = Map("CustomTags" -> "CustomValue")))
        val encoded       = enhancedPoint.asJson

        assert(root.metric.tags.json.getOption(encoded))(isNone) &&
        assert(
          root.metric.CustomTags.string.getOption(encoded)
        )(isSome(equalTo("CustomValue")))
      }
    ),
    suite("decoder")(
      test("restore the tags") {
        val point         = createInsertPoint()
        val enhancedPoint = point.copy(metric = point.metric.copy(tags = Map("CustomTags" -> "CustomValue")))
        val encoded       = enhancedPoint.asJson

        assert(
          decode[PrometheusInsertMetric](encoded.toString()).toOption
        )(
          isSome(
            hasField("tags", _.metric.tags("CustomTags"), equalTo("CustomValue"))
          )
        )
      }
    )
  )
}
