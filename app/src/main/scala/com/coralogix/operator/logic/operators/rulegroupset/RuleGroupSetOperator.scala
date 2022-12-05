package com.coralogix.operator.logic.operators.rulegroupset

import com.coralogix.operator.logging.Log
import com.coralogix.operator.logging.LogSyntax.FieldBuilder
import com.coralogix.operator.logic.aspects._
import com.coralogix.operator.logic.operators.rulegroupset.ModelTransformations.{
  toCreateRuleGroup,
  RuleGroupWithIndex
}
import com.coralogix.operator.logic.operators.rulegroupset.StatusUpdate.runStatusUpdates
import com.coralogix.operator.logic.{ CoralogixOperatorFailure, GrpcFailure, UndefinedGrpcField }
import com.coralogix.operator.monitoring.OperatorMetrics
import com.coralogix.rules.v1.rule_groups_service.ZioRuleGroupsService._
import com.coralogix.rules.v1.rule_groups_service.{ DeleteRuleGroupRequest, UpdateRuleGroupRequest }
import com.coralogix.zio.k8s.client.HttpFailure
import com.coralogix.zio.k8s.client.com.coralogix.definitions.rulegroupset.v1.RuleGroupSet
import com.coralogix.zio.k8s.client.com.coralogix.v1.rulegroupsets
import com.coralogix.zio.k8s.client.com.coralogix.v1.rulegroupsets.RuleGroupSets
import com.coralogix.zio.k8s.client.model._
import com.coralogix.zio.k8s.client.model.primitives.{ RuleGroupId, RuleGroupName }
import com.coralogix.zio.k8s.operator.Operator._
import com.coralogix.zio.k8s.operator._
import sttp.model.StatusCode
import zio.ZIO
import zio.clock.Clock
import zio.logging.Logging

object RuleGroupSetOperator {

  /** The central rulegroupset event processor logic */
  private def eventProcessor(): EventProcessor[
    Logging with RuleGroupSets with RuleGroupsServiceClient,
    CoralogixOperatorFailure,
    RuleGroupSet
  ] =
    (ctx, event) =>
      event match {
        case Reseted() =>
          ZIO.unit
        case Added(item) =>
          if (
            item.generation == 0L || // new item
            !item.status
              .flatMap(_.lastUploadedGeneration)
              .contains(item.generation) // already synchronized
          )
            for {
              name <- item.getName.mapError(KubernetesFailure.apply)
              updates <- createNewRuleGroups(
                           ctx,
                           name,
                           item.spec.ruleGroupsSequence.zipWithIndex
                             .map {
                               case (ruleGroup, idx) =>
                                 RuleGroupWithIndex(
                                   ruleGroup,
                                   idx + 1
                                 ) // the rules-api uses 1-based indexing
                             },
                           item.spec.startOrder.toOption
                         )
              _ <- applyStatusUpdates(
                     ctx,
                     item,
                     StatusUpdate.ClearFailures +:
                       StatusUpdate.UpdateLastUploadedGeneration(item.generation) +:
                       updates
                   )
            } yield ()
          else
            Log.debug(
              "AlreadyAdded",
              "name"       := item.metadata.flatMap(_.name),
              "generation" := item.generation
            )
        case Modified(item) if item.spec.ruleGroupsSequence.isEmpty =>
          Log.warn(
            "CustomObjectModifiedWithoutBody",
            "name"        := item.metadata.flatMap(_.name),
            "generation"  := item.generation,
            "description" := "Body of custom object is empty. Evidence of produced Modified event with empty body instead of Deleted one."
          )
        case Modified(item) =>
          withExpectedStatus(item) { status =>
            if (status.lastUploadedGeneration.getOrElse(0L) < item.generation) {
              val mappings = status.groupIds.getOrElse(Vector.empty)
              val byName =
                item.spec.ruleGroupsSequence.zipWithIndex.map {
                  case (ruleGroup, idx) =>
                    ruleGroup.name -> RuleGroupWithIndex(
                      ruleGroup,
                      idx + 1
                    ) // rules-api uses 1-based indexing
                }.toMap
              val alreadyAssigned = mappingToMap(mappings)
              val toAdd = byName.keySet.diff(alreadyAssigned.keySet)
              val toRemove = alreadyAssigned -- byName.keysIterator
              val toUpdate = alreadyAssigned
                .flatMap {
                  case (name, status) =>
                    byName.get(name).map(data => name -> (status, data))
                }

              for {
                name <- item.getName.mapError(KubernetesFailure.apply)
                up0  <- modifyExistingRuleGroups(ctx, name, toUpdate, item.spec.startOrder.toOption)
                up1 <- createNewRuleGroups(
                         ctx,
                         name,
                         toAdd.map(byName.apply).toVector,
                         item.spec.startOrder.toOption
                       )
                up2 <- deleteRuleGroups(toRemove)
                _ <- applyStatusUpdates(
                       ctx,
                       item,
                       StatusUpdate.ClearFailures +:
                         StatusUpdate.UpdateLastUploadedGeneration(item.generation) +:
                         (up0 ++ up1 ++ up2)
                     )
              } yield ()
            } else
              Log.debug(
                "SkippingModification",
                "name"       := item.metadata.flatMap(_.name),
                "generation" := item.generation
              )
          }
        case Deleted(item) =>
          withExpectedStatus(item) { status =>
            val mappings = status.groupIds.getOrElse(Vector.empty)
            deleteRuleGroups(mappingToMap(mappings)).unit
          }
      }

