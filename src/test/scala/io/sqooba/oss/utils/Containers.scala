package io.sqooba.oss.utils

import zio._
import zio.blocking.{ effectBlocking, Blocking }
import com.dimafeng.testcontainers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait

object Containers {
  type VictoriaMetrics = Has[GenericContainer]

  def victoriaMetrics(
    imageName: String = "victoriametrics/victoria-metrics:v1.42.0"
  ): ZLayer[Blocking, Nothing, VictoriaMetrics] = {
    val port = 8428
    ZManaged.make {
      effectBlocking {
        val container = GenericContainer(
          imageName,
          exposedPorts = Seq(port),
          command = Seq("-retentionPeriod=1200"),
          waitStrategy = Wait.defaultWaitStrategy()
        )
        container.start()
        container
      }.orDie
    }(container => effectBlocking(container.stop()).orDie).toLayer
  }
}
