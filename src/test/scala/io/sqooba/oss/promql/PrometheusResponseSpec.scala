package io.sqooba.oss.promql

import scala.io.Source
import io.circe.generic.auto._
import io.circe.parser.decode
import java.time.Instant

import PrometheusResponse._
import PrometheusTestUtils.createSuccessResponse
import metrics._
import zio.test._
import zio.test.Assertion._

import scala.concurrent.duration.DurationInt

object PrometheusResponseSpec extends DefaultRunnableSpec {

  val spec = suite("PrometheusResponse decoder")(
    test("detect an error response") {
      val json            = Source.fromResource("responses/PrometheusResponseError").mkString
      val decodedResponse = decode[PrometheusResponse](json)

      assert(decodedResponse)(isRight(isSubtype[ErrorResponse](anything)))
    },
    test("detect a success response") {
      val json            = Source.fromResource("responses/PrometheusResponseSuccess").mkString
      val decodedResponse = decode[PrometheusResponse](json)

      assert(decodedResponse)(isRight(isSubtype[SuccessResponse](anything)))
    },
    test("decode a Vector response") {
      val json            = Source.fromResource("responses/PrometheusResponseSuccessVector").mkString
      val decodedResponse = decode[PrometheusResponse](json)

      assert(decodedResponse)(
        isRight(
          isSubtype[SuccessResponse](
            hasField(
              "data.result",
              _.data,
              isSubtype[VectorResponseData](
                hasField(
                  "result",
                  _.result,
                  hasFirst(
                    equalTo(
                      VectorMetric(
                        Map("job" -> "prometheus", "instance" -> "testInstance", "__name__" -> "up"),
                        (Instant.ofEpochSecond(1337), "1")
                      )
                    )
                  ) &&
                    hasSize(equalTo(1))
                )
              )
            )
          )
        )
      )
    },
    test("decode a vector with empty metric") {
      val json            = Source.fromResource("responses/PrometheusResponseSuccessEmptyVector").mkString
      val decodedResponse = decode[PrometheusResponse](json)

      assert(decodedResponse)(
        isRight(
          isSubtype[SuccessResponse](
            hasField(
              "data",
              _.data,
              isSubtype[VectorResponseData](
                equalTo(VectorResponseData(List(VectorMetric(Map(), (Instant.ofEpochSecond(1337), "445100")))))
              )
            )
          )
        )
      )
    },
    test("decode a vector without any metric") {
      val json            = Source.fromResource("responses/PrometheusResponseNoMetric").mkString
      val decodedResponse = decode[PrometheusResponse](json)

      assert(decodedResponse)(
        isRight(
          isSubtype[SuccessResponse](
            hasField(
              "data",
              _.data,
              isSubtype[VectorResponseData](
                equalTo(VectorResponseData(List(VectorMetric(Map(), (Instant.ofEpochSecond(1337), "445100")))))
              )
            )
          )
        )
      )
    },
    test("decode a vector with timestamps containing ms") {
      val json            = Source.fromResource("responses/PrometheusResponseMs").mkString
      val decodedResponse = decode[PrometheusResponse](json)

      assert(decodedResponse)(
        isRight(
          isSubtype[SuccessResponse](
            hasField(
              "data",
              _.data,
              isSubtype[VectorResponseData](
                equalTo(VectorResponseData(List(VectorMetric(Map(), (Instant.ofEpochMilli(1602511200123L), "100000")))))
              )
            )
          )
        )
      )
    },
    test("decode a string response") {
      val json            = Source.fromResource("responses/PrometheusResponseSuccessString").mkString
      val decodedResponse = decode[PrometheusResponse](json)

      assert(decodedResponse)(
        isRight(
          isSubtype[SuccessResponse](
            hasField(
              "data",
              _.data,
              isSubtype[StringResponseData](
                equalTo(StringResponseData((Instant.ofEpochSecond(1337), "testing")))
              )
            )
          )
        )
      )
    },
    test("correctly add the tags into the decoded object") {
      val json            = Source.fromResource("ranges/PrometheusResponseWGRIWAVG").mkString
      val decodedResponse = decode[PrometheusResponse](json)

      assert(decodedResponse)(
        isRight(
          isSubtype[SuccessResponse](
            hasField(
              "data",
              _.data,
              isSubtype[MatrixResponseData](
                hasField(
                  "result",
                  (m: MatrixResponseData) => m.result,
                  hasFirst(
                    hasField(
                      "metric",
                      (m: MatrixMetric) => m.metric,
                      equalTo(
                        Map(
                          "__name__"   -> "WGRI_W_10m_Avg",
                          "id"         -> "122",
                          "entityType" -> "t",
                          "instance"   -> "VMLoader",
                          "job"        -> "TimeSeriesDatabaseSink"
                        )
                      )
                    )
                  ) && hasSize(equalTo(2))
                )
              )
            )
          )
        )
      )
    },
    test("decode using seconds and not ms") {
      val json            = Source.fromResource("ranges/PrometheusResponseWGRIWAVG").mkString
      val decodedResponse = decode[SuccessResponse](json)

      /* If the implicit decode to use ofEpochSecond instead of ofEpochMilli is not used
      then the date will always be in 1970. This is a test to make sure that all our timestamps
      are correctly decoded using ofEpochSeconds */
      assert(decodedResponse)(
        isRight(
          hasField(
            "data",
            _.data,
            isSubtype[MatrixResponseData](
              hasField(
                "head value timestamp after",
                _.result.head.values.head._1.isAfter(Instant.parse("2000-01-01T00:00:00Z")),
                isTrue
              )
            )
          )
        )
      )
    },
    test("correctly merge the data") {
      val firstResponse = MatrixResponseData(
        List(
          MatrixMetric(
            Map("__name__" -> "WGRI_W_10m_Avg", "id" -> "122", "entityType" -> "t"),
            List((Instant.ofEpochMilli(0), "150"))
          )
        )
      )
      val secondResponse = MatrixResponseData(
        List(
          MatrixMetric(
            Map("__name__" -> "WGRI_W_10m_Avg", "id" -> "122", "entityType" -> "t"),
            List((Instant.ofEpochMilli(10000), "1000"))
          )
        )
      )
      val merged = firstResponse.merge(secondResponse)
      assert(merged.result.length)(equalTo(1)) &&
      assert(merged.result.head.values)(
        equalTo(
          List(
            (Instant.ofEpochMilli(0), "150"),
            (Instant.ofEpochMilli(10000), "1000")
          )
        )
      )
    },
    test("remove duplicated point") {
      val firstResponse = MatrixResponseData(
        List(
          MatrixMetric(
            Map("__name__" -> "WGRI_W_10m_Avg", "id" -> "122", "entityType" -> "t"),
            List((Instant.ofEpochMilli(0), "150"))
          )
        )
      )
      val secondResponse = MatrixResponseData(
        List(
          MatrixMetric(
            Map("__name__" -> "WGRI_W_10m_Avg", "id" -> "122", "entityType" -> "t"),
            List((Instant.ofEpochMilli(0), "150"), (Instant.ofEpochMilli(10000), "1000"))
          )
        )
      )
      val merged = firstResponse.merge(secondResponse)
      assert(merged.result.length)(equalTo(1)) &&
      assert(merged.result.head.values)(
        equalTo(
          List(
            (Instant.ofEpochMilli(0), "150"),
            (Instant.ofEpochMilli(10000), "1000")
          )
        )
      )
    },
    test("merge the warnings") {
      val first  = createSuccessResponse(Instant.ofEpochMilli(0), Seq("1"), 10.minutes)
      val second = createSuccessResponse(Instant.ofEpochMilli(10.minutes.toMillis), Seq("2"), 10.minutes)
      assert(first.merge(second).get.warnings)(isNone)

      assert(first.copy(warnings = Some(List("first"))).merge(second).get.warnings)(isSome(equalTo(List("first"))))
      assert(
        first.copy(warnings = Some(List("first"))).merge(second.copy(warnings = Some(List("second")))).get.warnings
      )(
        isSome(equalTo(List("first", "second")))
      )

      assert(first.merge(second.copy(warnings = Some(List("second")))).get.warnings)(isSome(equalTo((List("second")))))
    }
  )

}
