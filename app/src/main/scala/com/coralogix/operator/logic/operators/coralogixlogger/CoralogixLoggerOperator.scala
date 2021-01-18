package com.coralogix.operator.logic.operators.coralogixlogger

import com.coralogix.operator.logic.aspects._
import com.coralogix.operator.logic.{ CoralogixOperatorFailure, ProvisioningFailed }
import com.coralogix.operator.monitoring.OperatorMetrics
import com.coralogix.zio.k8s.client.K8sFailure.syntax._
import com.coralogix.zio.k8s.client.com.coralogix.loggers.coralogixloggers.v1.{
  metadata,
  CoralogixLoggers
}
import com.coralogix.zio.k8s.client.com.coralogix.loggers.coralogixloggers.{
  v1 => coralogixloggers
}
import com.coralogix.zio.k8s.client.com.coralogix.loggers.definitions.coralogixlogger.v1.CoralogixLogger
import com.coralogix.zio.k8s.client.io.k8s.authorization.rbac.clusterrolebindings.v1.ClusterRoleBindings
import com.coralogix.zio.k8s.client.model._
import com.coralogix.zio.k8s.client.model.K8sObject._
import com.coralogix.zio.k8s.client.io.k8s.authorization.rbac.clusterroles.{ v1 => clusterroles }
import com.coralogix.zio.k8s.client.io.k8s.authorization.rbac.clusterrolebindings.{
  v1 => clusterrolebindings
}
import com.coralogix.zio.k8s.client.io.k8s.authorization.rbac.clusterroles.v1.ClusterRoles
import com.coralogix.zio.k8s.client.serviceaccounts.v1.ServiceAccounts
import com.coralogix.zio.k8s.client.serviceaccounts.{ v1 => serviceaccounts }
import com.coralogix.zio.k8s.client.apps.daemonsets.{ v1 => daemonsets }
import com.coralogix.zio.k8s.client.apps.daemonsets.v1.DaemonSets
import com.coralogix.zio.k8s.client.{
  ClusterResource,
  K8sFailure,
  NamespacedResource,
  NamespacedResourceStatus,
  ResourceClient
}
import com.coralogix.zio.k8s.model.rbac.v1.ClusterRole
import com.coralogix.zio.k8s.operator.Operator.{ EventProcessor, OperatorContext }
import com.coralogix.zio.k8s.operator.OperatorLogging._
import com.coralogix.zio.k8s.operator.aspects.logEvents
import com.coralogix.zio.k8s.operator.{
  KubernetesFailure,
  Operator,
  OperatorError,
  OperatorFailure
}
import izumi.reflect.Tag
import zio.clock.Clock
import zio.logging.{ log, Logging }
import zio.stm.{ TMap, TSet }
import zio.{ Cause, Has, Ref, ZIO }

object CoralogixLoggerOperator {
  private def eventProcessor(failedProvisions: FailedProvisions): EventProcessor[
    Logging with CoralogixLoggers with ServiceAccounts with ClusterRoles with ClusterRoleBindings with DaemonSets,
    CoralogixOperatorFailure,
    CoralogixLogger
  ] =
    (ctx, event) =>
      event match {
        case Reseted =>
          ZIO.unit
        case Added(item) =>
          setupLogger(ctx, failedProvisions, item)
        case Modified(item) =>
          setupLogger(ctx, failedProvisions, item)
        case Deleted(item) =>
          // The generated items are owned by the logger and get automatically garbage collected
          ZIO.unit
      }

  private def setupLogger(
    ctx: OperatorContext,
    failedProvisions: FailedProvisions,
    resource: CoralogixLogger
  ): ZIO[
    Logging with CoralogixLoggers with ServiceAccounts with ClusterRoles with ClusterRoleBindings with DaemonSets,
    OperatorFailure[
      CoralogixOperatorFailure
    ],
    Unit
  ] =
    skipIfAlreadyOutdated(resource) {
      skipIfAlredyRunning(resource) {
        skipIfFailedPreviously(failedProvisions, resource) {
          withCurrentResource(resource) { currentResource =>
            for {
              name <- resource.getName.mapError(KubernetesFailure.apply)
              uid  <- resource.getUid.mapError(KubernetesFailure.apply)
              _ <-
                updateState(
                  currentResource,
                  "PENDING",
                  "Initializing Provision",
                  s"Provisioning of '$name' in namespace '${resource.metadata.flatMap(_.namespace).getOrElse("-")}'"
                )
              _ <- setupServiceAccount(ctx, failedProvisions, name, uid, currentResource)
              _ <- setupClusterRole(ctx, failedProvisions, name, uid, currentResource)
              _ <- setupClusterRoleBinding(ctx, failedProvisions, name, uid, currentResource)
              _ <- setupDaemonSet(ctx, failedProvisions, name, uid, currentResource)
              _ <- updateState(
                     currentResource,
                     "RUNNING",
                     "Provisioning Succeeded",
                     s"CoralogixLogger '$name' successfully provisioned in namespace '${resource.metadata.flatMap(_.namespace).getOrElse("-")}'"
                   )
              _ <- log.info("Provision succeeded")
            } yield ()
          }
        }
      }
    }.catchSome {
      case OperatorError(ProvisioningFailed) =>
        log.info(s"Provision failed")
    }

