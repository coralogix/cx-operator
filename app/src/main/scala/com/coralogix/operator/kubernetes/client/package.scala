package com.coralogix.operator.kubernetes

import com.coralogix.operator.opentelemetry.OpenTelemetryTracingZioBackend
import com.coralogix.zio.k8s.client.config.{ K8sClusterConfig, K8sServerCertificate, SSL }
import sttp.client3.httpclient.zio._
import sttp.client3.logging.slf4j.Slf4jLoggingBackend
import zio.blocking.Blocking
import zio.system.System
import zio.telemetry.opentelemetry.Tracing
import zio.{ Has, ZIO, ZLayer, ZManaged }

import java.net.http.HttpClient

package object client {
  val k8sClient
    : ZLayer[Has[K8sClusterConfig] with System with Blocking with Tracing, Throwable, SttpClient] =
    ZLayer.fromServiceManaged { (config: K8sClusterConfig) =>
      val disableHostnameVerification = config.client.serverCertificate match {
        case K8sServerCertificate.Insecure => true
        case K8sServerCertificate.Secure(_, disableHostnameVerification) =>
          disableHostnameVerification
      }
      for {
        _ <- ZIO
               .effect {
                 java.lang.System.setProperty(
                   "jdk.internal.httpclient.disableHostnameVerification",
                   "true"
                 )
               }
               .when(disableHostnameVerification)
               .toManaged_
        sslContext <- SSL(config.client.serverCertificate, config.authentication).toManaged_
        tracing    <- ZIO.service[Tracing.Service].toManaged_
        client <- ZManaged
                    .makeEffect(
                      HttpClientZioBackend.usingClient(
                        HttpClient
                          .newBuilder()
                          .followRedirects(HttpClient.Redirect.NORMAL)
                          .sslContext(sslContext)
                          .build()
                      )
                    )(_.close().ignore)
                    .map { backend =>
                      OpenTelemetryTracingZioBackend(
                        Slf4jLoggingBackend(
                          backend,
                          logRequestBody = config.client.debug,
                          logResponseBody = config.client.debug
                        ),
                        tracing
                      )
                    }
      } yield client
    }
}
