package com.coralogix.operator.config

import com.coralogix.operator.logging.Log
import com.coralogix.operator.logging.LogSyntax.FieldBuilder
import com.coralogix.zio.k8s.client.model.K8sNamespace
import com.typesafe.config.ConfigFactory
import zio._
import zio.config._
import zio.config.magnolia.DeriveConfigDescriptor.{ descriptor, Descriptor }
import zio.config.typesafe.TypesafeConfigSource
import zio.duration.Duration
import zio.logging.{ log, LogAnnotation, Logging }
import zio.nio.file.Path

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
case class AlertConfig(namespace: K8sNamespace, buffer: Option[Int]) extends BaseOperatorConfig

case class OperatorResources(
  defaultBuffer: Int,
  rulegroups: List[RulegroupConfig],
  coralogixLoggers: List[CoralogixLoggerConfig],
  alerts: List[AlertConfig]
)

case class GrpcClientsConfig(rulegroups: GrpcClientConfig, alerts: GrpcClientConfig)

case class GrpcConfig(port: Int, clients: GrpcClientsConfig)

case class OperatorConfig(
  prometheus: PrometheusConfig,
  grpc: GrpcConfig,
  resources: OperatorResources,
  alertLabels: List[String]
)

object OperatorConfig {
  private implicit val k8sNamespaceDescriptor: Descriptor[K8sNamespace] =
    Descriptor[String].transform(
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
  ): Layer[ReadError[String], Has[OperatorConfig]] =
    fromLocationM(ZIO.succeed(configLocation))

  def fromLocationM[R, E >: ReadError[String]](
    configLocation: ZIO[R, E, Path]
  ): ZLayer[R, E, Has[OperatorConfig]] =
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

  val live: ZLayer[system.System with Logging, Exception, Has[OperatorConfig]] = fromLocationM(
    configLocation.tap(path =>
      log.locally(LogAnnotation.Name("com" :: "coralogix" :: "operator" :: "config" :: Nil)) {
        Log.info("LoadingConfiguration", "path" := path.toString())
      }
    )
  )
}
