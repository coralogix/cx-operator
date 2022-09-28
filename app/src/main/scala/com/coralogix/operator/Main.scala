package com.coralogix.operator

import com.coralogix.alerts.v1.ZioAlertService.AlertServiceClient
import com.coralogix.crdgen.core.operator.logic.CoralogixOperatorFailure
import com.coralogix.operator.config.{ BaseOperatorConfig, OperatorConfig, OperatorResources }
import com.coralogix.operator.logging.Log
import com.coralogix.operator.logging.LogSyntax._
import com.coralogix.operator.logic.operators.alertset.AlertSetOperator
import com.coralogix.operator.logic.operators.coralogixlogger.CoralogixLoggerOperator
import com.coralogix.operator.logic.operators.rulegroupset.RuleGroupSetOperator
import com.coralogix.operator.monitoring.clientMetrics
import com.coralogix.crdgen.core.operator.monitoring.OperatorMetrics
import com.coralogix.rules.v1.ZioRuleGroupsService.RuleGroupsServiceClient
import com.coralogix.tags.v2.tag.operator.TagsOperator
import com.coralogix.tags.v2.tags_service.ZioTagsService.TagsServiceClient
import com.coralogix.zio.k8s.client.K8sFailure
import com.coralogix.zio.k8s.client.apiextensions.v1.customresourcedefinitions.CustomResourceDefinitions
import com.coralogix.zio.k8s.client.apiextensions.v1.{ customresourcedefinitions => crd }
import com.coralogix.zio.k8s.client.apps.v1.daemonsets.DaemonSets
import com.coralogix.zio.k8s.client.authorization.rbac.v1.clusterrolebindings.ClusterRoleBindings
import com.coralogix.zio.k8s.client.authorization.rbac.v1.clusterroles.ClusterRoles
import com.coralogix.zio.k8s.client.com.coralogix.definitions.alertset.v1.AlertSet
import com.coralogix.zio.k8s.client.com.coralogix.definitions.rulegroupset.v1.RuleGroupSet
import com.coralogix.zio.k8s.client.com.coralogix.definitions.tag.v2.Tag
import com.coralogix.zio.k8s.client.com.coralogix.loggers.definitions.coralogixlogger.v1.CoralogixLogger
import com.coralogix.zio.k8s.client.com.coralogix.loggers.v1.coralogixloggers
import com.coralogix.zio.k8s.client.com.coralogix.loggers.v1.coralogixloggers.CoralogixLoggers
import com.coralogix.zio.k8s.client.com.coralogix.v1.alertsets.AlertSets
import com.coralogix.zio.k8s.client.com.coralogix.v1.rulegroupsets.RuleGroupSets
import com.coralogix.zio.k8s.client.com.coralogix.v1.{ alertsets, rulegroupsets }
import com.coralogix.zio.k8s.client.com.coralogix.v2.tags
import com.coralogix.zio.k8s.client.com.coralogix.v2.tags.Tags
import com.coralogix.zio.k8s.client.config.httpclient.k8sSttpClient
import com.coralogix.zio.k8s.client.config.{ defaultConfigChain, k8sCluster }
import com.coralogix.zio.k8s.client.model.K8sNamespace
import com.coralogix.zio.k8s.client.v1.configmaps.ConfigMaps
import com.coralogix.zio.k8s.client.v1.pods.Pods
import com.coralogix.zio.k8s.client.v1.serviceaccounts.ServiceAccounts
import com.coralogix.zio.k8s.model.pkg.apis.apiextensions.v1.CustomResourceDefinition
import com.coralogix.zio.k8s.operator.contextinfo.ContextInfoFailure._
import com.coralogix.zio.k8s.operator.leader.{ runAsLeader, LeaderElection }
import com.coralogix.zio.k8s.operator.{ contextinfo, KubernetesFailure, Operator, OperatorFailure }
import zio.blocking.Blocking
import zio.clock.Clock
import zio.config._
import zio.config.syntax._
import zio.console.Console
import zio.logging.{ log, LogAnnotation, Logging }
import zio.magic._
import zio.system.System
import zio.{ console, App, ExitCode, Fiber, URIO, ZIO, ZManaged }

