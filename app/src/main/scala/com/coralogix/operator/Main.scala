package com.coralogix.operator

import com.coralogix.alerts.grpc.external.v1.ZioAlertService.AlertServiceClient
import com.coralogix.zio.k8s.client.com.coralogix.definitions.rulegroupset.v1.RuleGroupSet
import com.coralogix.zio.k8s.client.io.k8s.apiextensions.customresourcedefinitions.{ v1 => crd }
import com.coralogix.zio.k8s.client.serviceaccounts.{ v1 => serviceaccounts }
import com.coralogix.zio.k8s.client.com.coralogix.alertsets.{ v1 => alertsets }
import com.coralogix.zio.k8s.client.com.coralogix.rulegroupsets.{ v1 => rulegroupsets }
import com.coralogix.zio.k8s.client.com.coralogix.loggers.coralogixloggers.{
  v1 => coralogixloggers
}
import com.coralogix.operator.config.{ BaseOperatorConfig, OperatorConfig, OperatorResources }
import com.coralogix.operator.logic.CoralogixOperatorFailure
import com.coralogix.operator.logic.operators.alertset.AlertSetOperator
import com.coralogix.operator.logic.operators.rulegroupset.RuleGroupSetOperator
import com.coralogix.zio.k8s.operator.{ Leader, Operator, Registration }
import com.coralogix.operator.logic.operators.coralogixlogger.CoralogixLoggerOperator
import com.coralogix.operator.monitoring.{ clientMetrics, OperatorMetrics }
import com.coralogix.rules.grpc.external.v1.RuleGroupsService.ZioRuleGroupsService.RuleGroupsServiceClient
import com.coralogix.zio.k8s.client.configmaps.{ v1 => configmaps }
import com.coralogix.zio.k8s.client.pods.{ v1 => pods }
import com.coralogix.zio.k8s.client.apps.daemonsets.{ v1 => daemonsets }
import com.coralogix.zio.k8s.client.apps.daemonsets.v1.DaemonSets
import com.coralogix.zio.k8s.client.com.coralogix.alertsets.v1.AlertSets
import com.coralogix.zio.k8s.client.com.coralogix.definitions.alertset.v1.AlertSet
import zio.blocking.Blocking
import zio.clock.Clock
import zio.config._
import zio.config.syntax._
import zio.console.Console
import com.coralogix.zio.k8s.client.com.coralogix.loggers.coralogixloggers.v1.CoralogixLoggers
import com.coralogix.zio.k8s.client.com.coralogix.loggers.definitions.coralogixlogger.v1.CoralogixLogger
import com.coralogix.zio.k8s.client.com.coralogix.rulegroupsets.v1.RuleGroupSets
import com.coralogix.zio.k8s.client.config.{ k8sCluster, k8sSttpClient }
import com.coralogix.zio.k8s.client.io.k8s.authorization.rbac.clusterrolebindings.{
  v1 => clusterrolebindings
}
import com.coralogix.zio.k8s.client.io.k8s.authorization.rbac.clusterrolebindings.v1.ClusterRoleBindings
import com.coralogix.zio.k8s.client.io.k8s.authorization.rbac.clusterroles.{ v1 => clusterroles }
import com.coralogix.zio.k8s.client.io.k8s.authorization.rbac.clusterroles.v1.ClusterRoles
import com.coralogix.zio.k8s.client.model.K8sNamespace
import com.coralogix.zio.k8s.client.serviceaccounts.v1.ServiceAccounts
import zio.logging.{ log, LogAnnotation, Logging }
import zio.system.System
import zio.{ console, App, ExitCode, Fiber, Has, URIO, ZIO, ZLayer }

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
          clusterroles.live ++
          clusterrolebindings.live ++
          daemonsets.live ++
          configmaps.live ++
          pods.live ++

          rulegroupsets.live ++
          coralogixloggers.live ++
          alertsets.live))

    val grpcServer = (logging.live ++ config.narrow(_.grpc)) >>> grpc.live

    val ruleGroupsClient =
      (config.narrow(
        _.grpc.clients.rulegroups
      ) ++ (monitoring.live >>> clientMetrics) ++ Clock.any) >>> grpc.clients.rulegroups.live

    val alertsClient =
      (config.narrow(
        _.grpc.clients.alerts
      ) ++ (monitoring.live >>> clientMetrics) ++ Clock.any) >>> grpc.clients.alerts.live

    val service =
      log.locally(LogAnnotation.Name("com" :: "coralogix" :: "operator" :: Nil)) {
        Leader.leaderForLife("cx-operator-lock", None) {
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
                   ) <&>
                   Registration.registerIfMissing(
                     alertsets.metadata,
                     alertsets.customResourceDefinition
                   )
            rulegroupFibers <- spawnRuleGroupOperators(metrics, config.resources)
            loggerFibers    <- spawnLoggerOperators(metrics, config.resources)
            alertFibers     <- spawnAlertOperators(metrics, config.resources)
            allFibers = rulegroupFibers ::: loggerFibers ::: alertFibers
            _ <- ZIO.never.raceAll(allFibers.map(_.await))
          } yield ()
        }
      }

    service
      .provideSomeLayer[Blocking with System with Clock with Console](
        config ++ monitoring.live ++ clients ++ grpcServer ++ ruleGroupsClient ++ alertsClient
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
      constructForNamespace: (
        K8sNamespace,
        Int,
        OperatorMetrics
      ) => ZIO[R, E, Operator[ROp, CoralogixOperatorFailure, T]]
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
    Clock with Logging with RuleGroupSets with RuleGroupsServiceClient,
    Nothing,
    List[Fiber.Runtime[Nothing, Unit]]
  ] =
    SpawnOperators[RuleGroupSet](
      "rule group operator",
      metrics,
      resources,
      _.rulegroups,
      RuleGroupSetOperator.forAllNamespaces,
      RuleGroupSetOperator.forNamespace
    )

  private def spawnLoggerOperators(
    metrics: OperatorMetrics,
    resources: OperatorResources
  ): ZIO[
    Clock with Logging with CoralogixLoggers with ServiceAccounts with ClusterRoles with ClusterRoleBindings with DaemonSets,
    Nothing,
    List[
      Fiber.Runtime[Nothing, Unit]
    ]
  ] =
    SpawnOperators[CoralogixLogger](
      "coralogix logger operator",
      metrics,
      resources,
      _.coralogixLoggers,
      CoralogixLoggerOperator.forAllNamespaces,
      CoralogixLoggerOperator.forNamespace
    )

  private def spawnAlertOperators(
    metrics: OperatorMetrics,
    resources: OperatorResources
  ): ZIO[
    Clock with Logging with AlertSets with AlertServiceClient,
    Nothing,
    List[Fiber.Runtime[Nothing, Unit]]
  ] =
    SpawnOperators[AlertSet](
      "alert operator",
      metrics,
      resources,
      _.alerts,
      AlertSetOperator.forAllNamespaces,
      AlertSetOperator.forNamespace
    )

}
