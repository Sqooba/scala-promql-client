package io.sqooba.oss.promql

import com.typesafe.scalalogging.LazyLogging
import io.circe.syntax.EncoderOps
import sttp.client._
import sttp.client.circe._
import metrics._
import io.circe.parser.decode
import PrometheusInsertMetric._

import java.time.Instant
import com.typesafe.config.{ Config, ConfigFactory }
import io.sqooba.oss.promql.PrometheusService.PrometheusService
import zio._
import sttp.client.asynchttpclient.zio.AsyncHttpClientZioBackend
import sttp.client.asynchttpclient.zio.SttpClient
import sttp.model.{ MediaType, Method }

/**
 * This is a "low level" prometheus client, it can be used to communicate with
 * VictoriaMatrics as it speaks the same procotol. It is able to insert and run queries
 * against VM and to return the raw data It supports the response formats described in
 * [https://prometheus.io/docs/prometheus/latest/querying/api/#expression-query-result-formats](the
 * doc).
 *
 * @param config VictoriaMetrics related configuration
 * @param client Sttp backend to perform queries
 */
// scalastyle has some issues with sttp's uri scheme. Disabling it entirely for this file.
// scalastyle:off
class PrometheusClient(
  config: PrometheusClientConfig,
  client: SttpClient
) extends PrometheusService.Service
    with LazyLogging {

  private val scheme = if (config.ssl) "https" else "http"

  private[promql] val endpoint = uri"${scheme}://${config.host}:${config.port}"
  logger.info(s"Sending to endpoint $endpoint")
  private val importEndpoint = endpoint.path("/api/v1/import")
  logger.info(s"Import endpoint is $importEndpoint")

  /**
   * Prometheus is usually pull based (it scrapes the data from the defined sources)
   * This method can be used to push data into prometheus instead
   *
   * @param dataPoints a sequence of metrics to insert
   * @return An error or the number of points that were inserted
   */
  def put(dataPoints: Seq[PrometheusInsertMetric]): IO[PrometheusClientError, Int] = {

    val toPost = dataPoints.map(_.asJson.noSpaces).mkString("\n")
    val request = basicRequest
      .body(toPost)
      .post(importEndpoint)

    SttpClient
      .send(request)
      .refineOrDie {
        case e: Throwable => PrometheusClientError(s"[put] Something went wrong ${e.getLocalizedMessage}")
      }
      .flatMap(resp =>
        if (resp.code.isSuccess) {
          IO.succeed(dataPoints.length)
        } else {
          IO.fail(PrometheusClientError(f"Unable to insert points [${resp.code}]: ${resp.statusText}"))
        }
      )
      .provide(client)
  }

  /**
   * Execute a query against a prometheus compatible endpoint
   *
   * @param query the query to execute
   * @return an error or a decoded prometheus response containing the raw data
   */
  def query(query: PrometheusQuery): IO[PrometheusError, ResponseData] =
    query match {
      case q: InstantQuery => executeInstantQuery(q)
      case q: RangeQuery =>
        val queries = RangeQuery.splitRangeQuery(q, maxSample = config.maxPointsPerTimeseries)
        val responses = ZIO.collectAllParN(config.parallelRequests)(
          queries.map(query => executeRangeQuery(query).retryN(config.retryNumber))
        )

        responses
          .map(_.collect {
            case x: MatrixResponseData => x
          }.foldLeft(MatrixResponseData(List[MatrixMetric]()))((acc, curr) => acc.merge(curr)))

      case q: SeriesQuery      => executeMetaQuery(q, "/api/v1/series")
      case q: LabelsQuery      => executeMetaQuery(q, "/api/v1/labels")
      case q: LabelValuesQuery => executeMetaQuery(q, s"/api/v1/label/${q.label}/values")

      case _ => throw new NotImplementedError("The type of query is not supported yet")
    }

  /**
   * Export the raw data from the database
   * The result is not a valid JSON, but contains a valid JSON per line
   * This is defined in [https://victoriametrics.github.io/#how-to-export-time-series](the doc)
   *
   * @param query to filter the data to export
   * @param from the optional start limit for the exported data
   * @param to to optional end limit for the exported data
   * @return an error or a sequence of raw datapoints
   */
  def export(
    query: String,
    from: Option[Instant],
    to: Option[Instant]
  ): IO[PrometheusClientError, Seq[PrometheusInsertMetric]] = {
    def parseBodyString(body: String) =
      body
        .split("\n")
        .map(decode[PrometheusInsertMetric](_).toOption)
        .collect { case Some(p) => p }
        .toSeq

    def decodeBody(response: Response[Either[String, String]]): IO[PrometheusClientError, Seq[PrometheusInsertMetric]] =
      if (response.code.isSuccess) {
        response.body.fold(
          err => IO.fail(PrometheusClientError(f"Error while getting export body: $err")),
          body => IO.succeed(parseBodyString(body))
        )
      } else {
        IO.fail(PrometheusClientError(f"Unable to export points [${response.code}]: ${response.statusText}"))
      }

    val params = Map(
      "match[]" -> query,
      "from"    -> from.map(ts => f"$ts").getOrElse(""),
      "to"      -> to.map(ts => f"$ts").getOrElse("")
    ).view.filter(_._2.nonEmpty).toMap

    val exportEndpoint = endpoint
      .path("/api/v1/export")
      .params(params)
    logger.info(s"Export endpoint is $exportEndpoint")

    val request = basicRequest.get(exportEndpoint)

    SttpClient
      .send(request)
      .refineOrDie {
        case e: Throwable =>
          PrometheusClientError(s"[export] Something went wrong ${e.getLocalizedMessage}")
      }
      .flatMap(resp => decodeBody(resp))
      .provide(client)
  }

  private def executeInstantQuery(promQuery: InstantQuery): IO[PrometheusError, ResponseData] = {
    val httpQueryEndpoint = endpoint.path("/api/v1/query")
    val body              = promQuery.formEncode()

    val httpQuery = basicRequest
      .body(body, "utf-8")
      .contentType(MediaType.ApplicationXWwwFormUrlencoded)
      .post(httpQueryEndpoint)
      .response(asJson[PrometheusResponse])

    val response = SttpClient.send(httpQuery).provide(client)
    handleQueryError(response)
  }

  private def executeRangeQuery(promQuery: RangeQuery): IO[PrometheusError, ResponseData] = {
    val queryEndpoint = endpoint.path("/api/v1/query_range")
    val body          = promQuery.formEncode()

    val httpQuery = basicRequest
      .contentType(MediaType.ApplicationXWwwFormUrlencoded)
      .body(body, "utf-8")
      .post(queryEndpoint)
      .response(asJson[PrometheusResponse])

    val response = SttpClient.send(httpQuery).provide(client)
    handleQueryError(response)
  }

  private def executeMetaQuery(promQuery: PrometheusQuery, apiEndpoint: String): IO[PrometheusError, ResponseData] = {
    val queryEndpoint = endpoint.path(apiEndpoint)
    val body          = promQuery.formEncode()

    val httpQuery = (promQuery.httpMethod() match {
      case Method.GET =>
        basicRequest
          .method(promQuery.httpMethod(), queryEndpoint.params(body: _*))
      case _ =>
        basicRequest
          .contentType(MediaType.ApplicationXWwwFormUrlencoded)
          .method(promQuery.httpMethod(), queryEndpoint)
          .body(body, "utf-8")
    }).response(promQuery match {
      case _ => asJson[PrometheusResponse]
    })

    val response = for {
      query    <- IO.succeed(httpQuery)
      response <- SttpClient.send(query).provide(client)
    } yield response

    handleQueryError(response)
  }

  private def handleQueryError(
    response: Task[Response[Either[ResponseError[io.circe.Error], PrometheusResponse]]]
  ): IO[PrometheusError, ResponseData] = {
    val result = response.refineOrDie {
      case e: Throwable => PrometheusClientError(s"[query-error] Something went wrong ${e.getLocalizedMessage}")
    }
    result.flatMap(response =>
      response.body.fold(
        error =>
          IO.fail(
            // There is no easy way to log the raw response from the client, it should be easier in sttp 3 (https://github.com/softwaremill/sttp/issues/190)
            PrometheusClientError(f"Unable to execute query: ${error.getLocalizedMessage}")
          ),
        {
          case success: SuccessResponse => IO.succeed(success.data)
          case ErrorResponse(_, errorType, error, warnings) =>
            IO.fail(PrometheusErrorResponse(errorType, error, warnings))
        }
      )
    )
  }

}

object PrometheusClient {
  def live(
    config: PrometheusClientConfig,
    client: SttpClient
  ): ULayer[Has[PrometheusClient]] = ZLayer.succeed(new PrometheusClient(config, client))

  def live(
    config: PrometheusClientConfig
  ): TaskLayer[PrometheusService] =
    Task(config).toLayer ++
      AsyncHttpClientZioBackend.layer() >>> PrometheusClient.live

  def liveFromConfig(
    config: Config
  ): TaskLayer[PrometheusService] =
    Task(config).toLayer >>> liveFromConfig

  def liveFromConfig: RLayer[Has[Config], PrometheusService] =
    PrometheusClientConfig.layer ++ AsyncHttpClientZioBackend.layer() >>>
      PrometheusClient.live

  def live: URLayer[SttpClient with Has[PrometheusClientConfig], PrometheusService] =
    ZLayer
      .fromServiceM[PrometheusClientConfig, SttpClient, Nothing, PrometheusService.Service] { config =>
        ZIO.fromFunction[SttpClient, PrometheusService.Service] { client =>
          new PrometheusClient(config, client)
        }
      }

  def liveDefault: TaskLayer[PrometheusService] =
    liveFromConfig(ConfigFactory.load().getConfig("promql-client"))

}