object Main extends App {
  val logger = Log.logger("cx-operator")

  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] = {
    val config = (System.any ++ logger) >>> OperatorConfig.live
    val ruleGroupsClient =
      (config.narrow(
        _.grpc.clients.rulegroups
      ) ++ (monitoring.live >>> clientMetrics) ++ Clock.any) >>> grpc.clients.rulegroups.live

    val alertsClient =
      (config.narrow(
        _.grpc.clients.alerts
      ) ++ (monitoring.live >>> clientMetrics) ++ Clock.any) >>> grpc.clients.alerts.live

    val tagsClient =
      (config.narrow(
        _.grpc.clients.tags
      ) ++ (monitoring.live >>> clientMetrics) ++ Clock.any) >>> grpc.clients.tags.live

    val spawnOperators =
      log.locally(LogAnnotation.Name("com" :: "coralogix" :: "operator" :: Nil)) {
        for {
          _       <- Log.info(s"OperatorStarted")
          config  <- getConfig[OperatorConfig]
          metrics <- OperatorMetrics.make

          _ <- register(
                 rulegroupsets.customResourceDefinition
               ) <&>
                 register(
                   coralogixloggers.customResourceDefinition
                 ) <&>
                 register(
                   alertsets.customResourceDefinition
                 ) <&>
                 register(
                   tags.customResourceDefinition
                 )
          rulegroupFibers <- spawnRuleGroupOperators(metrics, config.resources)
          loggerFibers    <- spawnLoggerOperators(metrics, config.resources)
          alertFibers     <- spawnAlertOperators(metrics, config.resources, config.alertLabels)
          tagsFibers      <- spawnTagsOperator(metrics, config.resources)
        } yield rulegroupFibers ::: loggerFibers ::: alertFibers
      }

    val leader = runAsLeader {
      ZManaged
        .makeInterruptible(spawnOperators)(fibers =>
          ZIO.foreach(fibers)(
            _.interrupt.tapCause(cause =>
              console
                .putStrLnErr(s"Interrupt failure\n${cause.squash}")
                .ignore
                .provideLayer(console.Console.live)
            )
          )
        )
        .use(fibers => ZIO.never.raceAll(fibers.map(_.await)))
    }

    leader
      .injectSome[Blocking with System with Clock with Console](
        OperatorConfig.live,
        monitoring.live,
        defaultConfigChain.project(_.dropTrailingDot),
        k8sSttpClient,
        k8sCluster,
        logger,
        CustomResourceDefinitions.live,
        ServiceAccounts.live,
        ClusterRoles.live,
        ClusterRoleBindings.live,
        DaemonSets.live,
        ConfigMaps.live,
        Pods.live,
        RuleGroupSets.live,
        CoralogixLoggers.live,
        AlertSets.live,
        Tags.live,
        ruleGroupsClient,
        alertsClient,
        tagsClient,
        contextinfo.ContextInfo.live.mapError(error =>
          contextInfoFailureToThrowable.toThrowable(error)
        ),
        LeaderElection.configMapLock("cx-operator-lock")
      )
      .provideSomeLayer[Blocking with System with Clock with Console](
        (config.narrow(_.grpc) ++ logger) >>> grpc.server
      )
      .tapCause { cause =>
        console.putStrLnErr(s"Critical failure\n${cause.squash}")
      }
      .exitCode
      .untraced
  }

  object SpawnOperators {
    def apply[T] = new SpawnOperators[T]
  }
  class SpawnOperators[T] {
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
      Log.annotate("operator" := name) {
        if (resourceSelector(resources).isEmpty)
          for {
            _       <- Log.info(s"Starting", "namespace" := "all")
            op      <- constructAll(resources.defaultBuffer, metrics)
            opFiber <- op.start()
          } yield List(opFiber)
        else
          ZIO.foreach(resourceSelector(resources)) { config =>
            for {
              _ <- Log.info(s"Starting", "namespace" := config.namespace.value)
              op <- constructForNamespace(
                      config.namespace,
                      config.buffer.getOrElse(resources.defaultBuffer),
                      metrics
                    )
              opFiber <- op.start()
            } yield opFiber
          }
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
      "RuleGroupSet",
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
          "CoralogixLogger",
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
    resources: OperatorResources,
    alertLabels: List[String]
  ): ZIO[
    Clock with Logging with AlertSets with AlertServiceClient,
    Nothing,
    List[Fiber.Runtime[Nothing, Unit]]
  ] =
    SpawnOperators[AlertSet](
      "AlertSet",
      metrics,
      resources,
      _.alerts,
      AlertSetOperator.forAllNamespaces(alertLabels),
      AlertSetOperator.forNamespace(alertLabels)
    )

  private def spawnTagsOperator(
    metrics: OperatorMetrics,
    resources: OperatorResources
  ): ZIO[
    Clock with Logging with Tags with TagsServiceClient,
    Nothing,
    List[Fiber.Runtime[Nothing, Unit]]
  ] =
    SpawnOperators[Tag](
      "Tags",
      metrics,
      resources,
      _.tags,
      TagsOperator.forAllNamespaces,
      TagsOperator.forNamespace
    )

  private def register(
    customResourceDefinition: ZIO[Logging with Blocking, Throwable, CustomResourceDefinition]
  ): ZIO[CustomResourceDefinitions with Logging with Blocking, Throwable, Unit] =
    for {
      definition <- customResourceDefinition
      name       <- definition.getName.mapError(registrationFailure)
      _ <- crd
             .create(definition)
             .orElse(
               for {
                 current <- crd.get(name)
                 _ <- crd.replace(
                        name,
                        definition.mapMetadata(
                          _.copy(resourceVersion = current.metadata.flatMap(_.resourceVersion))
                        )
                      )
               } yield ()
             )
             .mapError(registrationFailure)
      name <- definition.getName.mapError(registrationFailure)
      _    <- Log.info(s"RegisteredCRD", "name" := name)
    } yield ()

  private def registrationFailure(failure: K8sFailure): Throwable =
    new RuntimeException(
      s"CRD registration failed",
      OperatorFailure.toThrowable[Nothing].toThrowable(KubernetesFailure(failure))
    )
}
