package io.sqooba.oss.promq

import com.typesafe.config.Config

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
  maxPointsPerTimeseries: Int,
  retryNumber: Int,
  parallelRequests: Int
)

object PrometheusClientConfig {

  def from(
    config: Config
  ): PrometheusClientConfig =
    PrometheusClientConfig(
      config.getString("host"),
      config.getInt("port"),
      config.getInt("maxPointsPerTimeseries"),
      config.getInt("retryNumber"),
      config.getInt("parallelRequests")
    )
}
