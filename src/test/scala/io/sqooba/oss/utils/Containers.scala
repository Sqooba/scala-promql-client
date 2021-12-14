package io.sqooba.oss.utils

import com.dimafeng.testcontainers.GenericContainer
import org.testcontainers.containers.BindMode
import org.testcontainers.containers.wait.strategy.Wait
import zio.blocking.{Blocking, effectBlocking}
import zio.{Has, ZLayer, ZManaged}

object Containers {
  type VictoriaMetrics = Has[GenericContainer]

  def victoriaMetrics(
    imageName: String = "victoriametrics/victoria-metrics:v1.53.1"
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

  def prometheus(
                  imageName: String = "prom/prometheus:latest"
                ): ZLayer[Blocking, Nothing, VictoriaMetrics] = {
    val port = 9090
    ZManaged.make {
      effectBlocking {
        val container = GenericContainer(
          imageName,
          exposedPorts = Seq(port),
          command = Seq("--storage.tsdb.retention.time=1200d"),
          waitStrategy = Wait.defaultWaitStrategy()
        )
        container.start()
        container
      }.orDie
    }(container => effectBlocking(container.stop()).orDie).toLayer
  }

  def customProm(version: String = "latest"): ZLayer[Blocking, Nothing, Has[GenericContainer]] = {
    val imageName: String = s"prom/prometheus:$version"
    val port = 9090
    ZManaged.make {
      effectBlocking {
        val container = GenericContainer(
          imageName,
          exposedPorts = Seq(port),
          command = Seq("--storage.tsdb.retention.time=1200d",
            "--config.file=/etc/prometheus/prometheus.yml",
            "--storage.tsdb.path=/prometheus",
            "--web.console.libraries=/usr/share/prometheus/console_libraries",
            "--web.console.templates=/usr/share/prometheus/consoles"),
          waitStrategy = Wait.defaultWaitStrategy(), classpathResourceMapping = Seq(("/metrics", "/home/metrics", BindMode.READ_ONLY))
        )

        container.start()
        container.execInContainer("/bin/promtool", "tsdb", "create-blocks-from","openmetrics","/home/metrics","/prometheus")
        // Warning: Prometheus takes up a minute before merging the newly written data.
        // only other solution is to restart the server.
        // as this is the entrypoint
        // https://hub.docker.com/layers/prom/prometheus/latest/images/sha256-d0abef1bc9a1c2dd92dec3ed7c3584c2ae5c4e45d0106a74da80a8406b30eb22?context=explore
        // I suspect it would kill the container.

        container.synchronized{
          container.wait(60 * 1000L)
        }
        container
      }.orDie
    }(container => effectBlocking(container.stop()).orDie).toLayer
  }
}
