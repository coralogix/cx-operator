package com.coralogix.operator.config

import com.typesafe.config.ConfigFactory
import sttp.model.Uri
import zio._
import zio.config._
import zio.config.derivation.name
import zio.config.magnolia.DeriveConfigDescriptor.{ descriptor, Descriptor }
import zio.config.typesafe.TypesafeConfigSource
import zio.duration.Duration
import com.coralogix.zio.k8s.client.config._
import com.coralogix.zio.k8s.client.model.K8sNamespace
import zio.logging.{ log, LogAnnotation, Logging }
import zio.nio.core.file.Path

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

trait BaseOperatorConfig {
  val namespace: K8sNamespace
  val buffer: Option[Int]
}

case class RulegroupConfig(namespace: K8sNamespace, buffer: Option[Int]) extends BaseOperatorConfig
case class CoralogixLoggerConfig(namespace: K8sNamespace, buffer: Option[Int])
    extends BaseOperatorConfig

case class OperatorResources(
  defaultBuffer: Int,
  rulegroups: List[RulegroupConfig],
  coralogixLoggers: List[CoralogixLoggerConfig]
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
}
