package com.coralogix.operator

import zio.k8s.client.com.coralogix.definitions.rulegroupset.v1.Rulegroupset
import zio.k8s.client.io.k8s.apiextensions.customresourcedefinitions.{ v1 => crd }
import zio.k8s.client.com.coralogix.rulegroupset.{ v1 => rulegroupset }
import com.coralogix.operator.config.{ OperatorConfig, OperatorResources }
import com.coralogix.operator.logic.operators.rulegroupset.RulegroupsetOperator
import com.coralogix.operator.logic.Registration
import com.coralogix.operator.monitoring.{ clientMetrics, OperatorMetrics }
import com.coralogix.rules.grpc.external.v1.RuleGroupsService.ZioRuleGroupsService.RuleGroupsServiceClient
import zio.blocking.Blocking
import zio.clock.Clock
import zio.config._
import zio.config.syntax._
import zio.console.Console
import zio.k8s.client.config.{ k8sCluster, k8sSttpClient }
import zio.k8s.client.{ NamespacedResource, NamespacedResourceStatus }
import zio.logging.{ log, LogAnnotation, Logging }
import zio.system.System
import zio.{ console, App, ExitCode, Fiber, Has, URIO, ZIO }

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
          rulegroupset.live // TODO: add other custom resource client layers here
        ))

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
                 rulegroupset.metadata,
                 rulegroupset.customResourceDefinition
               )
          rulegroupFibers <- spawnRuleGroupOperators(metrics, config.resources)
          _               <- ZIO.never.raceAll(rulegroupFibers.map(_.await))
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

  private def spawnRuleGroupOperators(
    metrics: OperatorMetrics,
    resources: OperatorResources
  ): ZIO[
    Clock with Logging with rulegroupset.Rulegroupsets with RuleGroupsServiceClient,
    Nothing,
    List[Fiber.Runtime[Nothing, Unit]]
  ] =
    if (resources.rulegroups.isEmpty)
      for {
        _ <- log.info(s"Starting rule group operator for all namespaces")
        op <- RulegroupsetOperator.forAllNamespaces(
                resources.defaultBuffer,
                metrics
              )
        opFiber <- op.start()
      } yield List(opFiber)
    else
      ZIO.foreach(resources.rulegroups) { rulegroupConfig =>
        for {
          _ <- log.info(
                 s"Starting rule group operator for namespace ${rulegroupConfig.namespace.value}"
               )
          op <- RulegroupsetOperator.forNamespace(
                  rulegroupConfig.namespace,
                  rulegroupConfig.buffer.getOrElse(resources.defaultBuffer),
                  metrics
                )
          opFiber <- op.start()
        } yield opFiber
      }
}
