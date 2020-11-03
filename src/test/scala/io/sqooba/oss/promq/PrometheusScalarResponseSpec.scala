package io.sqooba.oss.promq

import scala.io.Source
import io.circe.parser.decode
import java.time.Instant

import PrometheusResponse._
import zio.test._
import zio.test.Assertion._

object PrometheusScalarResponseSpec extends DefaultRunnableSpec {

  def isScalarResponse(d: Double) =
    isRight(
      isSubtype[SuccessResponse](
        hasField(
          "data",
          _.data,
          isSubtype[ScalarResponseData](
            equalTo(ScalarResponseData((Instant.ofEpochSecond(1337), d)))
          )
        )
      )
    )
  val isScalarNaN =
    isRight(
      isSubtype[SuccessResponse](
        hasField(
          "data",
          _.data,
          isSubtype[ScalarResponseData](
            hasField(
              "result",
              _.result._2.isNaN,
              isTrue
            )
          )
        )
      )
    )
  val spec = suite("Scalar response decoder")(
    test("decode a integer response") {
      val json            = Source.fromResource("responses/scalars/ScalarInt").mkString
      val decodedResponse = decode[PrometheusResponse](json)

      assert(decodedResponse)(
        isScalarResponse(28d)
      )
    },
    test("decode a double response") {
      val json            = Source.fromResource("responses/scalars/ScalarDouble").mkString
      val decodedResponse = decode[PrometheusResponse](json)

      assert(decodedResponse)(
        isScalarResponse(28.56d)
      )
    },
    test("decode a inf response") {
      val json            = Source.fromResource("responses/scalars/ScalarInfinity").mkString
      val decodedResponse = decode[PrometheusResponse](json)

      assert(decodedResponse)(
        isScalarResponse(Double.PositiveInfinity)
      )
    },
    test("decode a minus inf response") {
      val json            = Source.fromResource("responses/scalars/ScalarMinInfinity").mkString
      val decodedResponse = decode[PrometheusResponse](json)

      assert(decodedResponse)(
        isScalarResponse(Double.NegativeInfinity)
      )
    },
    test("decode a positive hex response") {
      val json            = Source.fromResource("responses/scalars/ScalarPlusHex").mkString
      val decodedResponse = decode[PrometheusResponse](json)

      assert(decodedResponse)(
        isScalarResponse(255)
      )
    },
    test("decode a negative hex response") {
      val json            = Source.fromResource("responses/scalars/ScalarMinusHex").mkString
      val decodedResponse = decode[PrometheusResponse](json)

      assert(decodedResponse)(
        isScalarResponse(-78)
      )
    },
    test("decode a hex response") {
      val json            = Source.fromResource("responses/scalars/ScalarHex").mkString
      val decodedResponse = decode[PrometheusResponse](json)

      assert(decodedResponse)(
        isScalarResponse(155)
      )
    },
    test("decode Nan") {
      val json            = Source.fromResource("responses/scalars/ScalarNan").mkString
      val decodedResponse = decode[PrometheusResponse](json)

      // Double.NaN != Double.Nan so we can't use the isScalarResponse here
      assert(decodedResponse)(
        isScalarNaN
      )
    },
    test("decode negative Nan") {
      val json            = Source.fromResource("responses/scalars/ScalarMinusNan").mkString
      val decodedResponse = decode[PrometheusResponse](json)

      assert(decodedResponse)(
        isScalarNaN
      )

    },
    test("decode positive Nan") {
      val json            = Source.fromResource("responses/scalars/ScalarPlusNan").mkString
      val decodedResponse = decode[PrometheusResponse](json)

      assert(decodedResponse)(
        isScalarNaN
      )

    }
  )

}
