package com.coralogix.operator.logic.operators.alertset

import com.coralogix.alerts.grpc.external.v1.ZioAlertService.AlertServiceClient
import com.coralogix.alerts.grpc.external.v1.{ DeleteAlertRequest, UpdateAlertRequest }
import com.coralogix.operator.logic.aspects.metered
import com.coralogix.operator.logic.operators.alertset.ModelTransformations._
import com.coralogix.operator.logic.operators.alertset.StatusUpdate.runStatusUpdates
import com.coralogix.operator.logic._
import com.coralogix.operator.monitoring.OperatorMetrics
import com.coralogix.zio.k8s.client.NamespacedResourceStatus
import com.coralogix.zio.k8s.client.com.coralogix.alertsets.v1.metadata
import com.coralogix.zio.k8s.client.com.coralogix.alertsets.{ v1 => alertsets }
import com.coralogix.zio.k8s.client.com.coralogix.definitions.alertset.v1.AlertSet
import com.coralogix.zio.k8s.client.model.primitives.{ AlertId, AlertName }
import com.coralogix.zio.k8s.client.model._
import com.coralogix.zio.k8s.operator.Operator.{ EventProcessor, OperatorContext }
import com.coralogix.zio.k8s.operator.aspects.logEvents
import com.coralogix.zio.k8s.operator.{ KubernetesFailure, Operator }
import zio.clock.Clock
import zio.logging.{ log, Logging }
import zio.{ Has, ZIO }

object AlertSetOperator {
  private def eventProcessor(): EventProcessor[
    Logging with alertsets.AlertSets with AlertServiceClient,
    CoralogixOperatorFailure,
    AlertSet
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
            updates <- createNewAlerts(item.spec.alerts.toSet)
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
                up0 <- modifyExistingAlerts(toUpdate)
                up1 <- createNewAlerts(toAdd.map(byName.apply))
                up2 <- deleteAlerts(toRemove)
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

  private def createNewAlerts(
    alerts: Set[AlertSet.Spec.Alerts]
  ): ZIO[Logging with AlertServiceClient, Nothing, Vector[StatusUpdate]] =
    ZIO
      .foreachPar(alerts.toVector) { alert =>
        (for {
          _           <- log.info(s"Creating alert '${alert.name.value}'")
          createAlert <- ZIO.fromEither(toCreateAlert(alert)).mapError(CustomResourceError.apply)
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
            ZIO.succeed(
              StatusUpdate.RecordFailure(
                alert.name,
                CoralogixOperatorFailure.toFailureString(failure)
              )
            )
        }
      }

  private def modifyExistingAlerts(
    mappings: Map[AlertName, (AlertId, AlertSet.Spec.Alerts)]
  ): ZIO[AlertServiceClient with Logging, Nothing, Vector[StatusUpdate]] =
    ZIO
      .foreachPar(mappings.toVector) {
        case (alertName, (id, data)) =>
          (for {
            _     <- log.info(s"Modifying alert '${alertName.value}' (${id.value})")
            alert <- ZIO.fromEither(toAlert(data, id)).mapError(CustomResourceError.apply)
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
              ZIO.succeed(
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
              ZIO.succeed(
                StatusUpdate.RecordFailure(name, CoralogixOperatorFailure.toFailureString(failure))
              )
          }
      }

  private def withExpectedStatus[R <: Logging, E](
    alertSet: AlertSet
  )(f: AlertSet.Status => ZIO[R, E, Unit]): ZIO[R, E, Unit] =
    alertSet.status match {
      case Some(status) =>
        f(status)
      case None =>
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
  ): ZIO[Logging with Has[
    NamespacedResourceStatus[AlertSet.Status, AlertSet]
  ], KubernetesFailure, Unit] = {
    val initialStatus =
      resource.status.getOrElse(AlertSet.Status(alertIds = Some(Vector.empty)))
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
  ): ZIO[alertsets.AlertSets, Nothing, Operator[
    Clock with Logging with alertsets.AlertSets with AlertServiceClient,
    CoralogixOperatorFailure,
    AlertSet
  ]] =
    Operator.namespaced(
      eventProcessor() @@ logEvents @@ metered(metrics)
    )(Some(namespace), buffer)

  def forAllNamespaces(
    buffer: Int,
    metrics: OperatorMetrics
  ): ZIO[alertsets.AlertSets, Nothing, Operator[
    Clock with Logging with alertsets.AlertSets with AlertServiceClient,
    CoralogixOperatorFailure,
    AlertSet
  ]] =
    Operator.namespaced(
      eventProcessor() @@ logEvents @@ metered(metrics)
    )(None, buffer)

  def forTest(): ZIO[alertsets.AlertSets, Nothing, Operator[
    Logging with alertsets.AlertSets with AlertServiceClient,
    CoralogixOperatorFailure,
    AlertSet
  ]] =
    Operator.namespaced(eventProcessor())(Some(K8sNamespace("default")), 256)
}
