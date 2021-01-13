package com.coralogix.operator.logic.operators.coralogixlogger

import com.coralogix.operator.logic.{ CoralogixOperatorFailure, ProvisioningFailed }
import com.coralogix.operator.logic.aspects._
import com.coralogix.operator.monitoring.OperatorMetrics
import com.coralogix.zio.k8s.client.{ K8sFailure, NamespacedResource }
import com.coralogix.zio.k8s.client.K8sFailure.syntax._
import com.coralogix.zio.k8s.client.com.coralogix.loggers.coralogixloggers.v1.{
  metadata,
  replaceStatus,
  Coralogixloggers
}
import com.coralogix.zio.k8s.client.com.coralogix.loggers.coralogixloggers.{
  v1 => coralogixloggers
}
import com.coralogix.zio.k8s.client.com.coralogix.loggers.definitions.coralogixlogger.v1.Coralogixlogger
import com.coralogix.zio.k8s.client.model._
import com.coralogix.zio.k8s.client.serviceaccounts.v1.ServiceAccounts
import com.coralogix.zio.k8s.client.serviceaccounts.{ v1 => serviceaccounts }
import com.coralogix.zio.k8s.model.core.v1.ServiceAccount
import com.coralogix.zio.k8s.operator.Operator.{ EventProcessor, OperatorContext }
import com.coralogix.zio.k8s.operator.OperatorLogging._
import com.coralogix.zio.k8s.operator.aspects.logEvents
import com.coralogix.zio.k8s.operator.{
  KubernetesFailure,
  Operator,
  OperatorError,
  OperatorFailure,
  OperatorLogging
}
import zio.{ Cause, Has, ZIO }
import zio.clock.Clock
import zio.logging.{ log, Logging }

object CoralogixloggerOperator {
  private def eventProcessor(): EventProcessor[
    Logging with Coralogixloggers with ServiceAccounts,
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
  ): ZIO[Logging with Coralogixloggers with ServiceAccounts, OperatorFailure[
    CoralogixOperatorFailure
  ], Unit] =
    skipIfAlredyRunning(resource) {
      for {
        name <- resource.getName.mapError(KubernetesFailure.apply)
        uid  <- resource.getUid.mapError(KubernetesFailure.apply)
        _ <-
          updateState(
            resource,
            "PENDING",
            "Initializing Provision",
            s"Provisioning of '$name' in namespace '${resource.metadata.flatMap(_.namespace).getOrElse("-")}'"
          )
        _ <- setupServiceAccount(ctx, name, uid, resource)
      } yield ()
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

  private def updateState(
    resource: Coralogixlogger,
    newState: String,
    newPhase: String,
    newReason: String
  ): ZIO[Coralogixloggers, OperatorFailure[CoralogixOperatorFailure], Coralogixlogger] = {
    val oldStatus = resource.status.getOrElse(Coralogixlogger.Status())
    val replacedStatus = oldStatus.copy(
      state = Some(newState),
      phase = Some(newPhase),
      reason = Some(newReason)
    )
    coralogixloggers
      .replaceStatus(
        resource,
        replacedStatus,
        resource.metadata
          .flatMap(_.namespace)
          .map(K8sNamespace.apply)
          .getOrElse(K8sNamespace.default)
      )
      .mapError(KubernetesFailure.apply)
  }

  private def provisioningFailed(
    resource: Coralogixlogger,
    phase: String,
    reason: String,
    k8sReason: K8sFailure
  ): ZIO[Coralogixloggers with Logging, OperatorFailure[CoralogixOperatorFailure], Nothing] =
    logFailure(
      reason,
      Cause.fail[OperatorFailure[CoralogixOperatorFailure]](KubernetesFailure(k8sReason))
    ) *>
      updateState(resource, "FAILED", phase, reason) *>
      ZIO.fail(OperatorError(ProvisioningFailed))

  private def setupServiceAccount(
    ctx: OperatorContext,
    name: String,
    uid: String,
    resource: Coralogixlogger
  ): ZIO[Coralogixloggers with Logging with Has[
    NamespacedResource[ServiceAccount]
  ], OperatorFailure[CoralogixOperatorFailure], Unit] = {
    val serviceAccount =
      Model.attachOwner(name, uid, ctx.resourceType, Model.serviceAccount(name, resource))

    for {
      serviceAccountName <- serviceAccount.getName.mapError(KubernetesFailure.apply)
      namespace = resource.metadata
                    .flatMap(_.namespace)
                    .map(K8sNamespace.apply)
                    .getOrElse(K8sNamespace.default)
      queryResult <- serviceaccounts
                       .get(serviceAccountName, namespace)
                       .ifFound
                       .either
      _ <- queryResult match {
             case Left(failure) =>
               provisioningFailed(
                 resource,
                 phase = "ServiceAccount",
                 reason = "Provisioning of ServiceAccount failed.",
                 failure
               )
             case Right(None) =>
               for {
                 _ <- updateState(
                        resource,
                        "PROVISIONING",
                        "ServiceAccount",
                        "Provisioning of ServiceAccount..."
                      )
                 _ <- log.info(s"Creating a new ServiceAccount with name $serviceAccountName")
                 _ <- serviceaccounts
                        .create(serviceAccount, namespace)
                        .catchAll { failure =>
                          provisioningFailed(
                            resource,
                            phase = "ServiceAccount",
                            reason = "Provisioning of ServiceAccount failed.",
                            failure
                          )
                        }
                 _ <- replaceStatus(
                        resource,
                        resource.status
                          .getOrElse(Coralogixlogger.Status())
                          .copy(
                            serviceAccount = Some(serviceAccountName),
                            reason = Some("Provisioning of ServiceAccount successful.")
                          ),
                        namespace
                      ).mapError(KubernetesFailure.apply)
               } yield ()
             case Right(_) =>
               log.info(s"Skip: ServiceAccount already exists") *>
                 replaceStatus(
                   resource,
                   resource.status
                     .getOrElse(Coralogixlogger.Status())
                     .copy(serviceAccount = Some(serviceAccountName)),
                   namespace
                 ).mapError(KubernetesFailure.apply)
           }
    } yield ()
  }

  def forNamespace(
    namespace: K8sNamespace,
    buffer: Int,
    metrics: OperatorMetrics
  ): ZIO[Coralogixloggers, Nothing, Operator[
    Clock with Logging with Coralogixloggers with ServiceAccounts,
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
    Clock with Logging with Coralogixloggers with ServiceAccounts,
    CoralogixOperatorFailure,
    Coralogixlogger
  ]] =
    Operator.namespaced(
      eventProcessor() @@ logEvents @@ metered(metrics)
    )(None, buffer)
}
