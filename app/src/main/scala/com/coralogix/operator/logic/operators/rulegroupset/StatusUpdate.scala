package com.coralogix.operator.logic.operators.rulegroupset

import com.coralogix.zio.k8s.client.com.coralogix.definitions.rulegroupset.v1.RuleGroupSet
import com.coralogix.zio.k8s.client.model.primitives.{ RuleGroupId, RuleGroupName }
import com.softwaremill.quicklens._

/** Update actions for the Rulegroupset.Status subresource */
sealed trait StatusUpdate
object StatusUpdate {
  final case class AddRuleGroupMapping(name: RuleGroupName, id: RuleGroupId) extends StatusUpdate
  final case class DeleteRuleGroupMapping(name: RuleGroupName) extends StatusUpdate
  final case class UpdateLastUploadedGeneration(generation: Long) extends StatusUpdate

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
    }

  def runStatusUpdates(
    status: RuleGroupSet.Status,
    updates: Vector[StatusUpdate]
  ): RuleGroupSet.Status =
    updates.foldLeft(status)(runStatusUpdate)

}
