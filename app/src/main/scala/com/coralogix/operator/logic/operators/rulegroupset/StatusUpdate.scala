package com.coralogix.operator.logic.operators.rulegroupset

import com.coralogix.zio.k8s.client.com.coralogix.definitions.rulegroupset.v1.RuleGroupSet
import com.coralogix.zio.k8s.client.model.primitives.{ RuleGroupId, RuleGroupName }
import com.coralogix.zio.k8s.quicklens._
import com.softwaremill.quicklens._

/** Update actions for the Rulegroupset.Status subresource */
sealed trait StatusUpdate
object StatusUpdate {
  final case class AddRuleGroupMapping(name: RuleGroupName, id: RuleGroupId) extends StatusUpdate
  final case class DeleteRuleGroupMapping(name: RuleGroupName) extends StatusUpdate
  final case class UpdateLastUploadedGeneration(generation: Long) extends StatusUpdate
  final case class RecordFailure(name: RuleGroupName, failure: String) extends StatusUpdate
  final case object ClearFailures extends StatusUpdate

  private def runStatusUpdate(
    status: RuleGroupSet.Status,
    update: StatusUpdate
  ): RuleGroupSet.Status =
    update match {
      case StatusUpdate.AddRuleGroupMapping(name, id) =>
        modify(runStatusUpdate(status, DeleteRuleGroupMapping(name)))(
          _.groupIds.atOrElse(Vector.empty)
        )(_ :+ RuleGroupSet.Status.GroupIds(name, id))
      case StatusUpdate.DeleteRuleGroupMapping(name) =>
        modify(status)(_.groupIds.each)(_.filterNot(_.name == name))
      case StatusUpdate.UpdateLastUploadedGeneration(generation) =>
        modify(status)(_.lastUploadedGeneration).setTo(Some(generation))
      case StatusUpdate.RecordFailure(name, failure) =>
        modify(status)(_.failures.atOrElse(Vector.empty))(
          _ :+ RuleGroupSet.Status.Failures(name, failure)
        )
      case StatusUpdate.ClearFailures =>
        modify(status)(_.failures).setTo(None)
    }

  def runStatusUpdates(
    status: RuleGroupSet.Status,
    updates: Vector[StatusUpdate]
  ): RuleGroupSet.Status =
    updates.foldLeft(status)(runStatusUpdate)

}