  private def withExpectedStatus[R <: Logging, E](
    ruleGroupSet: RuleGroupSet
  )(f: RuleGroupSet.Status => ZIO[R, E, Unit]): ZIO[R, E, Unit] =
    ruleGroupSet.status match {
      case Optional.Present(status) =>
        f(status)
      case Optional.Absent =>
        Log.warn("StatusIsMissing", "name" := ruleGroupSet.metadata.flatMap(_.name))
    }

  private def modifyExistingRuleGroups(
    ctx: OperatorContext,
    setName: String,
    mappings: Map[RuleGroupName, (RuleGroupId, RuleGroupWithIndex)],
    startOrder: Option[Int]
  ): ZIO[RuleGroupsServiceClient with Logging, Nothing, Vector[StatusUpdate]] =
    ZIO
      .foreachPar(mappings.toVector) {
        case (ruleGroupName, (id, data)) =>
          (for {
            _ <- Log.info("Modifying", "name" := ruleGroupName.value, "id" := id.value)
            response <- RuleGroupsServiceClient
                          .updateRuleGroup(
                            UpdateRuleGroupRequest(
                              groupId = Some(id.value),
                              ruleGroup = Some(toCreateRuleGroup(data, startOrder, ctx, setName))
                            )
                          )
                          .mapError(GrpcFailure.apply)
            _ <- Log.trace(
                   "ModifyApiResponse",
                   "name"     := ruleGroupName.value,
                   "id"       := id,
                   "response" := response.toString
                 )
            groupId <- ZIO.fromEither(
                         response.ruleGroup
                           .flatMap(_.id)
                           .map(RuleGroupId.apply)
                           .toRight(UndefinedGrpcField("CreateRuleGroupResponse.ruleGroup.id"))
                       )
          } yield StatusUpdate.AddRuleGroupMapping(ruleGroupName, groupId)).catchAll {
            (failure: CoralogixOperatorFailure) =>
              Log
                .error(
                  CoralogixOperatorFailure.toThrowable.toThrowable(failure),
                  "CannotModify",
                  "name" := ruleGroupName.value
                )
                .as(
                  StatusUpdate
                    .RecordFailure(ruleGroupName, CoralogixOperatorFailure.toFailureString(failure))
                )
          }
      }

  private def createNewRuleGroups(
    ctx: OperatorContext,
    setName: String,
    ruleGroups: Vector[RuleGroupWithIndex],
    startOrder: Option[Int]
  ): ZIO[RuleGroupsServiceClient with Logging, OperatorFailure[CoralogixOperatorFailure], Vector[
    StatusUpdate
  ]] =
    ZIO
      .foreachPar(ruleGroups) { item =>
        (for {
          _ <- Log.info("Create", "name" := item.ruleGroup.name.value)
          groupResponse <- RuleGroupsServiceClient
                             .createRuleGroup(toCreateRuleGroup(item, startOrder, ctx, setName))
                             .mapError(GrpcFailure.apply)
          _ <- Log.trace(
                 "CreateApiResponse",
                 "name"     := item.ruleGroup.name.value,
                 "response" := groupResponse.toString
               )
          groupId <- ZIO.fromEither(
                       groupResponse.ruleGroup
                         .flatMap(_.id)
                         .map(RuleGroupId.apply)
                         .toRight(UndefinedGrpcField("CreateRuleGroupResponse.ruleGroup.id"))
                     )
        } yield StatusUpdate.AddRuleGroupMapping(item.ruleGroup.name, groupId)).catchAll {
          (failure: CoralogixOperatorFailure) =>
            Log
              .error(
                CoralogixOperatorFailure.toThrowable.toThrowable(failure),
                "CannotCreate",
                "name" := item.ruleGroup.name.value
              )
              .as(
                StatusUpdate.RecordFailure(
                  item.ruleGroup.name,
                  CoralogixOperatorFailure.toFailureString(failure)
                )
              )
        }
      }

