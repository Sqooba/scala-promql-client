package io.sqooba.oss.promql

import com.typesafe.config.Config
import zio.{Has, RLayer, Task, ZLayer}

/**
 * @param host  server's hostname or ip
 * @param port server's port
 * @param maxPointsPerTimeseries The maximum number of points a prometheus endpoint can return before we have to split queries
 * @param retryNumber Number of retry to perform in case of a failed query
 * @param parallelRequests Number of requests that can be made in parallel when splitting queries
 */
case class PrometheusClientConfig(
  host: String,
  port: Int,
  ssl: Boolean,
  maxPointsPerTimeseries: Int,
  retryNumber: Int,
  parallelRequests: Int
)

object PrometheusClientConfig {

  /**
   * Shameful late addition of the SSL support: there might be a more elegant way of
   * supporting default options, but for now we'll stick with this.
   *
   * We can consider defaulting to true at some point, though the most likely worst case is that
   * clients connect to an https endpoint using http, which will fail.
   *
   * @return true if the config contains `ssl = true`, false otherwise.
   */
  private def readSslFlag(config: Config): Boolean =
    if (config.hasPath("ssl")) {
      config.getBoolean("ssl")
    } else {
      false
    }

  def from(config: Config): Task[PrometheusClientConfig] =
    Task {
      PrometheusClientConfig(
        config.getString("host"),
        config.getInt("port"),
        readSslFlag(config),
        config.getInt("maxPointsPerTimeseries"),
        config.getInt("retryNumber"),
        config.getInt("parallelRequests")
      )
    }

  def fromKebabCase(config: Config): Task[PrometheusClientConfig] =
    Task {
      PrometheusClientConfig(
        config.getString("host"),
        config.getInt("port"),
        readSslFlag(config),
        config.getInt("max-points-per-timeseries"),
        config.getInt("retry-number"),
        config.getInt("parallel-requests")
      )
    }

  /**
   * Create a layer containing a [[PrometheusClientConfig]]. This config is built from a
   * typesafe config.
   */
  val layer: RLayer[Has[Config], Has[PrometheusClientConfig]] =
    ZLayer.fromFunctionM[Has[Config], Throwable, PrometheusClientConfig](config => from(config.get))

  val layerKebabCaseConfig: RLayer[Has[Config], Has[PrometheusClientConfig]] =
    ZLayer.fromFunctionM[Has[Config], Throwable, PrometheusClientConfig](config => fromKebabCase(config.get))
}
