package com.coralogix.operator.logic.operators.coralogixlogger

import com.coralogix.operator.logic.aspects._
import com.coralogix.operator.logic.{ CoralogixOperatorFailure, ProvisioningFailed }
import com.coralogix.operator.monitoring.OperatorMetrics
import com.coralogix.zio.k8s.client.K8sFailure.syntax._
import com.coralogix.zio.k8s.client.apps.v1.daemonsets.DaemonSets
import com.coralogix.zio.k8s.client.com.coralogix.loggers.v1.coralogixloggers.CoralogixLoggers
import com.coralogix.zio.k8s.client.com.coralogix.loggers.v1.coralogixloggers
import com.coralogix.zio.k8s.client.com.coralogix.loggers.definitions.coralogixlogger.v1.CoralogixLogger
import com.coralogix.zio.k8s.client.authorization.rbac.v1.clusterrolebindings.ClusterRoleBindings
import com.coralogix.zio.k8s.client.authorization.rbac.v1.clusterroles.ClusterRoles
import com.coralogix.zio.k8s.client.model.K8sObject._
import com.coralogix.zio.k8s.client.model._
import com.coralogix.zio.k8s.client.v1.serviceaccounts.ServiceAccounts
import com.coralogix.zio.k8s.client.{ ClusterResource, K8sFailure, NamespacedResource, Resource }
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
import zio.{ Cause, Has, Ref, ZIO }

object CoralogixLoggerOperator {
  private def eventProcessor(failedProvisions: FailedProvisions): EventProcessor[
    Logging with CoralogixLoggers with ServiceAccounts.Generic with ClusterRoles.Generic with ClusterRoleBindings.Generic with DaemonSets.Generic,
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
    Logging with CoralogixLoggers with ServiceAccounts.Generic with ClusterRoles.Generic with ClusterRoleBindings.Generic with DaemonSets.Generic,
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
    }.catchSome { case OperatorError(ProvisioningFailed) =>
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
               state = newState,
               phase = newPhase,
               reason = newReason
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

  private def setupNamespacedComponent[T: K8sObject: ResourceMetadata: Tag](
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
    ZIO.service[NamespacedResource[T]].flatMap { resourceClient =>
      setupComponent(createComponent, store, namespaced = true)(
        ctx,
        resourceClient.asGenericResource,
        failedProvisions,
        name,
        uid,
        currentResource
      )
    }

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
    ZIO.service[ClusterResource[T]].flatMap { resourceClient =>
      setupComponent(createComponent, store, namespaced = false)(
        ctx,
        resourceClient.asGenericResource,
        failedProvisions,
        name,
        uid,
        currentResource
      )
    }

  private def setupComponent[T: K8sObject: ResourceMetadata: Tag](
    createComponent: (String, CoralogixLogger) => T,
    store: (CoralogixLogger.Status, String) => CoralogixLogger.Status,
    namespaced: Boolean
  )(
    ctx: OperatorContext,
    resourceClient: Resource[T],
    failedProvisions: FailedProvisions,
    name: String,
    uid: String,
    currentResource: Ref[CoralogixLogger]
  ): ZIO[CoralogixLoggers with Logging, OperatorFailure[
    CoralogixOperatorFailure
  ], Unit] =
    for {
      resource <- currentResource.get
      component     = createComponent(name, resource).attachOwner(name, uid, ctx.resourceType)
      componentKind = implicitly[ResourceMetadata[T]].kind
      componentName <- component.getName.mapError(KubernetesFailure.apply)
      namespace = resource.metadata
                    .flatMap(_.namespace)
                    .toOption
                    .map(K8sNamespace.apply)
                    .getOrElse(K8sNamespace.default)
      componentNamespace = if (namespaced) Some(namespace) else None
      queryResult <- resourceClient
                       .get(componentName, componentNamespace)
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
                 _ <- resourceClient
                        .create(component, componentNamespace)
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
                                reason = s"Provisioning of $componentKind successful."
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
  ): ZIO[CoralogixLoggers with Logging with ServiceAccounts.Generic, OperatorFailure[
    CoralogixOperatorFailure
  ], Unit] =
    setupNamespacedComponent(
      Model.serviceAccount,
      (status, serviceAccountName) => status.copy(serviceAccount = serviceAccountName)
    )(ctx, failedProvisions, name, uid, currentResource)

  private def setupClusterRole(
    ctx: OperatorContext,
    failedProvisions: FailedProvisions,
    name: String,
    uid: String,
    currentResource: Ref[CoralogixLogger]
  ): ZIO[CoralogixLoggers with Logging with ClusterRoles.Generic, OperatorFailure[
    CoralogixOperatorFailure
  ], Unit] =
    setupClusterComponent(
      Model.clusterRole,
      (status, clusterRoleName) => status.copy(clusterRole = clusterRoleName)
    )(ctx, failedProvisions, name, uid, currentResource)

  private def setupClusterRoleBinding(
    ctx: OperatorContext,
    failedProvisions: FailedProvisions,
    name: String,
    uid: String,
    currentResource: Ref[CoralogixLogger]
  ): ZIO[CoralogixLoggers with Logging with ClusterRoleBindings.Generic, OperatorFailure[
    CoralogixOperatorFailure
  ], Unit] =
    setupClusterComponent(
      Model.clusterRoleBinding,
      (status, clusterRoleBindingName) => status.copy(clusterRoleBinding = clusterRoleBindingName)
    )(ctx, failedProvisions, name, uid, currentResource)

  private def setupDaemonSet(
    ctx: OperatorContext,
    failedProvisions: FailedProvisions,
    name: String,
    uid: String,
    currentResource: Ref[CoralogixLogger]
  ): ZIO[CoralogixLoggers with Logging with DaemonSets.Generic, OperatorFailure[
    CoralogixOperatorFailure
  ], Unit] =
    setupNamespacedComponent(
      Model.daemonSet,
      (status, daemonSetName) => status.copy(daemonSet = daemonSetName)
    )(ctx, failedProvisions, name, uid, currentResource)

  def forNamespace(
    namespace: K8sNamespace,
    buffer: Int,
    metrics: OperatorMetrics
  ): ZIO[CoralogixLoggers, Nothing, Operator[
    Clock with Logging with CoralogixLoggers with ServiceAccounts.Generic with ClusterRoles.Generic with ClusterRoleBindings.Generic with DaemonSets.Generic,
    CoralogixOperatorFailure,
    CoralogixLogger
  ]] =
    for {
      coralogixLoggers <- ZIO.service[CoralogixLoggers.Service]
      failedProvisions <- FailedProvisions.make
      operator <- Operator
                    .namespaced(
                      eventProcessor(failedProvisions) @@ logEvents @@ metered(metrics)
                    )(Some(namespace), buffer)
                    .provide(coralogixLoggers.asGeneric)
    } yield operator

  def forAllNamespaces(
    buffer: Int,
    metrics: OperatorMetrics
  ): ZIO[CoralogixLoggers, Nothing, Operator[
    Clock with Logging with CoralogixLoggers with ServiceAccounts.Generic with ClusterRoles.Generic with ClusterRoleBindings.Generic with DaemonSets.Generic,
    CoralogixOperatorFailure,
    CoralogixLogger
  ]] =
    for {
      coralogixLoggers <- ZIO.service[CoralogixLoggers.Service]
      failedProvisions <- FailedProvisions.make
      operator <- Operator
                    .namespaced(
                      eventProcessor(failedProvisions) @@ logEvents @@ metered(metrics)
                    )(None, buffer)
                    .provide(coralogixLoggers.asGeneric)
    } yield operator

}
