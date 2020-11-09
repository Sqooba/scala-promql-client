package io.sqooba.oss.promql

import zio._
import io.sqooba.oss.promql.metrics.PrometheusInsertMetric
import java.time.Instant

/**
 * ZIO-environment service definition
 */
object PrometheusService {
  type PrometheusService = Has[PrometheusService.Service]

  /**
   * The definition of the PrometheusService trait
   */
  trait Service {
    def put(dataPoints: Seq[PrometheusInsertMetric]): IO[PrometheusClientError, Int]
    def query(query: PrometheusQuery): IO[PrometheusError, ResponseData]
    def export(
      query: String,
      from: Option[Instant],
      to: Option[Instant]
    ): IO[PrometheusClientError, Seq[PrometheusInsertMetric]]
  }

  /*
    Those helpers are here to easily access and construct effects from the Service
    They can be seen as IO[E, A] => ZIO[PrometheusService, E, A]
   */

  /**
   * Insert points into Prometheus
   *
   * @param dataPoints A collection of [[io.sqooba.oss.promql.metrics.PrometheusInsertMetric]] to insert
   * @return An effect resulting in an Int, containing the number of points successfully inserted.
   *
   *         A failed effect, if an exception occured when connecting to the target host or when writing the
   *         points inside Prometheus
   *
   *         Know exceptions are converted to one of [[PrometheusClientError]]. Other exceptions are kept unchanged.
   */
  def put(dataPoints: Seq[PrometheusInsertMetric]): ZIO[PrometheusService, PrometheusClientError, Int] =
    ZIO.accessM(_.get.put(dataPoints))

  /**
   * Run a query against a Prometheus backend
   *
   * @param query A [[PrometheusQuery]] to run
   * @return An effect resulting in a [[ResponseData]], containing the data returned by the query
   *
   *         A failed effect, if an exception occured when connecting to the target host or when running the
   *         query on the backend, the expected errors will be converted to a type of [[PrometheusError]].
   *         Other exceptions are kept unchanged.
   */
  def query(query: PrometheusQuery): ZIO[PrometheusService, PrometheusError, ResponseData] =
    ZIO.accessM(_.get.query(query))

  /**
   * Run an export query against a Prometheus backend
   *
   * @param query A raw PromQL query
   * @param from An optional start date for the export
   * @param to An optional end date for the export
   * @return An effect resulting in a sequence of [[io.sqooba.oss.promql.metrics.PrometheusInsertMetric]], containing the data returned by export
   *
   *         A failed effect, if an exception occured when connecting to the target host or when running the
   *         query on the backend, the expected errors will be converted to a type of [[PrometheusClientError]].
   *         Other exceptions are kept unchanged.
   */
  def export(
    query: String,
    from: Option[Instant],
    to: Option[Instant]
  ): ZIO[PrometheusService, PrometheusClientError, Seq[PrometheusInsertMetric]] =
    ZIO.accessM(_.get.export(query, from, to))
}
