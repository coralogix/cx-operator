package com.coralogix.operator

import com.coralogix.operator.config.K8sClientConfig
import sttp.client3.httpclient.zio.HttpClientZioBackend.usingClient
import sttp.client3.httpclient.zio.SttpClient
import sttp.client3.logging.slf4j.Slf4jLoggingBackend
import zio.config.ZConfig
import zio.nio.core.file.Path
import zio.{ Task, ZIO, ZLayer, ZManaged }

import java.io.{ FileInputStream, InputStream }
import java.net.http.HttpClient
import java.security.{ KeyStore, SecureRandom }
import java.security.cert.{ CertificateFactory, X509Certificate }
import javax.net.ssl.{ SSLContext, TrustManager, TrustManagerFactory, X509TrustManager }

package object k8s {

  private def insecureSSLContext(): Task[SSLContext] = {
    val trustAllCerts = Array[TrustManager](new X509TrustManager {
      override def checkClientTrusted(chain: Array[X509Certificate], authType: String): Unit = {}
      override def checkServerTrusted(chain: Array[X509Certificate], authType: String): Unit = {}
      override def getAcceptedIssuers: Array[X509Certificate] = null
    })
    Task.effect {
      val sslContext: SSLContext = SSLContext.getInstance("TLS")
      sslContext.init(null, trustAllCerts, new SecureRandom())
      sslContext
    }
  }

  private def secureSSLContext(certFile: Path): Task[SSLContext] =
    ZManaged.fromAutoCloseable(Task.effect(new FileInputStream(certFile.toFile))).use {
      certStream =>
        for {
          trustStore    <- createTrustStore(certStream)
          trustManagers <- createTrustManagers(trustStore)
          sslContext    <- createSslContext(trustManagers)
        } yield sslContext
    }

  private def createTrustStore(pemInputStream: InputStream): Task[KeyStore] =
    Task.effect {
      val certFactory = CertificateFactory.getInstance("X509")
      val cert = certFactory.generateCertificate(pemInputStream).asInstanceOf[X509Certificate]
      val trustStore = KeyStore.getInstance("JKS")
      trustStore.load(null)
      val alias = cert.getSubjectX500Principal.getName
      trustStore.setCertificateEntry(alias, cert)
      trustStore
    }

  private def createTrustManagers(trustStore: KeyStore): Task[Array[TrustManager]] =
    Task.effect {
      val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
      tmf.init(trustStore)
      tmf.getTrustManagers
    }

  private def createSslContext(trustManagers: Array[TrustManager]): Task[SSLContext] =
    Task.effect {
      val sslContext = SSLContext.getInstance("TLSv1.2")
      sslContext.init(null, trustManagers, new SecureRandom())
      sslContext
    }

  val sttpClient: ZLayer[ZConfig[K8sClientConfig], Throwable, SttpClient] =
    ZLayer.fromServiceManaged { config: K8sClientConfig =>
      for {
        sslContext <- (if (config.insecure)
                         insecureSSLContext()
                       else
                         secureSSLContext(config.cert)).toManaged_
        client <- ZManaged
                    .makeEffect(
                      usingClient(
                        HttpClient
                          .newBuilder()
                          .followRedirects(HttpClient.Redirect.NEVER)
                          .sslContext(sslContext)
                          .build()
                      )
                    )(_.close().ignore)
                    .map { backend =>
                      Slf4jLoggingBackend(
                        backend,
                        logRequestBody = config.debug,
                        logResponseBody = config.debug
                      )
                    }
      } yield client
    }
}
