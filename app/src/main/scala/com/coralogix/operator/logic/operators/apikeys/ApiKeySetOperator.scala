package com.coralogix.operator.logic.operators.apikeys

import com.coralogix.users.v2beta1.ZioApiKeysService.ApiKeysServiceClient
import com.coralogix.users.v2beta1.CreateApiKeyRequest
import com.coralogix.operator.logic.aspects.metered
import com.coralogix.operator.logic.operators.apikeys.StatusUpdate.runStatusUpdates
import com.coralogix.operator.logic._
import com.coralogix.operator.monitoring.OperatorMetrics
import com.coralogix.zio.k8s.client.NamespacedResourceStatus
import com.coralogix.zio.k8s.client.com.coralogix.v1.apikeysets
import com.coralogix.zio.k8s.client.com.coralogix.definitions.apikeyset.v1.ApiKeySet
import com.coralogix.zio.k8s.client.com.coralogix.v1.apikeysets.ApiKeySets
import com.coralogix.zio.k8s.client.model._
import com.coralogix.zio.k8s.operator.Operator.{ EventProcessor, OperatorContext }
import com.coralogix.zio.k8s.operator.OperatorLogging.logFailure
import com.coralogix.zio.k8s.operator.aspects.logEvents
import com.coralogix.zio.k8s.operator.{ KubernetesFailure, Operator }
import zio.clock.Clock
import zio.logging.{ log, Logging }
import zio.{ Cause, Has, ZIO }

object ApiKeySetOperator {
  private def eventProcessor(): EventProcessor[
    Logging with ApiKeySets with ApiKeysServiceClient,
    CoralogixOperatorFailure,
    ApiKeySet
  ] =
    (ctx, event) =>
      event match {
        case Reseted =>
          ZIO.unit
        case Added(item) =>
          if (
            item.generation == 0L || // new item
            !item.status
              .flatMap(_.lastUploadedGeneration)
              .contains(item.generation) // already synchronized
          ) for {
            name    <- item.getName.mapError(KubernetesFailure.apply)
            updates <- createNewApiKeys(ctx, name, item.spec.apikeys.toSet)
            _ <- applyStatusUpdates(
                   ctx,
                   item,
                   StatusUpdate.ClearFailures +:
                     StatusUpdate.UpdateLastUploadedGeneration(item.generation) +:
                     updates
                 )
          } yield ()
          else
            log.debug(
              s"Alert set '${item.metadata.flatMap(_.name).getOrElse("")}' with generation ${item.generation} is already added"
            )
        case Modified(item) =>
          withExpectedStatus(item) { status =>
            if (status.lastUploadedGeneration.getOrElse(0L) < item.generation) {
              val mappings = status.alertIds.getOrElse(Vector.empty)
              val byName = item.spec.alerts.map(alert => alert.name -> alert).toMap
              val alreadyAssigned = mappingToMap(mappings)
              val toAdd = byName.keySet.diff(alreadyAssigned.keySet)
              val toRemove = alreadyAssigned -- byName.keysIterator
              val toUpdate = alreadyAssigned.flatMap {
                case (name, status) =>
                  byName.get(name).map(data => name -> (status, data))
              }

              for {
                name <- item.getName.mapError(KubernetesFailure.apply)
                up0  <- modifyExistingAlerts(ctx, name, toUpdate)
                up1  <- createNewApiKeys(ctx, name, toAdd.map(byName.apply))
                up2  <- deleteAlerts(toRemove)
                _ <- applyStatusUpdates(
                       ctx,
                       item,
                       StatusUpdate.ClearFailures +:
                         StatusUpdate.UpdateLastUploadedGeneration(item.generation) +:
                         (up0 ++ up1 ++ up2)
                     )
              } yield ()
            } else
              log.debug(
                s"Skipping modification of alert set '${item.metadata.flatMap(_.name).getOrElse("")}' with generation ${item.generation}"
              )
          }
        case Deleted(item) =>
          withExpectedStatus(item) { status =>
            val mappings = status.alertIds.getOrElse(Vector.empty)
            deleteAlerts(mappingToMap(mappings)).unit
          }
      }

  private def createNewApiKeys(
    ctx: OperatorContext,
    name: String,
    apiKeys: Set[ApiKeySet.Spec.Apikeys]
  ): ZIO[Logging with ApiKeysServiceClient, Nothing, Vector[StatusUpdate]] =
    ZIO
      .foreachPar(apiKeys.toVector) { alert =>
        (for {
          _ <- log.info(s"Creating alert '${alert.name.value}'")
          createAlert <-
            ZIO.fromEither(toCreateAlert(alert, ctx, name)).mapError(CustomResourceError.apply)
          response <- AlertServiceClient
                        .createAlert(createAlert)
                        .mapError(GrpcFailure.apply)
          _ <- log.trace(
                 s"Alerts API response for creating alert '${alert.name.value}': $response"
               )
          alertId <- ZIO.fromEither(
                       response.alert
                         .flatMap(_.id)
                         .map(AlertId.apply)
                         .toRight(UndefinedGrpcField("CreateAlertResponse.alert.id"))
                     )
        } yield StatusUpdate.AddRuleGroupMapping(alert.name, alertId)).catchAll {
          (failure: CoralogixOperatorFailure) =>
            logFailure(s"Failed to create alert '${alert.name.value}'", Cause.fail(failure)).as(
              StatusUpdate.RecordFailure(
                alert.name,
                CoralogixOperatorFailure.toFailureString(failure)
              )
            )
        }
      }

