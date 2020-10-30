package io.sqooba.oss.promq

import io.circe.syntax._
import io.circe.parser.decode
import PrometheusTestUtils._
import sttp.client.{ Response, StringBody }
import java.time.Instant

import io.sqooba.oss.promq.metrics.{ MatrixMetric, MetricHeaders, PrometheusInsertMetric }
import org.scalatest.Assertions.fail
import sttp.client.asynchttpclient.zio.AsyncHttpClientZioBackend
import sttp.model.StatusCode
import sttp.model.Uri.QuerySegment.KeyValue
import zio._
import zio.test.Assertion._
import zio.test._

import sttp.client.asynchttpclient.zio.stubbing._
import scala.concurrent.duration.DurationInt
import scala.io.Source

object PrometheusClientSpec extends DefaultRunnableSpec {
  private val emptySequence = Seq(createInsertPoint(), createInsertPoint())
  private val config =
    PrometheusClientConfig("test", port = 12, maxPointsPerTimeseries = 1000, retryNumber = 1, parallelRequests = 5)
  private val env = (ZLayer.succeed(config) ++ AsyncHttpClientZioBackend.stubLayer) >+> PrometheusClient.live

  val spec = suite("VictoriaMetricsClient")(
    suite("put")(
      testM("create a stream of JSON to insert data") {
        val request = PrometheusService.put(emptySequence)
        val scenario = for {
          _ <- whenRequestMatches(_.body match {
                 case StringBody(s, _, _) => s.split("\n").length == 2
                 case _                   => false
               }).thenRespondOk
          resp <- request
        } yield resp

        assertM(scenario.provideLayer(env))(equalTo(2))
      },
      testM("correctly send the serialized data") {
        val customSequence = emptySequence
          .map(point => point.copy(metric = point.metric.copy(tags = Map("customtag" -> "value"))))

        val scenario = for {
          _ <- whenRequestMatches(req =>
                 req.body match {
                   case StringBody(s, _, _) =>
                     val dataPoints = s.split("\n")
                     dataPoints.length == 2 &&
                     dataPoints.zipWithIndex.forall {
                       case (point, idx) =>
                         val decoded = decode[PrometheusInsertMetric](point).toOption.get
                         decoded == customSequence(idx) &&
                         decoded.metric.tags("customtag") == "value"
                     }
                   case _ => false
                 }
               ).thenRespondOk
          resp <- PrometheusService.put(customSequence)
        } yield resp

        val effect = scenario.provideLayer(env)
        assertM(effect)(equalTo(2))
      },
      testM("return the number of inserted points") {
        val scenario = for {
          _    <- whenAnyRequest.thenRespondOk
          resp <- PrometheusService.put(emptySequence)
        } yield resp

        val effect = scenario.provideLayer(env)

        assertM(effect)(equalTo(emptySequence.length))
      }
    ),
    suite("export")(
      testM("correctly add the parameters") {
        val scenario = for {
          _ <- whenAnyRequest
                 .thenRespond(emptySequence.map(_.asJson.noSpaces).mkString("\n"))
          resp <- PrometheusService.export("""{__name__!=""}""", None, None)
        } yield resp

        val effect = scenario.provideLayer(env)

        assertM(effect)(equalTo(emptySequence))
      },
      testM("include the start and end timestamp") {
        val start = Instant.parse("2020-08-01T00:00:00Z")
        val end   = Instant.parse("2020-08-08T00:00:00Z")
        val data  = emptySequence

        val scenario = for {
          _ <- whenRequestMatches { req =>
                 val params = req.uri.querySegments.collect {
                   case KeyValue(k, v, _, _) => k -> v
                 }.toMap

                 params == Map(
                   "match[]" -> """{__name__!=""}""",
                   "from"    -> f"$start",
                   "to"      -> f"$end"
                 )
               }
                 .thenRespond(data.map(_.asJson.noSpaces).mkString("\n"))
          resp <- PrometheusService.export("""{__name__!=""}""", Some(start), Some(end))
        } yield resp

        val effect = scenario.provideLayer(env)
        assertM(effect)(equalTo(data))
      }
    ),
    suite("client")(
      testM("correctly put application errors in the type") {
        val start = Instant.parse("2020-08-01T00:00:00Z")
        val end   = Instant.parse("2020-08-08T00:00:00Z")

        val scenario = for {
          _ <- whenAnyRequest.thenRespond(
                 Right(
                   decode[PrometheusResponse](
                     Source
                       .fromResource("responses/PrometheusResponseError")
                       .mkString
                   ).toOption.get
                 )
               )
          resp <- PrometheusService.query(RangeQuery("""{__name__!=""}""", start, end, step = 10, None)).run
        } yield resp

        val effect = scenario.provideLayer(env)
        assertM(effect)(
          fails(
            equalTo(
              PrometheusErrorResponse("TestErrorType", "This is a custom error message", None)
            )
          )
        )
      },
      testM("reports HTTP error") {
        val start = Instant.parse("2020-08-01T00:00:00Z")
        val end   = Instant.parse("2020-08-08T00:00:00Z")
        val scenario = for {
          _    <- whenAnyRequest.thenRespondServerError()
          resp <- PrometheusService.query(RangeQuery("""{__name__!=""}""", start, end, step = 10, None)).run
        } yield resp

        val effect = scenario.provideLayer(env)

        assertM(effect)(
          fails(isSubtype[PrometheusClientError](anything))
        )
      },
      testM("concatenate the datapoint") {
        val start       = Instant.parse("2020-08-01T00:00:00Z")
        val step        = 10.minutes
        val stepSeconds = step.toSeconds.toLong

        /**
         * This test is creating a very prometheus specific condition.
         * When splitting the request, the last data point of the Nth query will be the same as
         * the first datapoint of the N+1 query, we need to take care of that during a merge
         *  because timestamp should not be duplicated (query splitting should be transparent)
         */
        val scenario = for {
          _ <-
            whenAnyRequest.thenRespondWrapped { req =>
              IO {
                req.body match {
                  case StringBody(s, _, _) if s.contains("2020-08-01T00%3A00%3A00Z") =>
                    // This is the first respond, and the last point of (timestamp, 3) is the same as the first of the second response
                    Response(Right(createSuccessResponse(start, Seq("1", "2", "3"), step)), StatusCode.Ok, "", Nil, Nil)
                  case StringBody(_, _, _) =>
                    // This is the second respond, and the first point of (timestamp, 3) is the same as the last of the first response
                    Response(
                      Right(createSuccessResponse(start.plusSeconds(2 * stepSeconds), Seq("3", "4", "5"), step)),
                      StatusCode.Ok,
                      "",
                      Nil,
                      Nil
                    )
                  case _ => fail("We are expecting a string body")
                }
              }
            }
          resp <- PrometheusService.query(
                    RangeQuery(
                      """WGRI_W_10m_Avg""",
                      start,
                      start.plusSeconds(5 * stepSeconds),
                      step = stepSeconds.intValue(),
                      None
                    )
                  )
        } yield resp

        val env = (ZLayer.succeed(
          config.copy(maxPointsPerTimeseries = 3)
        ) ++ AsyncHttpClientZioBackend.stubLayer) >+> PrometheusClient.live
        val effect = scenario.provideLayer(env)

        assertM(effect)(
          equalTo(
            MatrixResponseData(
              List(
                MatrixMetric(
                  MetricHeaders("WGRI_W_10m_Avg", "", "", Map("t_id" -> "115")),
                  List(
                    (start, "1"),
                    (start.plusSeconds(stepSeconds), "2"),
                    (start.plusSeconds(2 * stepSeconds), "3"),
                    (start.plusSeconds(3 * stepSeconds), "4"),
                    (start.plusSeconds(4 * stepSeconds), "5")
                  )
                )
              )
            )
          )
        )
      }
    )
  )
}
