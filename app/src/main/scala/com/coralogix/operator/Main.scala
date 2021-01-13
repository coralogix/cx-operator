package com.coralogix.operator

import com.coralogix.zio.k8s.client.com.coralogix.definitions.rulegroupset.v1.Rulegroupset
import com.coralogix.zio.k8s.client.io.k8s.apiextensions.customresourcedefinitions.{v1 => crd}
import com.coralogix.zio.k8s.client.serviceaccounts.{v1 => serviceaccounts }
import com.coralogix.zio.k8s.client.com.coralogix.rulegroupsets.{v1 => rulegroupsets}
import com.coralogix.zio.k8s.client.com.coralogix.loggers.coralogixloggers.{v1 => coralogixloggers}
import com.coralogix.operator.config.{BaseOperatorConfig, OperatorConfig, OperatorResources}
import com.coralogix.operator.logic.CoralogixOperatorFailure
import com.coralogix.operator.logic.operators.rulegroupset.RulegroupsetOperator
import com.coralogix.zio.k8s.operator.{Operator, Registration}
import com.coralogix.operator.logic.operators.coralogixlogger.CoralogixloggerOperator
import com.coralogix.operator.monitoring.{OperatorMetrics, clientMetrics}
import com.coralogix.rules.grpc.external.v1.RuleGroupsService.ZioRuleGroupsService.RuleGroupsServiceClient
import zio.blocking.Blocking
import zio.clock.Clock
import zio.config._
import zio.config.syntax._
import zio.console.Console
import com.coralogix.zio.k8s.client.com.coralogix.loggers.coralogixloggers.v1.Coralogixloggers
import com.coralogix.zio.k8s.client.com.coralogix.loggers.definitions.coralogixlogger.v1.Coralogixlogger
import com.coralogix.zio.k8s.client.config.{k8sCluster, k8sSttpClient}
import com.coralogix.zio.k8s.client.model.{K8sNamespace, Object}
import com.coralogix.zio.k8s.client.serviceaccounts.v1.ServiceAccounts
import zio.logging.{LogAnnotation, Logging, log}
import zio.system.System
import zio.{App, ExitCode, Fiber, Has, URIO, ZIO, ZLayer, console}

object Main extends App {

  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] = {
    val config = (System.any ++ logging.live) >>> OperatorConfig.live
    val monitoringExport =
      (config.narrow(
        _.prometheus
      ) ++ monitoring.live ++ logging.live) >>> monitoring.prometheusExport
    val sttp = config.narrow(_.k8sClient) >>> k8sSttpClient
    val cluster = (Blocking.any ++ config.narrow(_.cluster)) >>> k8sCluster
    val operatorEnvironment =
      System.any ++ cluster ++ sttp ++ monitoring.live ++ monitoringExport

    val clients =
      logging.live ++ (operatorEnvironment >>>
        (crd.live ++
        serviceaccounts.live ++
          rulegroupsets.live ++
          coralogixloggers.live))

    val grpcServer = (logging.live ++ config.narrow(_.grpc)) >>> grpc.live

    val ruleGroupsClient =
      (config.narrow(
        _.grpc.clients.rulegroups
      ) ++ (monitoring.live >>> clientMetrics) ++ Clock.any) >>> grpc.clients.rulegroups.live

    val service =
      log.locally(LogAnnotation.Name("com" :: "coralogix" :: "operator" :: Nil)) {
        for {
          _       <- log.info(s"Operator started")
          config  <- getConfig[OperatorConfig]
          metrics <- OperatorMetrics.make

          _ <- Registration.registerIfMissing(
                 rulegroupsets.metadata,
                 rulegroupsets.customResourceDefinition
               ) <&>
                 Registration.registerIfMissing(
                   coralogixloggers.metadata,
                   coralogixloggers.customResourceDefinition
                 )
          rulegroupFibers <- spawnRuleGroupOperators(metrics, config.resources)
          loggerFibers    <- spawnLoggerOperators(metrics, config.resources)
          allFibers = rulegroupFibers ::: loggerFibers
          _ <- ZIO.never.raceAll(allFibers.map(_.await))
        } yield ()
      }

    service
      .provideSomeLayer[Blocking with System with Clock with Console](
        config ++ monitoring.live ++ clients ++ grpcServer ++ ruleGroupsClient
      )
      .tapCause { cause =>
        console.putStrLnErr(s"Critical failure\n${cause.squash}")
      }
      .exitCode
      .ensuring {
        console.putStrLnErr("Shutting down")
      }
      .untraced
  }

  object SpawnOperators {
    def apply[T <: Object] = new SpawnOperators[T]
  }
  class SpawnOperators[T <: Object] {
    def apply[R, E, ROp](
      name: String,
      metrics: OperatorMetrics,
      resources: OperatorResources,
      resourceSelector: OperatorResources => List[BaseOperatorConfig],
      constructAll: (Int, OperatorMetrics) => ZIO[R, E, Operator[ROp, CoralogixOperatorFailure, T]],
      constructForNamespace: (K8sNamespace, Int, OperatorMetrics) => ZIO[R, E, Operator[ROp, CoralogixOperatorFailure, T]]
    ): ZIO[ROp with Clock with Logging with R, E, List[Fiber.Runtime[Nothing, Unit]]] =
      if (resourceSelector(resources).isEmpty)
        for {
          _       <- log.info(s"Starting $name for all namespaces")
          op      <- constructAll(resources.defaultBuffer, metrics)
          opFiber <- op.start()
        } yield List(opFiber)
      else
        ZIO.foreach(resourceSelector(resources)) { config =>
          for {
            _ <- log.info(
                   s"Starting $name for namespace ${config.namespace.value}"
                 )
            op <- constructForNamespace(
                    config.namespace,
                    config.buffer.getOrElse(resources.defaultBuffer),
                    metrics
                  )
            opFiber <- op.start()
          } yield opFiber
        }
  }

  private def spawnRuleGroupOperators(
    metrics: OperatorMetrics,
    resources: OperatorResources
  ): ZIO[
    Clock with Logging with rulegroupsets.Rulegroupsets with RuleGroupsServiceClient,
    Nothing,
    List[Fiber.Runtime[Nothing, Unit]]
  ] =
    SpawnOperators[Rulegroupset](
      "rule group operator",
      metrics,
      resources,
      _.rulegroups,
      RulegroupsetOperator.forAllNamespaces,
      RulegroupsetOperator.forNamespace
    )

  private def spawnLoggerOperators(
    metrics: OperatorMetrics,
    resources: OperatorResources
  ): ZIO[Clock with Logging with Coralogixloggers with ServiceAccounts, Nothing, List[
    Fiber.Runtime[Nothing, Unit]
  ]] =
    SpawnOperators[Coralogixlogger](
      "coralogix logger operator",
      metrics,
      resources,
      _.coralogixLoggers,
      CoralogixloggerOperator.forAllNamespaces,
      CoralogixloggerOperator.forNamespace
    )
}
