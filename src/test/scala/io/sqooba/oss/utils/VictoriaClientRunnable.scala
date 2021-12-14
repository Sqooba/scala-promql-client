package io.sqooba.oss.utils

import zio._
import zio.test._
import zio.duration._

import zio.blocking.Blocking
import zio.test.environment._
import VictoriaClientRunnable._
import io.sqooba.oss.promql.PrometheusClient
import io.sqooba.oss.promql.PrometheusService._
import io.sqooba.oss.promql.PrometheusClientConfig
import com.dimafeng.testcontainers.GenericContainer
import sttp.client.asynchttpclient.zio.AsyncHttpClientZioBackend

object VictoriaClientRunnable {
  type PromClientEnv = TestEnvironment with PrometheusService
}

object PromClientRunnableCompanion {
  def promConfigFromContainer: ZLayer[Has[GenericContainer], Nothing, Has[PrometheusClientConfig]] =
    ZLayer.fromService[GenericContainer, PrometheusClientConfig] { container =>
    // scalastyle:off magic.number
    PrometheusClientConfig(
      container.container.getContainerIpAddress(),
      container.container.getFirstMappedPort(),
      ssl = false,
      maxPointsPerTimeseries = 30000,
      retryNumber = 3,
      parallelRequests = 3
    )
    // scalastyle:on magic.number
  }
}

/**
 *  Extends ZIO-test default runner to automatically provide a VictoriaMetric instance to tests
 *  The components of the layer provided by this class can be used directly in the tests extending ChronosRunnable.
 */
abstract class VictoriaClientRunnable extends RunnableSpec[PromClientEnv, Any] {

  type PromClientRunnable = ZSpec[PromClientEnv, Any]

  override def aspects: List[TestAspect[Nothing, PromClientEnv, Nothing, Any]] =
    List(TestAspect.timeout(60.seconds))

  override def runner: TestRunner[PromClientEnv, Any] =
    TestRunner(TestExecutor.default(victoriaLayer))

  /**
   * Create a test environment by spawning a VictoriaMetrics container, building a client configuration
   * as well as a ChronosClient to be used by the tests
   */
  val victoriaLayer: ULayer[PromClientEnv] = {
    val victoriaMetrics = Blocking.live >>> Containers.victoriaMetrics()
    val promConfig      = victoriaMetrics >>> PromClientRunnableCompanion.promConfigFromContainer
    val promClient      = (promConfig ++ AsyncHttpClientZioBackend.layer()) >>> PrometheusClient.live
    testEnvironment ++ promClient
  }.orDie

}
