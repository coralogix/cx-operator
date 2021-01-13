package com.coralogix.operator.logic.operators.coralogixlogger

import com.coralogix.operator.logic.aspects._
import com.coralogix.operator.logic.{ CoralogixOperatorFailure, ProvisioningFailed }
import com.coralogix.operator.monitoring.OperatorMetrics
import com.coralogix.zio.k8s.client.K8sFailure.syntax._
import com.coralogix.zio.k8s.client.com.coralogix.loggers.coralogixloggers.v1.{
  metadata,
  Coralogixloggers
}
import com.coralogix.zio.k8s.client.com.coralogix.loggers.coralogixloggers.{
  v1 => coralogixloggers
}
import com.coralogix.zio.k8s.client.com.coralogix.loggers.definitions.coralogixlogger.v1.Coralogixlogger
import com.coralogix.zio.k8s.client.io.k8s.authorization.rbac.clusterrolebindings.v1.ClusterRoleBindings
import com.coralogix.zio.k8s.client.model._
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
import zio.{ Cause, Has, Ref, ZIO }

object CoralogixloggerOperator {
  private def eventProcessor(): EventProcessor[
    Logging with Coralogixloggers with ServiceAccounts with ClusterRoles with ClusterRoleBindings with DaemonSets,
    CoralogixOperatorFailure,
    Coralogixlogger
  ] =
    (ctx, event) =>
      event match {
        case Reseted =>
          ZIO.unit
        case Added(item) =>
          setupLogger(ctx, item)
        case Modified(item) =>
          setupLogger(ctx, item)
        case Deleted(item) =>
          // The generated items are owned by the logger and get automatically garbage collected
          ZIO.unit
      }