  private def skipIfAlredyRunning[R <: Logging](resource: CoralogixLogger)(
    f: ZIO[R, OperatorFailure[CoralogixOperatorFailure], Unit]
  ): ZIO[R, OperatorFailure[CoralogixOperatorFailure], Unit] =
    if (resource.status.flatMap(_.state).contains("RUNNING"))
      log.info(s"CoralogixLogger is already running, skipping")
    else
      f

  private def skipIfFailedPreviously[R <: Logging](
    failedProvisions: FailedProvisions,
    resource: CoralogixLogger
  )(
    f: ZIO[R, OperatorFailure[CoralogixOperatorFailure], Unit]
  ): ZIO[R, OperatorFailure[CoralogixOperatorFailure], Unit] =
    ZIO.ifM(failedProvisions.isRecordedFailure(resource))(
      onTrue = log.info(s"CoralogixLogger failed before, skipping"),
      onFalse = f
    )

  private def skipIfAlreadyOutdated[R <: Logging with CoralogixLoggers](resource: CoralogixLogger)(
    f: ZIO[R, OperatorFailure[CoralogixOperatorFailure], Unit]
  ): ZIO[R, OperatorFailure[CoralogixOperatorFailure], Unit] =
    for {
      name <- resource.getName.mapError(KubernetesFailure.apply)
      namespace = resource.metadata
                    .flatMap(_.namespace)
                    .map(K8sNamespace.apply)
                    .getOrElse(K8sNamespace.default)
      latest <- coralogixloggers.get(name, namespace).mapError(KubernetesFailure.apply)
      _ <-
        if (
          latest.metadata.flatMap(_.resourceVersion) == resource.metadata.flatMap(_.resourceVersion)
        )
          f
        else
          log.info(s"Event refers to an outdated resource, skipping")
    } yield ()

  private def withCurrentResource[R, E, A](
    resource: CoralogixLogger
  )(f: Ref[CoralogixLogger] => ZIO[R, E, A]): ZIO[R, E, A] =
    Ref.make(resource).flatMap(f)

  private def updateState(
    currentResource: Ref[CoralogixLogger],
    newState: String,
    newPhase: String,
    newReason: String
  ): ZIO[CoralogixLoggers, OperatorFailure[CoralogixOperatorFailure], Unit] =
    for {
      resource <- currentResource.get
      oldStatus = resource.status.getOrElse(CoralogixLogger.Status())
      skip = oldStatus.state.contains(newState) &&
               oldStatus.phase.contains(newPhase) &&
               oldStatus.reason.contains(newReason)
      _ <- ZIO.unless(skip) {
             val replacedStatus = oldStatus.copy(
               state = Some(newState),
               phase = Some(newPhase),
               reason = Some(newReason)
             )
             for {
               updatedResource <- coralogixloggers
                                    .replaceStatus(
                                      resource,
                                      replacedStatus,
                                      resource.metadata
                                        .flatMap(_.namespace)
                                        .map(K8sNamespace.apply)
                                        .getOrElse(K8sNamespace.default)
                                    )
                                    .mapError(KubernetesFailure.apply)
               _ <- currentResource.set(updatedResource)
             } yield ()
           }
    } yield ()

  private def replaceStatus(
    of: Ref[CoralogixLogger],
    updateStatus: CoralogixLogger.Status => CoralogixLogger.Status,
    namespace: K8sNamespace,
    dryRun: Boolean = false
  ): ZIO[CoralogixLoggers, KubernetesFailure, Unit] =
    for {
      resource <- of.get
      updatedStatus = updateStatus(resource.status.getOrElse(CoralogixLogger.Status()))
      updatedResource <- coralogixloggers
                           .replaceStatus(resource, updatedStatus, namespace, dryRun)
                           .mapError(KubernetesFailure.apply)
      _ <- of.set(updatedResource)
    } yield ()

