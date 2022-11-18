package com.coralogix.operator.logic.operators.alertset

import com.coralogix.alerts.v1.alert_service.ZioAlertService.AlertServiceClient
import com.coralogix.alerts.v1.alert_service.{
  DeleteAlertRequest,
  DeleteAlertResponse,
  GetAlertByUniqueIdRequest,
  UpdateAlertRequest,
  ValidateAlertRequest
}
import com.coralogix.operator.logging.Log
import com.coralogix.operator.logging.LogSyntax.FieldBuilder
import com.coralogix.operator.logic._
import com.coralogix.operator.logic.aspects.metered
import com.coralogix.operator.logic.operators.alertset.ModelTransformations._
import com.coralogix.operator.logic.operators.alertset.StatusUpdate.{
  runStatusUpdates,
  RecordFailure
}
import com.coralogix.operator.monitoring.OperatorMetrics
import com.coralogix.zio.k8s.client.HttpFailure
import com.coralogix.zio.k8s.client.com.coralogix.definitions.alertset.v1.AlertSet
import com.coralogix.zio.k8s.client.com.coralogix.definitions.alertset.v1.AlertSet.Spec.Alerts
import com.coralogix.zio.k8s.client.com.coralogix.v1.alertsets
import com.coralogix.zio.k8s.client.com.coralogix.v1.alertsets.AlertSets
import com.coralogix.zio.k8s.client.model._
import com.coralogix.zio.k8s.client.model.primitives.{ AlertId, AlertName, UniqueAlertId }
import com.coralogix.zio.k8s.operator.Operator.{ EventProcessor, OperatorContext }
import com.coralogix.zio.k8s.operator.{ KubernetesFailure, Operator }
import io.circe.syntax.EncoderOps
import io.grpc.Status
import sttp.model.StatusCode
import zio.clock.Clock
import zio.logging.Logging
import zio.{ Has, ZIO }

object AlertSetOperator {
  private[alertset] def filterLabels(alertLabels: List[String])(
    metadataLabels: Map[String, String]
  ): Map[String, String] =
    metadataLabels.filter {
      case (key, _) =>
        alertLabels.exists(label => key.matches(label))
    }

  private def eventProcessor(alertLabels: List[String]): EventProcessor[
    Logging with AlertSets with AlertServiceClient,
    CoralogixOperatorFailure,
    AlertSet
  ] =
    (ctx, event) =>
      event match {
        case Reseted() =>
          ZIO.unit

        case Added(item)
            if item.generation == 1L && item.status
              .flatMap(_.lastUploadedGeneration)
              .isEmpty => // new item
          for {
            alertSetName  <- item.getName.mapError(KubernetesFailure.apply)
            setValidation <- isSetValid(alertSetName, item.spec.alerts, ctx)
            updates <- {
              setValidation match {
                case Right(_) =>
                  createNewAlerts(
                    ctx,
                    alertSetName,
                    item.spec.alerts.toSet,
                    filterLabels(alertLabels)(item.metadata.flatMap(_.labels).getOrElse(Map.empty))
                  ).map(updates =>
                    StatusUpdate.ClearFailures +: StatusUpdate.UpdateLastUploadedGeneration(
                      item.generation
                    ) +: updates
                  )
                case Left(failures) => ZIO.succeed(failures)
              }

            }
            _ <- applyStatusUpdates(
                   ctx,
                   item,
                   StatusUpdate.UpdateLastUploadedGeneration(item.generation) +: updates
                 )
          } yield ()

        case Added(item)
            if !item.status.flatMap(_.lastUploadedGeneration).contains(item.generation) =>
          modifyFlow(ctx, item)(alertLabels)

        case Added(item) =>
          Log.debug(
            "AlreadyAdded",
            "name"       := item.metadata.flatMap(_.name),
            "generation" := item.generation
          )

        case Modified(item) =>
          modifyFlow(ctx, item)(alertLabels)

        case Deleted(item) =>
          withExpectedStatus(item) { status =>
            val mappings = status.alertIds.getOrElse(Vector.empty)
            deleteAlerts(mappingToMap(mappings)).unit
          }
      }