  private def modifyExistingAlerts(
    ctx: OperatorContext,
    name: String,
    mappings: Map[AlertName, (AlertId, AlertSet.Spec.Alerts)]
  ): ZIO[AlertServiceClient with Logging, Nothing, Vector[StatusUpdate]] =
    ZIO
      .foreachPar(mappings.toVector) {
        case (alertName, (id, data)) =>
          (for {
            _ <- log.info(s"Modifying alert '${alertName.value}' (${id.value})")
            alert <-
              ZIO.fromEither(toAlert(data, id, ctx, name)).mapError(CustomResourceError.apply)
            response <- AlertServiceClient
                          .updateAlert(
                            UpdateAlertRequest(
                              alert = Some(alert)
                            )
                          )
                          .mapError(GrpcFailure.apply)
            _ <-
              log.trace(
                s"Alerts API response for modifying alert '${alertName.value}' (${id.value}): $response"
              )
            alertId <- ZIO.fromEither(
                         response.alert
                           .flatMap(_.id)
                           .map(AlertId.apply)
                           .toRight(UndefinedGrpcField("UpdateAlertResponse.alert.id"))
                       )
          } yield StatusUpdate.AddRuleGroupMapping(alertName, alertId)).catchAll {
            (failure: CoralogixOperatorFailure) =>
              logFailure(s"Failed to modify alert '${alertName.value}'", Cause.fail(failure)).as(
                StatusUpdate.RecordFailure(
                  alertName,
                  CoralogixOperatorFailure.toFailureString(failure)
                )
              )
          }
      }

  private def deleteAlerts(
    mappings: Map[AlertName, AlertId]
  ): ZIO[AlertServiceClient with Logging, Nothing, Vector[StatusUpdate]] =
    ZIO
      .foreachPar(mappings.toVector) {
        case (name, id) =>
          (for {
            _ <- log.info(s"Deleting alert '${name.value}' (${id.value})'")
            response <- AlertServiceClient
                          .deleteAlert(DeleteAlertRequest(Some(id.value)))
                          .mapError(GrpcFailure.apply)
            _ <-
              log.trace(
                s"Alerts API response for deleting alert '${name.value}' (${id.value}): $response"
              )
          } yield StatusUpdate.DeleteRuleGroupMapping(name)).catchAll {
            (failure: CoralogixOperatorFailure) =>
              logFailure(s"Failed to delete alert '${name.value}'", Cause.fail(failure)).as(
                StatusUpdate.RecordFailure(name, CoralogixOperatorFailure.toFailureString(failure))
              )
          }
      }

  private def withExpectedStatus[R <: Logging, E](
    alertSet: AlertSet
  )(f: AlertSet.Status => ZIO[R, E, Unit]): ZIO[R, E, Unit] =
    alertSet.status match {
      case Optional.Present(status) =>
        f(status)
      case Optional.Absent =>
        log.warn(
          s"Rule group set '${alertSet.metadata.flatMap(_.name).getOrElse("")}' has no status information"
        )
    }

  private def mappingToMap(
    mappings: Vector[AlertSet.Status.AlertIds]
  ): Map[AlertName, AlertId] =
    mappings.map { mapping =>
      mapping.name -> mapping.id
    }.toMap

  private def applyStatusUpdates(
    ctx: OperatorContext,
    resource: AlertSet,
    updates: Vector[StatusUpdate]
  ): ZIO[Logging with AlertSets, KubernetesFailure, Unit] = {
    val initialStatus =
      resource.status.getOrElse(AlertSet.Status(alertIds = Vector.empty[AlertSet.Status.AlertIds]))
    val updatedStatus = runStatusUpdates(initialStatus, updates)

    alertsets
      .replaceStatus(
        resource,
        updatedStatus,
        resource.metadata
          .flatMap(_.namespace)
          .map(K8sNamespace.apply)
          .getOrElse(K8sNamespace.default)
      )
      .mapError(KubernetesFailure.apply)
      .unit
  }.when(updates.nonEmpty)

  def forNamespace(
    namespace: K8sNamespace,
    buffer: Int,
    metrics: OperatorMetrics
  ): ZIO[AlertSets, Nothing, Operator[
    Clock with Logging with AlertSets with AlertServiceClient,
    CoralogixOperatorFailure,
    AlertSet
  ]] =
    ZIO.service[AlertSets.Service].flatMap { alertSets =>
      Operator
        .namespaced(
          eventProcessor() @@ logEvents @@ metered(metrics)
        )(Some(namespace), buffer)
        .provide(alertSets.asGeneric)
    }

  def forAllNamespaces(
    buffer: Int,
    metrics: OperatorMetrics
  ): ZIO[AlertSets, Nothing, Operator[
    Clock with Logging with AlertSets with AlertServiceClient,
    CoralogixOperatorFailure,
    AlertSet
  ]] =
    ZIO.service[AlertSets.Service].flatMap { alertSets =>
      Operator
        .namespaced(
          eventProcessor() @@ logEvents @@ metered(metrics)
        )(None, buffer)
        .provide(alertSets.asGeneric)
    }

  def forTest(): ZIO[AlertSets, Nothing, Operator[
    Logging with AlertSets with AlertServiceClient,
    CoralogixOperatorFailure,
    AlertSet
  ]] =
    ZIO.service[AlertSets.Service].flatMap { alertSets =>
      Operator
        .namespaced(eventProcessor())(Some(K8sNamespace("default")), 256)
        .provide(alertSets.asGeneric)
    }
}
