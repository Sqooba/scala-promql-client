package io.sqooba.oss.utils

import io.sqooba.oss.promql.PrometheusClient
import io.sqooba.oss.utils.VictoriaClientRunnable.PromClientEnv
import io.sqooba.oss.utils.PromClientRunnableCompanion.promConfigFromContainer
import sttp.client.asynchttpclient.zio.AsyncHttpClientZioBackend
import zio.ULayer
import zio.blocking.Blocking
import zio.duration.durationInt
import zio.test._
import zio.test.environment.testEnvironment

/**
 * PromClientRunnable defines a PromClient-Layer tied to one testcontainer per test
 * This extension runs a test suite against multiple versions of VictoriaMetrics
 *
 * Update the List of Versions here below if you prepare server upgrade
 */
abstract trait MultiVersionPromClient[R <: PromClientEnv] extends RunnableSpec[R, Any] {

  val versions = Seq("latest")

  override def aspects: List[TestAspect[Nothing, Environment, Nothing, Any]] =
    List(TestAspect.timeout(60.seconds))

  def serverLayer(version: String): ULayer[PromClientEnv]

  def buildLayer(version: String): ULayer[R]

  override def runner: TestRunner[R, Any] =
    TestRunner(MultiTestExecutor.fromList(versions.map(v => (v, buildLayer(v)))))

}

abstract class MultiVersionVictoriaClientRunnable extends MultiVersionPromClient[PromClientEnv] {

  def serverLayer(version: String): ULayer[PromClientEnv] = {
    val victoriaMetrics = Blocking.live >>> Containers.victoriaMetrics(s"victoriametrics/victoria-metrics:$version")
    val promConfig      = victoriaMetrics >>> promConfigFromContainer
    val promClient      = (promConfig ++ AsyncHttpClientZioBackend.layer()) >>> PrometheusClient.live
    testEnvironment ++ promClient
  }.orDie

  override def buildLayer(version: String): ULayer[PromClientEnv] = serverLayer(version)
}

abstract class MultiVersionPromClientRunnable extends MultiVersionPromClient[PromClientEnv] {

  def serverLayer(version: String): ULayer[PromClientEnv] = {
    val victoriaMetrics = Blocking.live >>> Containers.customProm(version)
    val promConfig      = victoriaMetrics >>> promConfigFromContainer
    val promClient      = (promConfig ++ AsyncHttpClientZioBackend.layer()) >>> PrometheusClient.live
    testEnvironment ++ promClient
  }.orDie

  override def buildLayer(version: String): ULayer[PromClientEnv] = serverLayer(version)
}
