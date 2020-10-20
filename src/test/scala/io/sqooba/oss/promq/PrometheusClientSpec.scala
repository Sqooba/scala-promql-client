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
import zio.IO
import zio.test.Assertion._
import zio.test._

import scala.concurrent.duration.DurationInt
import scala.io.Source

object PrometheusClientSpec extends DefaultRunnableSpec {
  private val testBackend   = AsyncHttpClientZioBackend.stub
  private val emptySequence = Seq(createInsertPoint(), createInsertPoint())
  private val config =
    PrometheusClientConfig("test", port = 12, maxPointsPerTimeseries = 1000, retryNumber = 1, parallelRequests = 5)

  val spec = suite("VictoriaMetricsClient")(
    suite("put")(
      testM("create a stream of JSON to insert data") {
        val callbackTestCheck = testBackend
          .whenRequestMatches(req =>
            req.body match {
              case StringBody(s, _, _) => s.split("\n").length == 2
              case _                   => false
            }
          )
          .thenRespondOk()

        val client = new PrometheusClient(config, callbackTestCheck)
        val effect = client.put(emptySequence)

        assertM(effect)(equalTo(2))
      },
      testM("correctly send the serialized data") {
        val customSequence = emptySequence
          .map(point => point.copy(metric = point.metric.copy(tags = Map("customtag" -> "value"))))

        val callbackTestCheck = testBackend
          .whenRequestMatches(req =>
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
          )
          .thenRespondOk()

        val client = new PrometheusClient(config, callbackTestCheck)
        assertM(client.put(customSequence))(equalTo(2))
      },
      testM("return the number of inserted points") {
        val data              = emptySequence
        val callbackTestCheck = testBackend.whenAnyRequest.thenRespondOk()

        val client = new PrometheusClient(config, callbackTestCheck)

        client
          .put(data)
          .map(length => assert(length)(equalTo(data.length)))
      }
    ),
    suite("export")(
      testM("correctly add the parameters") {
        val data = emptySequence
        val callbackTestCheck = testBackend.whenAnyRequest
          .thenRespond(data.map(_.asJson.noSpaces).mkString("\n"))
        val client = new PrometheusClient(config, callbackTestCheck)

        client
          .export("""{__name__!=""}""", None, None)
          .map(d => assert(d)(equalTo(data)))
      },
      testM("include the start and end timestamp") {
        val start = Instant.parse("2020-08-01T00:00:00Z")
        val end   = Instant.parse("2020-08-08T00:00:00Z")
        val data  = emptySequence

        val callbackTestCheck = testBackend.whenRequestMatches { req =>
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

        val client = new PrometheusClient(config, callbackTestCheck)

        client
          .export("""{__name__!=""}""", Some(start), Some(end))
          .map(d => assert(d)(equalTo(data)))
      }
    ),
    suite("client")(
      testM("correctly put application errors in the type") {
        val start = Instant.parse("2020-08-01T00:00:00Z")
        val end   = Instant.parse("2020-08-08T00:00:00Z")

        val callbackTestCheck = testBackend.whenAnyRequest.thenRespond(
          Right(
            decode[PrometheusResponse](
              Source
                .fromResource("responses/PrometheusResponseError")
                .mkString
            ).toOption.get
          )
        )

        val client = new PrometheusClient(config, callbackTestCheck)

        assertM(
          client.query(RangeQuery("""{__name__!=""}""", start, end, step = 10, None)).run
        )(
          fails(
            equalTo(
              PrometheusErrorResponse("TestErrorType", "This is a custom error message", None)
            )
          )
        )
      },
      testM("reports HTTP error") {
        val start             = Instant.parse("2020-08-01T00:00:00Z")
        val end               = Instant.parse("2020-08-08T00:00:00Z")
        val callbackTestCheck = testBackend.whenAnyRequest.thenRespondServerError()

        val client = new PrometheusClient(config, callbackTestCheck)

        assertM(
          client.query(RangeQuery("""{__name__!=""}""", start, end, step = 10, None)).run
        )(
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
        val callbackTestCheck = testBackend.whenAnyRequest.thenRespondWrapped { req =>
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

        val client = new PrometheusClient(config.copy(maxPointsPerTimeseries = 3), callbackTestCheck)
        assertM(
          client.query(
            RangeQuery(
              """WGRI_W_10m_Avg""",
              start,
              start.plusSeconds(5 * stepSeconds),
              step = stepSeconds.intValue(),
              None
            )
          )
        )(
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