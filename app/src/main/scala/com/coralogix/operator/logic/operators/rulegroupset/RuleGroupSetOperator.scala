package com.coralogix.operator.logic.operators.rulegroupset

import com.coralogix.operator.logic.aspects._
import com.coralogix.operator.logic.operators.rulegroupset.ModelTransformations.{
  toCreateRuleGroup,
  RuleGroupWithIndex
}
import com.coralogix.operator.logic.operators.rulegroupset.StatusUpdate.runStatusUpdates
import com.coralogix.operator.logic.{ CoralogixOperatorFailure, GrpcFailure, UndefinedGrpcField }
import com.coralogix.operator.monitoring.OperatorMetrics
import com.coralogix.rules.v1.ZioRuleGroupsService._
import com.coralogix.rules.v1.{ DeleteRuleGroupRequest, UpdateRuleGroupRequest }
import com.coralogix.zio.k8s.client.com.coralogix.definitions.rulegroupset.v1.RuleGroupSet
import com.coralogix.zio.k8s.client.com.coralogix.v1.rulegroupsets
import com.coralogix.zio.k8s.client.com.coralogix.v1.rulegroupsets.RuleGroupSets
import com.coralogix.zio.k8s.client.model._
import com.coralogix.zio.k8s.client.model.primitives.{ RuleGroupId, RuleGroupName }
import com.coralogix.zio.k8s.operator.Operator._
import com.coralogix.zio.k8s.operator.OperatorLogging.logFailure
import com.coralogix.zio.k8s.operator._
import com.coralogix.zio.k8s.operator.aspects._
import zio.clock.Clock
import zio.logging.{ log, Logging }
import zio.{ Cause, Has, ZIO }

object RuleGroupSetOperator {

  /** The central rulegroupset event processor logic */
  private def eventProcessor(): EventProcessor[
    Logging with RuleGroupSets with RuleGroupsServiceClient,
    CoralogixOperatorFailure,
    RuleGroupSet
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
          )
            for {
              name <- item.getName.mapError(KubernetesFailure.apply)
              updates <- createNewRuleGroups(
                           ctx,
                           name,
                           item.spec.ruleGroupsSequence.zipWithIndex
                             .map { case (ruleGroup, idx) =>
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
            log.debug(
              s"Rule group set '${item.metadata.flatMap(_.name).getOrElse("")}' with generation ${item.generation} is already added"
            )
        case Modified(item) =>
          withExpectedStatus(item) { status =>
            if (status.lastUploadedGeneration.getOrElse(0L) < item.generation) {
              val mappings = status.groupIds.getOrElse(Vector.empty)
              val byName =
                item.spec.ruleGroupsSequence.zipWithIndex.map { case (ruleGroup, idx) =>
                  ruleGroup.name -> RuleGroupWithIndex(
                    ruleGroup,
                    idx + 1
                  ) // rules-api uses 1-based indexing
                }.toMap
              val alreadyAssigned = mappingToMap(mappings)
              val toAdd = byName.keySet.diff(alreadyAssigned.keySet)
              val toRemove = alreadyAssigned -- byName.keysIterator
              val toUpdate = alreadyAssigned
                .flatMap { case (name, status) =>
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
              log.debug(
                s"Skipping modification of rule group set '${item.metadata.flatMap(_.name).getOrElse("")}' with generation ${item.generation}"
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
        log.warn(
          s"Rule group set '${ruleGroupSet.metadata.flatMap(_.name).getOrElse("")}' has no status information"
        )
    }

  private def modifyExistingRuleGroups(
    ctx: OperatorContext,
    setName: String,
    mappings: Map[RuleGroupName, (RuleGroupId, RuleGroupWithIndex)],
    startOrder: Option[Int]
  ): ZIO[RuleGroupsServiceClient with Logging, Nothing, Vector[StatusUpdate]] =
    ZIO
      .foreachPar(mappings.toVector) { case (ruleGroupName, (id, data)) =>
        (for {
          _ <- log.info(s"Modifying rule group '${ruleGroupName.value}' (${id.value})")
          response <- RuleGroupsServiceClient
                        .updateRuleGroup(
                          UpdateRuleGroupRequest(
                            groupId = Some(id.value),
                            ruleGroup = Some(toCreateRuleGroup(data, startOrder, ctx, setName))
                          )
                        )
                        .mapError(GrpcFailure.apply)
          _ <-
            log.trace(
              s"Rules API response for modifying rule group '${ruleGroupName.value}' (${id.value}): $response"
            )
          groupId <- ZIO.fromEither(
                       response.ruleGroup
                         .flatMap(_.id)
                         .map(RuleGroupId.apply)
                         .toRight(UndefinedGrpcField("CreateRuleGroupResponse.ruleGroup.id"))
                     )
        } yield StatusUpdate.AddRuleGroupMapping(ruleGroupName, groupId)).catchAll {
          (failure: CoralogixOperatorFailure) =>
            logFailure(
              s"Failed to modify rule group '${ruleGroupName.value}'",
              Cause.fail(failure)
            ).as(
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
          _ <- log.info(s"Creating rule group '${item.ruleGroup.name.value}'")
          groupResponse <- RuleGroupsServiceClient
                             .createRuleGroup(toCreateRuleGroup(item, startOrder, ctx, setName))
                             .mapError(GrpcFailure.apply)
          _ <-
            log.trace(
              s"Rules API response for creating rules group '${item.ruleGroup.name.value}': $groupResponse"
            )
          groupId <- ZIO.fromEither(
                       groupResponse.ruleGroup
                         .flatMap(_.id)
                         .map(RuleGroupId.apply)
                         .toRight(UndefinedGrpcField("CreateRuleGroupResponse.ruleGroup.id"))
                     )
        } yield StatusUpdate.AddRuleGroupMapping(item.ruleGroup.name, groupId)).catchAll {
          (failure: CoralogixOperatorFailure) =>
            logFailure(
              s"Failed to create rule group '${item.ruleGroup.name.value}'",
              Cause.fail(failure)
            ).as(
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
      .foreachPar(mappings.toVector) { case (name, id) =>
        (for {
          _ <- log.info(s"Deleting rule group '${name.value}' (${id.value})'")
          response <- RuleGroupsServiceClient
                        .deleteRuleGroup(DeleteRuleGroupRequest(id.value))
                        .mapError(GrpcFailure.apply)
          _ <-
            log.trace(
              s"Rules API response for deleting rule group '${name.value}' (${id.value}): $response"
            )
        } yield StatusUpdate.DeleteRuleGroupMapping(name)).catchAll {
          (failure: CoralogixOperatorFailure) =>
            logFailure(s"Failed to delete rule group '${name.value}'", Cause.fail(failure)).as(
              StatusUpdate.RecordFailure(name, CoralogixOperatorFailure.toFailureString(failure))
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
          eventProcessor() @@ logEvents @@ metered(metrics)
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
          eventProcessor() @@ logEvents @@ metered(metrics)
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