  private def setupLogger(
    ctx: OperatorContext,
    resource: Coralogixlogger
  ): ZIO[
    Logging with Coralogixloggers with ServiceAccounts with ClusterRoles with ClusterRoleBindings with DaemonSets,
    OperatorFailure[
      CoralogixOperatorFailure
    ],
    Unit
  ] =
    skipIfAlredyRunning(resource) {
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
          _ <- setupServiceAccount(ctx, name, uid, currentResource)
          _ <- setupClusterRole(ctx, name, uid, currentResource)
          _ <- setupClusterRoleBinding(ctx, name, uid, currentResource)
          _ <- setupDaemonSet(ctx, name, uid, currentResource)
          _ <- updateState(
                 currentResource,
                 "RUNNING",
                 "Provisioning Succeeded",
                 s"CoralogixLogger '$name' successfully provisioned in namespace '${resource.metadata.flatMap(_.namespace).getOrElse("-")}'"
               )
          _ <- log.info("Provision succeeded")
        } yield ()
      }
    }.catchSome {
      case OperatorError(ProvisioningFailed) =>
        log.info(s"Provision failed")
    }

  private def skipIfAlredyRunning[R <: Logging](resource: Coralogixlogger)(
    f: ZIO[R, OperatorFailure[CoralogixOperatorFailure], Unit]
  ): ZIO[R, OperatorFailure[CoralogixOperatorFailure], Unit] =
    if (resource.status.flatMap(_.state).contains("RUNNING"))
      log.info(s"CoralogixLogger is already running")
    else
      f

  private def withCurrentResource[R, E, A](
    resource: Coralogixlogger
  )(f: Ref[Coralogixlogger] => ZIO[R, E, A]): ZIO[R, E, A] =
    Ref.make(resource).flatMap(f)

  private def updateState(
    currentResource: Ref[Coralogixlogger],
    newState: String,
    newPhase: String,
    newReason: String
  ): ZIO[Coralogixloggers, OperatorFailure[CoralogixOperatorFailure], Unit] =
    for {
      resource <- currentResource.get
      oldStatus = resource.status.getOrElse(Coralogixlogger.Status())
      replacedStatus = oldStatus.copy(
                         state = Some(newState),
                         phase = Some(newPhase),
                         reason = Some(newReason)
                       )
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

  private def replaceStatus(
    of: Ref[Coralogixlogger],
    updateStatus: Coralogixlogger.Status => Coralogixlogger.Status,
    namespace: K8sNamespace,
    dryRun: Boolean = false
  ): ZIO[Coralogixloggers, KubernetesFailure, Unit] =
    for {
      resource <- of.get
      updatedStatus = updateStatus(resource.status.getOrElse(Coralogixlogger.Status()))
      updatedResource <- coralogixloggers
                           .replaceStatus(resource, updatedStatus, namespace, dryRun)
                           .mapError(KubernetesFailure.apply)
      _ <- of.set(updatedResource)
    } yield ()

  private def provisioningFailed(
    currentResource: Ref[Coralogixlogger],
    phase: String,
    reason: String,
    k8sReason: K8sFailure
  ): ZIO[Coralogixloggers with Logging, OperatorFailure[CoralogixOperatorFailure], Nothing] =
    logFailure(
      reason,
      Cause.fail[OperatorFailure[CoralogixOperatorFailure]](KubernetesFailure(k8sReason))
    ) *>
      updateState(currentResource, "FAILED", phase, reason) *>
      ZIO.fail(OperatorError(ProvisioningFailed))

  private def setupComponent[T <: Object: ObjectTransformations: ResourceMetadata: Tag](
    createComponent: (String, Coralogixlogger) => T,
    store: (Coralogixlogger.Status, String) => Coralogixlogger.Status
  )(
    ctx: OperatorContext,
    name: String,
    uid: String,
    currentResource: Ref[Coralogixlogger]
  ): ZIO[Coralogixloggers with Logging with Has[NamespacedResource[T]], OperatorFailure[
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
  private def setupClusterComponent[T <: Object: ObjectTransformations: ResourceMetadata: Tag](
    createComponent: (String, Coralogixlogger) => T,
    store: (Coralogixlogger.Status, String) => Coralogixlogger.Status
  )(
    ctx: OperatorContext,
    name: String,
    uid: String,
    currentResource: Ref[Coralogixlogger]
  ): ZIO[Coralogixloggers with Logging with Has[ClusterResource[T]], OperatorFailure[
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
    name: String,
    uid: String,
    currentResource: Ref[Coralogixlogger]
  ): ZIO[Coralogixloggers with Logging with ServiceAccounts, OperatorFailure[
    CoralogixOperatorFailure
  ], Unit] = {
    import serviceaccounts.metadata
    setupComponent(
      Model.serviceAccount,
      (status, serviceAccountName) => status.copy(serviceAccount = Some(serviceAccountName))
    )(ctx, name, uid, currentResource)
  }

  private def setupClusterRole(
    ctx: OperatorContext,
    name: String,
    uid: String,
    currentResource: Ref[Coralogixlogger]
  ): ZIO[Coralogixloggers with Logging with ClusterRoles, OperatorFailure[
    CoralogixOperatorFailure
  ], Unit] = {
    import clusterroles.metadata
    setupClusterComponent(
      Model.clusterRole,
      (status, clusterRoleName) => status.copy(clusterRole = Some(clusterRoleName))
    )(ctx, name, uid, currentResource)
  }

  private def setupClusterRoleBinding(
    ctx: OperatorContext,
    name: String,
    uid: String,
    currentResource: Ref[Coralogixlogger]
  ): ZIO[Coralogixloggers with Logging with ClusterRoleBindings, OperatorFailure[
    CoralogixOperatorFailure
  ], Unit] = {
    import clusterrolebindings.metadata
    setupClusterComponent(
      Model.clusterRoleBinding,
      (status, clusterRoleBindingName) =>
        status.copy(clusterRoleBinding = Some(clusterRoleBindingName))
    )(ctx, name, uid, currentResource)
  }

  private def setupDaemonSet(
    ctx: OperatorContext,
    name: String,
    uid: String,
    currentResource: Ref[Coralogixlogger]
  ): ZIO[Coralogixloggers with Logging with DaemonSets, OperatorFailure[
    CoralogixOperatorFailure
  ], Unit] = {
    import daemonsets.metadata
    setupComponent(
      Model.daemonSet,
      (status, daemonSetName) => status.copy(daemonSet = Some(daemonSetName))
    )(ctx, name, uid, currentResource)
  }

  def forNamespace(
    namespace: K8sNamespace,
    buffer: Int,
    metrics: OperatorMetrics
  ): ZIO[Coralogixloggers, Nothing, Operator[
    Clock with Logging with Coralogixloggers with ServiceAccounts with ClusterRoles with ClusterRoleBindings with DaemonSets,
    CoralogixOperatorFailure,
    Coralogixlogger
  ]] =
    Operator.namespaced(
      eventProcessor() @@ logEvents @@ metered(metrics)
    )(Some(namespace), buffer)

  def forAllNamespaces(
    buffer: Int,
    metrics: OperatorMetrics
  ): ZIO[Coralogixloggers, Nothing, Operator[
    Clock with Logging with Coralogixloggers with ServiceAccounts with ClusterRoles with ClusterRoleBindings with DaemonSets,
    CoralogixOperatorFailure,
    Coralogixlogger
  ]] =
    Operator.namespaced(
      eventProcessor() @@ logEvents @@ metered(metrics)
    )(None, buffer)
}
