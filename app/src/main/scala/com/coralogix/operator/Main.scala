package com.coralogix.operator

import com.coralogix.alerts.v1.ZioAlertService.AlertServiceClient
import com.coralogix.operator.config.{ BaseOperatorConfig, OperatorConfig, OperatorResources }
import com.coralogix.operator.logic.CoralogixOperatorFailure
import com.coralogix.operator.logic.operators.alertset.ApiKeySetOperator
import com.coralogix.operator.logic.operators.coralogixlogger.CoralogixLoggerOperator
import com.coralogix.operator.logic.operators.rulegroupset.RuleGroupSetOperator
import com.coralogix.operator.monitoring.{ clientMetrics, OperatorMetrics }
import com.coralogix.rules.v1.ZioRuleGroupsService.RuleGroupsServiceClient
import com.coralogix.users.v2beta1.ZioApiKeysService.ApiKeysServiceClient
import com.coralogix.zio.k8s.client.apiextensions.v1.customresourcedefinitions.CustomResourceDefinitions
import com.coralogix.zio.k8s.client.apps.v1.daemonsets.DaemonSets
import com.coralogix.zio.k8s.client.authorization.rbac.v1.clusterrolebindings.ClusterRoleBindings
import com.coralogix.zio.k8s.client.authorization.rbac.v1.clusterroles.ClusterRoles
import com.coralogix.zio.k8s.client.com.coralogix.definitions.alertset.v1.AlertSet
import com.coralogix.zio.k8s.client.com.coralogix.definitions.apikeyset.v1.ApiKeySet
import com.coralogix.zio.k8s.client.com.coralogix.definitions.rulegroupset.v1.RuleGroupSet
import com.coralogix.zio.k8s.client.com.coralogix.loggers.definitions.coralogixlogger.v1.CoralogixLogger
import com.coralogix.zio.k8s.client.com.coralogix.loggers.v1.coralogixloggers
import com.coralogix.zio.k8s.client.com.coralogix.loggers.v1.coralogixloggers.CoralogixLoggers
import com.coralogix.zio.k8s.client.com.coralogix.v1.alertsets.AlertSets
import com.coralogix.zio.k8s.client.com.coralogix.v1.apikeysets.ApiKeySets
import com.coralogix.zio.k8s.client.com.coralogix.v1.{ alertsets, apikeysets, rulegroupsets }
import com.coralogix.zio.k8s.client.com.coralogix.v1.rulegroupsets.RuleGroupSets
import com.coralogix.zio.k8s.client.config.httpclient.k8sSttpClient
import com.coralogix.zio.k8s.client.config.k8sCluster
import com.coralogix.zio.k8s.client.model.K8sNamespace
import com.coralogix.zio.k8s.client.v1.configmaps.ConfigMaps
import com.coralogix.zio.k8s.client.v1.pods.Pods
import com.coralogix.zio.k8s.client.v1.serviceaccounts.ServiceAccounts
import com.coralogix.zio.k8s.operator.{ Leader, Operator, Registration }
import zio.blocking.Blocking
import zio.clock.Clock
import zio.config._
import zio.config.syntax._
import zio.console.Console
import zio.logging.{ log, LogAnnotation, Logging }
import zio.system.System
import zio.{ console, App, ExitCode, Fiber, URIO, ZIO }

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
        (CustomResourceDefinitions.live ++
          ServiceAccounts.live ++
          ClusterRoles.live ++
          ClusterRoleBindings.live ++
          DaemonSets.live ++
          ConfigMaps.live ++
          Pods.live ++

          RuleGroupSets.live ++
          CoralogixLoggers.live ++
          AlertSets.live))

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

            _ <- Registration.registerIfMissing[RuleGroupSet](
                   rulegroupsets.customResourceDefinition
                 ) <&>
                   Registration.registerIfMissing[CoralogixLogger](
                     coralogixloggers.customResourceDefinition
                   ) <&>
                   Registration.registerIfMissing[AlertSet](
                     alertsets.customResourceDefinition
                   ) <&>
                   Registration.registerIfMissing[AlertSet](
                     apikeysets.customResourceDefinition
                   )
            rulegroupFibers <- spawnRuleGroupOperators(metrics, config.resources)
            loggerFibers    <- spawnLoggerOperators(metrics, config.resources)
            alertFibers     <- spawnApiKeysOperators(metrics, config.resources)
//            userOperators     <- spawnUserOperators(metrics, config.resources) // TODO
            allFibers = rulegroupFibers ::: loggerFibers ::: alertFibers // :: userOperators
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
    for {
      serviceAccounts     <- ZIO.service[ServiceAccounts.Service]
      clusterRoles        <- ZIO.service[ClusterRoles.Service]
      clusterRoleBindings <- ZIO.service[ClusterRoleBindings.Service]
      daemonSets          <- ZIO.service[DaemonSets.Service]
      op <-
        SpawnOperators[CoralogixLogger](
          "coralogix logger operator",
          metrics,
          resources,
          _.coralogixLoggers,
          CoralogixLoggerOperator.forAllNamespaces,
          CoralogixLoggerOperator.forNamespace
        ).provideSome[Clock with Logging with CoralogixLoggers](
          _ ++ serviceAccounts.asGeneric ++ clusterRoles.asGeneric ++ clusterRoleBindings.asGeneric ++ daemonSets.asGeneric
        )
    } yield op

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
      ApiKeySetOperator.forAllNamespaces,
      ApiKeySetOperator.forNamespace
    )

  // TODO should be all ApiKeys
  private def spawnApiKeysOperators(
    metrics: OperatorMetrics,
    resources: OperatorResources
  ): ZIO[
    Clock with Logging with ApiKeySets with ApiKeysServiceClient,
    Nothing,
    List[Fiber.Runtime[Nothing, Unit]]
  ] =
    SpawnOperators[ApiKeySet](
      "api keys operator",
      metrics,
      resources,
      _.alerts,
      ApiKeySetOperator.forAllNamespaces,
      ApiKeySetOperator.forNamespace
    )

}
