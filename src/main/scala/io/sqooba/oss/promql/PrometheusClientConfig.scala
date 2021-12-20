package io.sqooba.oss.promql

import com.typesafe.config.Config
import zio.{ Has, RLayer, Task, ZLayer }

sealed trait PrometheusClientAuth

case class PrometheusClientAuthBasicCredential(username:String, password: String) extends PrometheusClientAuth
case class PrometheusClientAuthBasicToken(token:String) extends PrometheusClientAuth
case class PrometheusClientAuthBearer(bearer:String) extends PrometheusClientAuth

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
  parallelRequests: Int,
  auth: Option[PrometheusClientAuth]=None
)

object PrometheusClientConfig {

  def decodeAuthConfig(config: Config):Option[PrometheusClientAuth] = {
    if (config.hasPath("auth-basic-credentials")) {
      val subConfig = config.getConfig("auth-basic-credentials")
      val username = subConfig.getString("username")
      val password = subConfig.getString("password")
      Some(PrometheusClientAuthBasicCredential(username, password))
    } else if (config.hasPath("auth-basic-token")) {
      val subConfig = config.getConfig("auth-basic-token")
      val token = subConfig.getString("token")
      Some(PrometheusClientAuthBasicToken(token))
    } else if (config.hasPath("auth-bearer")) {
      val subConfig = config.getConfig("auth-bearer")
      val bearer = subConfig.getString("bearer")
      Some(PrometheusClientAuthBearer(bearer))
    } else None
  }

  def from(config: Config): Task[PrometheusClientConfig] =
    Task {
      PrometheusClientConfig(
        config.getString("host"),
        config.getInt("port"),
        config.getBoolean("ssl"),
        config.getInt("maxPointsPerTimeseries"),
        config.getInt("retryNumber"),
        config.getInt("parallelRequests"),
        decodeAuthConfig(config)
      )
    }

  def fromKebabCase(config: Config): Task[PrometheusClientConfig] =
    Task {
      PrometheusClientConfig(
        config.getString("host"),
        config.getInt("port"),
        config.getBoolean("ssl"),
        config.getInt("max-points-per-timeseries"),
        config.getInt("retry-number"),
        config.getInt("parallel-requests"),
        decodeAuthConfig(config)
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
