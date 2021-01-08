package com.coralogix.operator.config

import zio.k8s.client.model.{ K8sCluster, K8sNamespace }
import com.typesafe.config.ConfigFactory
import sttp.model.Uri
import zio._
import zio.blocking.Blocking
import zio.config._
import zio.config.derivation.name
import zio.config.magnolia.DeriveConfigDescriptor.{ descriptor, Descriptor }
import zio.config.typesafe.TypesafeConfigSource
import zio.duration.Duration
import zio.logging.{ log, LogAnnotation, Logging }
import zio.nio.core.file.Path
import zio.nio.file.Files

import java.io.IOException
import java.nio.charset.StandardCharsets

case class K8sClusterConfig(
  host: Uri,
  token: Option[String],
  @name("token-file") tokenFile: Path
)

case class K8sClientConfig(
  insecure: Boolean, // for testing with minikube
  debug: Boolean,
  cert: Path
)

case class PrometheusConfig(
  port: Int
)

case class GrpcClientConfig(
  name: String,
  port: Int,
  tls: Boolean,
  token: String,
  headers: Map[String, String],
  deadline: Duration,
  parallelism: Int,
  queueSize: Int
)

case class RulegroupConfig(namespace: K8sNamespace, buffer: Option[Int])

case class OperatorResources(
  defaultBuffer: Int,
  rulegroups: List[RulegroupConfig]
)

case class GrpcClientsConfig(rulegroups: GrpcClientConfig)

case class GrpcConfig(port: Int, clients: GrpcClientsConfig)

case class OperatorConfig(
  cluster: K8sClusterConfig,
  @name("k8s-client") k8sClient: K8sClientConfig,
  prometheus: PrometheusConfig,
  grpc: GrpcConfig,
  resources: OperatorResources
)

object OperatorConfig {
  private implicit val k8sNamespaceDescriptor: Descriptor[K8sNamespace] =
    Descriptor[String].xmap(
      (s: String) => K8sNamespace(s),
      (ns: K8sNamespace) => ns.value
    )

  private implicit val uriDescriptor: Descriptor[Uri] =
    Descriptor[String].xmapEither(
      s => Uri.parse(s),
      (uri: Uri) => Right(uri.toString)
    )

  private implicit val pathDescriptor: Descriptor[Path] =
    Descriptor[String].xmap(
      s => Path(s),
      (path: Path) => path.toString()
    )

  private val configDescriptor: ConfigDescriptor[OperatorConfig] = descriptor[OperatorConfig]

  def configLocation: ZIO[system.System, RuntimeException, Path] =
    system.env("CORALOGIX_CONFIG").flatMap {
      case Some(env) =>
        ZIO.succeed(Path(env))
      case None =>
        ZIO.fail(
          new RuntimeException(
            "Unspecified config location, set the CORALOGIX_CONFIG environment variable"
          )
        )
    }

  def fromLocation(
    configLocation: Path
  ): Layer[ReadError[String], _root_.zio.config.ZConfig[OperatorConfig]] =
    fromLocationM(ZIO.succeed(configLocation))

  def fromLocationM[R, E >: ReadError[String]](
    configLocation: ZIO[R, E, Path]
  ): ZLayer[R, E, ZConfig[OperatorConfig]] =
    // TODO: this should not be this complicated, improve it in zio-config
    (for {
      path <- configLocation
      rawConfig <- ZIO.fromEither(
                     TypesafeConfigSource.fromTypesafeConfig(
                       ConfigFactory.parseFile(path.toFile).resolve
                     )
                   )
      descriptor = configDescriptor from rawConfig
      result <- ZIO.fromEither(read(descriptor))
    } yield result).toLayer

  val live: ZLayer[system.System with Logging, Exception, ZConfig[OperatorConfig]] = fromLocationM(
    configLocation.tap(path =>
      log.locally(LogAnnotation.Name("com" :: "coralogix" :: "operator" :: "config" :: Nil)) {
        log.info(s"Loading configuration $path")
      }
    )
  )

  def k8sCluster: ZLayer[Blocking with ZConfig[K8sClusterConfig], IOException, Has[K8sCluster]] =
    ZLayer.fromEffect {
      for {
        config <- getConfig[K8sClusterConfig]
        result <- config.token match {
                    case Some(token) =>
                      // Explicit API token
                      ZIO.succeed(
                        K8sCluster(
                          host = config.host,
                          token = token
                        )
                      )
                    case None =>
                      // No explicit token, loading from file
                      Files
                        .readAllBytes(config.tokenFile)
                        .map(bytes => new String(bytes.toArray, StandardCharsets.UTF_8))
                        .map { token =>
                          K8sCluster(
                            host = config.host,
                            token = token
                          )
                        }
                  }
      } yield result
    }

}
