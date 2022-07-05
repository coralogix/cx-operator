package com.coralogix.operator

import com.coralogix.operator.config.PrometheusConfig
import com.coralogix.operator.logging.Log
import com.coralogix.operator.logging.LogSyntax.FieldBuilder
import io.prometheus.client.CollectorRegistry
import io.prometheus.client.exporter.HTTPServer
import io.prometheus.client.hotspot.{
  BufferPoolsExports,
  ClassLoadingExports,
  GarbageCollectorExports,
  MemoryAllocationExports,
  MemoryPoolsExports,
  StandardExports,
  ThreadExports,
  VersionInfoExports
}
import zio.config._
import zio.logging._
import zio.metrics.prometheus
import zio.metrics.prometheus.Registry
import zio.metrics.prometheus.exporters.Exporters
import zio.metrics.prometheus.helpers._
import zio.{ Has, Task, ULayer, ZIO, ZLayer, ZManaged }

package object monitoring {
  case class PrometheusExport(server: HTTPServer)

  val prometheusExport: ZLayer[Logging with Registry with Exporters with Has[
    PrometheusConfig
  ], Throwable, Has[PrometheusExport]] =
    ZLayer.fromManaged(
      ZManaged.make(
        log.locally(LogAnnotation.Name("com" :: "coralogix" :: "operator" :: "monitoring" :: Nil)) {
          for {
            config   <- getConfig[PrometheusConfig]
            registry <- getCurrentRegistry()
            _        <- initializeDefaultExportsExceptThread(registry)
            server   <- http(registry, config.port)
            _        <- Log.info("StartedMetricsServe", "port" := config.port)
          } yield PrometheusExport(server)
        }
      )(export => stopHttp(export.server).ignore)
    )

  val clientMetrics: ZLayer[Registry, Throwable, Has[ClientMetrics]] =
    ClientMetrics.make.toLayer

  val live: ULayer[Registry with Exporters] =
    prometheus.Registry.live ++ prometheus.exporters.Exporters.live

  private def initializeDefaultExportsExceptThread(registry: CollectorRegistry): Task[Unit] =
    ZIO.effect {
      new StandardExports().register(registry)
      new MemoryPoolsExports().register(registry)
      new MemoryAllocationExports().register(registry)
      new BufferPoolsExports().register(registry)
      new GarbageCollectorExports().register(registry)
      new ClassLoadingExports().register(registry)
      new VersionInfoExports().register(registry)
      ()
    }
}