  private def deleteRuleGroups(
    mappings: Map[RuleGroupName, RuleGroupId]
  ): ZIO[RuleGroupsServiceClient with Logging, OperatorFailure[CoralogixOperatorFailure], Vector[
    StatusUpdate
  ]] =
    ZIO
      .foreachPar(mappings.toVector) {
        case (name, id) =>
          (for {
            _ <- Log.info("Delete", "name" := name.value, "id" := id.value)
            response <- RuleGroupsServiceClient
                          .deleteRuleGroup(DeleteRuleGroupRequest(id.value))
                          .mapError(GrpcFailure.apply)
            _ <- Log.trace(
                   "DeleteApiResponse",
                   "name"     := name,
                   "id"       := id,
                   "response" := response.toString
                 )
          } yield StatusUpdate.DeleteRuleGroupMapping(name)).catchAll {
            (failure: CoralogixOperatorFailure) =>
              Log
                .error(
                  CoralogixOperatorFailure.toThrowable.toThrowable(failure),
                  "CannotDelete",
                  "name" := name.value,
                  "id"   := id.value
                )
                .as(
                  StatusUpdate
                    .RecordFailure(name, CoralogixOperatorFailure.toFailureString(failure))
                )
          }
      }

  private def applyStatusUpdates(
    ctx: OperatorContext,
    resource: RuleGroupSet,
    updates: Vector[StatusUpdate]
  ): ZIO[Logging with RuleGroupSets, KubernetesFailure, Unit] = {
    val initialStatus =
      resource.status.getOrElse(RuleGroupSet.Status(groupIds = Some(Vector.empty)))
    val updatedStatus = runStatusUpdates(initialStatus, updates)

    rulegroupsets
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

  private def mappingToMap(
    mappings: Vector[RuleGroupSet.Status.GroupIds]
  ): Map[RuleGroupName, RuleGroupId] =
    mappings.map { mapping =>
      mapping.name -> mapping.id
    }.toMap

  def forNamespace(
    namespace: K8sNamespace,
    buffer: Int,
    metrics: OperatorMetrics
  ): ZIO[RuleGroupSets, Nothing, Operator[
    Clock with Logging with RuleGroupSets with RuleGroupsServiceClient,
    CoralogixOperatorFailure,
    RuleGroupSet
  ]] =
    ZIO.service[RuleGroupSets.Service].flatMap { ruleGroupSets =>
      Operator
        .namespaced(
          eventProcessor() @@ metered(metrics)
        )(Some(namespace), buffer)
        .provide(ruleGroupSets.asGeneric)
    }

  def forAllNamespaces(
    buffer: Int,
    metrics: OperatorMetrics
  ): ZIO[RuleGroupSets, Nothing, Operator[
    Clock with Logging with RuleGroupSets with RuleGroupsServiceClient,
    CoralogixOperatorFailure,
    RuleGroupSet
  ]] =
    ZIO.service[RuleGroupSets.Service].flatMap { ruleGroupSets =>
      Operator
        .namespaced(
          eventProcessor() @@ metered(metrics)
        )(None, buffer)
        .provide(ruleGroupSets.asGeneric)
    }

  def forTest(): ZIO[RuleGroupSets, Nothing, Operator[
    Logging with RuleGroupSets with RuleGroupsServiceClient,
    CoralogixOperatorFailure,
    RuleGroupSet
  ]] =
    ZIO.service[RuleGroupSets.Service].flatMap { ruleGroupSets =>
      Operator
        .namespaced(eventProcessor())(Some(K8sNamespace("default")), 256)
        .provide(ruleGroupSets.asGeneric)
    }
}