  private def provisioningFailed(
    failedProvisions: FailedProvisions,
    currentResource: Ref[CoralogixLogger],
    phase: String,
    reason: String,
    k8sReason: K8sFailure
  ): ZIO[CoralogixLoggers with Logging, OperatorFailure[CoralogixOperatorFailure], Nothing] =
    (for {
      _ <- logFailure(
             reason,
             Cause.fail[OperatorFailure[CoralogixOperatorFailure]](KubernetesFailure(k8sReason))
           )
      _              <- updateState(currentResource, "FAILED", phase, reason)
      failedResource <- currentResource.get
      _              <- failedProvisions.recordFailure(failedResource)
    } yield ()) *> ZIO.fail(OperatorError(ProvisioningFailed))

  private def setupComponent[T: K8sObject: ResourceMetadata: Tag](
    createComponent: (String, CoralogixLogger) => T,
    store: (CoralogixLogger.Status, String) => CoralogixLogger.Status
  )(
    ctx: OperatorContext,
    failedProvisions: FailedProvisions,
    name: String,
    uid: String,
    currentResource: Ref[CoralogixLogger]
  ): ZIO[CoralogixLoggers with Logging with Has[NamespacedResource[T]], OperatorFailure[
    CoralogixOperatorFailure
  ], Unit] =
    for {
      resource <- currentResource.get
      component     = Model.attachOwner(name, uid, ctx.resourceType, createComponent(name, resource))
      componentKind = implicitly[ResourceMetadata[T]].kind
      componentName <- component.getName.mapError(KubernetesFailure.apply)
      namespace = resource.metadata
                    .flatMap(_.namespace)
                    .map(K8sNamespace.apply)
                    .getOrElse(K8sNamespace.default)
      queryResult <- ResourceClient.namespaced
                       .get[T](componentName, namespace)
                       .ifFound
                       .either
      _ <- queryResult match {
             case Left(failure) =>
               provisioningFailed(
                 failedProvisions,
                 currentResource,
                 componentKind,
                 reason = s"Provisioning of $componentKind failed.",
                 failure
               )
             case Right(None) =>
               for {
                 _ <- updateState(
                        currentResource,
                        "PROVISIONING",
                        componentKind,
                        s"Provisioning of $componentKind..."
                      )
                 _ <- log.info(s"Creating a new $componentKind with name $componentName")
                 _ <- ResourceClient.namespaced
                        .create(component, namespace)
                        .catchAll { failure =>
                          provisioningFailed(
                            failedProvisions,
                            currentResource,
                            phase = componentKind,
                            reason = s"Provisioning of $componentKind failed.",
                            failure
                          )
                        }
                 _ <- replaceStatus(
                        currentResource,
                        status =>
                          store(
                            status
                              .copy(
                                reason = Some(s"Provisioning of $componentKind successful.")
                              ),
                            componentName
                          ),
                        namespace
                      )
               } yield ()
             case Right(_) =>
               log.info(s"Skip: $componentKind already exists") *>
                 replaceStatus(
                   currentResource,
                   status =>
                     store(
                       status,
                       componentName
                     ),
                   namespace
                 )
           }
    } yield ()

  // TODO: See if a common interface can be used for NS and Cluster resources in such generic use cases
  private def setupClusterComponent[T: K8sObject: ResourceMetadata: Tag](
    createComponent: (String, CoralogixLogger) => T,
    store: (CoralogixLogger.Status, String) => CoralogixLogger.Status
  )(
    ctx: OperatorContext,
    failedProvisions: FailedProvisions,
    name: String,
    uid: String,
    currentResource: Ref[CoralogixLogger]
  ): ZIO[CoralogixLoggers with Logging with Has[ClusterResource[T]], OperatorFailure[
    CoralogixOperatorFailure
  ], Unit] =
    for {
      resource <- currentResource.get
      component     = Model.attachOwner(name, uid, ctx.resourceType, createComponent(name, resource))
      componentKind = implicitly[ResourceMetadata[T]].kind
      componentName <- component.getName.mapError(KubernetesFailure.apply)
      namespace = resource.metadata
                    .flatMap(_.namespace)
                    .map(K8sNamespace.apply)
                    .getOrElse(K8sNamespace.default)
      queryResult <- ResourceClient.cluster
                       .get[T](componentName)
                       .ifFound
                       .either
      _ <- queryResult match {
             case Left(failure) =>
               provisioningFailed(
                 failedProvisions,
                 currentResource,
                 componentKind,
                 reason = s"Provisioning of $componentKind failed.",
                 failure
               )
             case Right(None) =>
               for {
                 _ <- updateState(
                        currentResource,
                        "PROVISIONING",
                        componentKind,
                        s"Provisioning of $componentKind..."
                      )
                 _ <- log.info(s"Creating a new $componentKind with name $componentName")
                 _ <- ResourceClient.cluster
                        .create(component)
                        .catchAll { failure =>
                          provisioningFailed(
                            failedProvisions,
                            currentResource,
                            phase = componentKind,
                            reason = s"Provisioning of $componentKind failed.",
                            failure
                          )
                        }
                 _ <- replaceStatus(
                        currentResource,
                        status =>
                          store(
                            status
                              .copy(
                                reason = Some(s"Provisioning of $componentKind successful.")
                              ),
                            componentName
                          ),
                        namespace
                      )
               } yield ()
             case Right(_) =>
               log.info(s"Skip: $componentKind already exists") *>
                 replaceStatus(
                   currentResource,
                   status =>
                     store(
                       status,
                       componentName
                     ),
                   namespace
                 )
           }
    } yield ()