  private def modifyFlow(ctx: OperatorContext, item: AlertSet)(
    alertLabels: List[String]
  ): ZIO[Logging with AlertSets with AlertServiceClient, KubernetesFailure, Unit] =
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
          alertSetName <- item.getName.mapError(KubernetesFailure.apply)
          setIsValid   <- isSetValid(alertSetName, item.spec.alerts, ctx)
          updates <- {
            setIsValid match {
              case Right(_) =>
                for {
                  up0 <- modifyExistingAlerts(ctx, alertSetName, toUpdate)
                  up1 <- createNewAlerts(
                           ctx,
                           alertSetName,
                           toAdd.map(byName.apply),
                           filterLabels(alertLabels)(
                             item.metadata.flatMap(_.labels).getOrElse(Map.empty)
                           )
                         )
                  up2 <- deleteAlerts(toRemove)
                } yield StatusUpdate.ClearFailures +: (up0 ++ up1 ++ up2)
              case Left(failures) => ZIO.succeed(failures)
            }

          }
          _ <- applyStatusUpdates(
                 ctx,
                 item,
                 StatusUpdate.UpdateLastUploadedGeneration(item.generation) +: updates
               )
        } yield ()
      } else
        Log.debug(
          "SkippingModification",
          "name"       := item.metadata.flatMap(_.name),
          "generation" := item.generation
        )
    }

  private def isSetValid(
    setName: String,
    alerts: Vector[Alerts],
    ctx: OperatorContext
  ): ZIO[Has[AlertServiceClient.ZService[Any, Any]] with Logging, Nothing, Either[Vector[
    RecordFailure
  ], Unit]] =
    ZIO
      .foreachPar(alerts) { a =>
        for {
          alert <-
            ZIO
              .fromEither(toAlert(a, None, ctx, setName))
              .mapError(description => a -> Status.INVALID_ARGUMENT.withDescription(description))
          _ <- AlertServiceClient.validateAlert(ValidateAlertRequest(Some(alert))).mapError(a -> _)
        } yield ()
      }
      .as(Right(()))
      .catchAll {
        case (alert, failure) =>
          val alertName = alert.name
          val integrations =
            alert.notifications.flatMap(_.integrations).map(_.mkString(", ")).getOrElse("")
          val warnMessage =
            if (
              failure.getDescription.contains(
                s"Invalid integration names ($integrations) in alert ${alertName.value}"
              )
            )
              "InvalidIntegrationName"
            else
              "InvalidAlertSet"
          Log
            .error(
              warnMessage,
              "description" -> failure.getDescription.asJson,
              "alertName"   -> alertName.value.asJson
            )
            .as(Left(Vector(RecordFailure(alertName, failure.getDescription))))
      }

  private def createNewAlerts(
    ctx: OperatorContext,
    name: String,
    alerts: Set[AlertSet.Spec.Alerts],
    labels: Map[String, String]
  ): ZIO[Logging with AlertServiceClient, Nothing, Vector[StatusUpdate]] =
    ZIO
      .foreachPar(alerts.toVector) { alert =>
        (for {
          _ <- Log.info("Create", "name" := alert.name.value)
          createAlert <- ZIO
                           .fromEither(toCreateAlert(alert, ctx, name, labels))
                           .mapError(CustomResourceError.apply)
          response <- AlertServiceClient
                        .createAlert(createAlert)
                        .mapError(GrpcFailure.apply)
          _ <- Log.trace(
                 "CreateApiResponse",
                 "name"     := alert.name.value,
                 "response" := response.toString
               )
          uniqueAlertId <-
            ZIO.fromEither(
              response.alert
                .flatMap(_.uniqueIdentifier)
                .map(UniqueAlertId.apply)
                .toRight(UndefinedGrpcField("CreateAlertResponse.alert.uniqueIdentifier"))
            )
        } yield StatusUpdate.AddRuleGroupMapping(alert.name, uniqueAlertId)).catchAll {
          (failure: CoralogixOperatorFailure) =>
            Log
              .error(
                CoralogixOperatorFailure.toThrowable.toThrowable(failure),
                "CannotCreate",
                "name" := alert.name.value
              )
              .as(
                StatusUpdate.RecordFailure(
                  alert.name,
                  CoralogixOperatorFailure.toFailureString(failure)
                )
              )
        }
      }

  private def modifyExistingAlerts(
    ctx: OperatorContext,
    setName: String,
    mappings: Map[AlertName, (UniqueAlertId, AlertSet.Spec.Alerts)]
  ): ZIO[AlertServiceClient with Logging, Nothing, Vector[StatusUpdate]] =
    ZIO
      .foreachPar(mappings.toVector) {
        case (alertName, (uniqueAlertId, data)) =>
          (for {
            _ <- Log.info(
                   "Modifying",
                   "name"             := alertName.value,
                   "uniqueIdentifier" := uniqueAlertId.value
                 )

            maybeAlertId <-
              AlertServiceClient
                .getAlertByUniqueId(GetAlertByUniqueIdRequest(Some(uniqueAlertId.value)))
                .mapBoth(GrpcFailure.apply, _.alert.flatMap(_.id))
            alertId <-
              ZIO.fromOption(maybeAlertId).mapBoth(_ => GrpcFailure(Status.NOT_FOUND), AlertId(_))

            alert <- ZIO
                       .fromEither(toAlert(data, Some(alertId), ctx, setName))
                       .mapError(CustomResourceError.apply)

            response <- AlertServiceClient
                          .updateAlert(UpdateAlertRequest(alert = Some(alert)))
                          .mapError(GrpcFailure.apply)
            _ <- Log.trace(
                   "ModifyApiResponse",
                   "name"          := alertName.value,
                   "id"            := alertId,
                   "uniqueAlertId" := uniqueAlertId,
                   "response"      := response.toString
                 )
            _ <- ZIO.fromEither(
                   response.alert
                     .flatMap(_.id)
                     .map(AlertId.apply)
                     .toRight(UndefinedGrpcField("UpdateAlertResponse.alert.id"))
                 )
          } yield StatusUpdate.AddRuleGroupMapping(alertName, uniqueAlertId)).catchAll {
            (failure: CoralogixOperatorFailure) =>
              Log
                .error(
                  CoralogixOperatorFailure.toThrowable.toThrowable(failure),
                  "CannotModify",
                  "name" := alertName.value
                )
                .as(
                  StatusUpdate.RecordFailure(
                    alertName,
                    CoralogixOperatorFailure.toFailureString(failure)
                  )
                )
          }
      }

  private def deleteAlerts(
    mappings: Map[AlertName, UniqueAlertId]
  ): ZIO[AlertServiceClient with Logging, Nothing, Vector[StatusUpdate]] =
    ZIO
      .foreachPar(mappings.toVector) {
        case (name, uniqueAlertId) =>
          (for {
            _ <- Log.info("Delete", "name" := name.value, "uniqueIdentifier" := uniqueAlertId.value)
            maybeAlertId <-
              AlertServiceClient
                .getAlertByUniqueId(GetAlertByUniqueIdRequest(Some(uniqueAlertId.value)))
                .map(_.alert.flatMap(_.id))
                .catchSome { case Status.NOT_FOUND => ZIO.none }
                .mapError(GrpcFailure.apply)

            alertId <- ZIO
                         .fromOption(maybeAlertId)
                         .map(AlertId(_))
                         .catchAll(_ => ZIO.succeed(AlertId(uniqueAlertId.value)))
            response <- AlertServiceClient
                          .deleteAlert(DeleteAlertRequest(Some(alertId.value)))
                          .catchSome{ case Status.NOT_FOUND =>
                            Log.warn("DeleteNotFound",
                              "alertId" := alertId,
                              "uniqueAlertId" := uniqueAlertId
                            ).as(DeleteAlertResponse())
                          }
                          .mapError(GrpcFailure.apply)
            _ <- Log.trace(
                   "DeleteApiResponse",
                   "name"          := name,
                   "id"            := alertId,
                   "uniqueAlertId" := uniqueAlertId,
                   "response"      := response.toString
                 )
          } yield StatusUpdate.DeleteRuleGroupMapping(name)).catchAll {
            (failure: CoralogixOperatorFailure) =>
              Log
                .error(
                  CoralogixOperatorFailure.toThrowable.toThrowable(failure),
                  "CannotDelete",
                  "name"          := name.value,
                  "uniqueAlertId" := uniqueAlertId.value
                )
                .as(
                  StatusUpdate
                    .RecordFailure(name, CoralogixOperatorFailure.toFailureString(failure))
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
        Log.warn("StatusIsMissing", "name" := alertSet.metadata.flatMap(_.name))
    }

  private def mappingToMap(
    mappings: Vector[AlertSet.Status.AlertIds]
  ): Map[AlertName, UniqueAlertId] =
    mappings.map { mapping =>
      mapping.name -> UniqueAlertId(mapping.id.value)
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
      .catchSome {
        case HttpFailure(_, message, StatusCode.Conflict) =>
          Log.warn("ReplaceStatusConflict", "responseMessage" := message, "status" := 409)
      }
      .mapError(KubernetesFailure.apply)
      .unit
  }.when(updates.nonEmpty)

  def forNamespace(alertLabels: List[String])(
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
          eventProcessor(alertLabels) @@ metered(metrics)
        )(Some(namespace), buffer)
        .provide(alertSets.asGeneric)
    }

  def forAllNamespaces(alertLabels: List[String])(
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
          eventProcessor(alertLabels) @@ metered(metrics)
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
        .namespaced(eventProcessor(List.empty))(Some(K8sNamespace("default")), 256)
        .provide(alertSets.asGeneric)
    }
}
