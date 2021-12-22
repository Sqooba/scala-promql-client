package io.sqooba.oss.promql

import com.typesafe.config.Config
import com.typesafe.scalalogging.LazyLogging
import zio.{Has, RLayer, Task, ZLayer}

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
 * @param auth Authentication information to connect to Prometheus
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

object PrometheusClientConfig extends LazyLogging {

  private def getSubConfig(config:Config, path:String):Option[Config] = {
    if (config.hasPath(path)) Some(config.getConfig(path))
    else None
  }

  def decodeAuthBasicCredential(config:Config):Option[PrometheusClientAuthBasicCredential] = {
    getSubConfig(config, "auth-basic-credentials").map{subConfig =>
      val username = subConfig.getString("username")
      val password = subConfig.getString("password")
      PrometheusClientAuthBasicCredential(username, password)
    }
  }

  def decodeAuthBasicToken(config: Config): Option[PrometheusClientAuthBasicToken] = {
    getSubConfig(config, "auth-basic-token").map{ subConfig =>
      val token = subConfig.getString("token")
      PrometheusClientAuthBasicToken(token)
    }
  }

  def decodeAuthBearer(config: Config): Option[PrometheusClientAuthBearer] = {
    getSubConfig(config, "auth-bearer").map { subConfig =>
      val bearer = subConfig.getString("bearer")
      PrometheusClientAuthBearer(bearer)
    }
  }

  def decodeAuthConfig(config: Config):Option[PrometheusClientAuth] = {
    val foundAuthConfig =
      List.empty[PrometheusClientAuth]:++decodeAuthBearer(config):++decodeAuthBasicToken(config):++decodeAuthBasicCredential(config)
    if (foundAuthConfig.size>1) {
      logger.warn(
        "Ignoring authentication as you've provided %d authentication configurations"
          .formatted(foundAuthConfig.size)
      )
      None
    } else foundAuthConfig.headOption
  }

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
        config.getInt("parallelRequests"),
        decodeAuthConfig(config)
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