  private def setupServiceAccount(
    ctx: OperatorContext,
    failedProvisions: FailedProvisions,
    name: String,
    uid: String,
    currentResource: Ref[CoralogixLogger]
  ): ZIO[CoralogixLoggers with Logging with ServiceAccounts, OperatorFailure[
    CoralogixOperatorFailure
  ], Unit] = {
    import serviceaccounts.metadata
    setupComponent(
      Model.serviceAccount,
      (status, serviceAccountName) => status.copy(serviceAccount = Some(serviceAccountName))
    )(ctx, failedProvisions, name, uid, currentResource)
  }

  private def setupClusterRole(
    ctx: OperatorContext,
    failedProvisions: FailedProvisions,
    name: String,
    uid: String,
    currentResource: Ref[CoralogixLogger]
  ): ZIO[CoralogixLoggers with Logging with ClusterRoles, OperatorFailure[
    CoralogixOperatorFailure
  ], Unit] = {
    import clusterroles.metadata
    setupClusterComponent(
      Model.clusterRole,
      (status, clusterRoleName) => status.copy(clusterRole = Some(clusterRoleName))
    )(ctx, failedProvisions, name, uid, currentResource)
  }

  private def setupClusterRoleBinding(
    ctx: OperatorContext,
    failedProvisions: FailedProvisions,
    name: String,
    uid: String,
    currentResource: Ref[CoralogixLogger]
  ): ZIO[CoralogixLoggers with Logging with ClusterRoleBindings, OperatorFailure[
    CoralogixOperatorFailure
  ], Unit] = {
    import clusterrolebindings.metadata
    setupClusterComponent(
      Model.clusterRoleBinding,
      (status, clusterRoleBindingName) =>
        status.copy(clusterRoleBinding = Some(clusterRoleBindingName))
    )(ctx, failedProvisions, name, uid, currentResource)
  }

  private def setupDaemonSet(
    ctx: OperatorContext,
    failedProvisions: FailedProvisions,
    name: String,
    uid: String,
    currentResource: Ref[CoralogixLogger]
  ): ZIO[CoralogixLoggers with Logging with DaemonSets, OperatorFailure[
    CoralogixOperatorFailure
  ], Unit] = {
    import daemonsets.metadata
    setupComponent(
      Model.daemonSet,
      (status, daemonSetName) => status.copy(daemonSet = Some(daemonSetName))
    )(ctx, failedProvisions, name, uid, currentResource)
  }

  def forNamespace(
    namespace: K8sNamespace,
    buffer: Int,
    metrics: OperatorMetrics
  ): ZIO[CoralogixLoggers, Nothing, Operator[
    Clock with Logging with CoralogixLoggers with ServiceAccounts with ClusterRoles with ClusterRoleBindings with DaemonSets,
    CoralogixOperatorFailure,
    CoralogixLogger
  ]] =
    FailedProvisions.make.flatMap { failedProvisions =>
      Operator.namespaced(
        eventProcessor(failedProvisions) @@ logEvents @@ metered(metrics)
      )(Some(namespace), buffer)
    }

  def forAllNamespaces(
    buffer: Int,
    metrics: OperatorMetrics
  ): ZIO[CoralogixLoggers, Nothing, Operator[
    Clock with Logging with CoralogixLoggers with ServiceAccounts with ClusterRoles with ClusterRoleBindings with DaemonSets,
    CoralogixOperatorFailure,
    CoralogixLogger
  ]] =
    FailedProvisions.make.flatMap { failedProvisions =>
      Operator.namespaced(
        eventProcessor(failedProvisions) @@ logEvents @@ metered(metrics)
      )(None, buffer)
    }

}
