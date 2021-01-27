package com.coralogix.operator.logic.operators.alertset

import com.coralogix.zio.k8s.client.com.coralogix.definitions.alertset.v1.AlertSet
import com.coralogix.zio.k8s.client.model.primitives.{ AlertId, AlertName }
import com.softwaremill.quicklens._

sealed trait StatusUpdate
object StatusUpdate {
  final case class AddRuleGroupMapping(name: AlertName, id: AlertId) extends StatusUpdate
  final case class DeleteRuleGroupMapping(name: AlertName) extends StatusUpdate
  final case class UpdateLastUploadedGeneration(generation: Long) extends StatusUpdate

  private def runStatusUpdate(
    status: AlertSet.Status,
    update: StatusUpdate
  ): AlertSet.Status =
    update match {
      case StatusUpdate.AddRuleGroupMapping(name, id) =>
        modify(runStatusUpdate(status, DeleteRuleGroupMapping(name)))(
          _.alertIds.atOrElse(Vector.empty)
        )(_ :+ AlertSet.Status.AlertIds(name, id))
      case StatusUpdate.DeleteRuleGroupMapping(name) =>
        modify(status)(_.alertIds.each)(_.filterNot(_.name == name))
      case StatusUpdate.UpdateLastUploadedGeneration(generation) =>
        modify(status)(_.lastUploadedGeneration).setTo(Some(generation))
    }

  def runStatusUpdates(
    status: AlertSet.Status,
    updates: Vector[StatusUpdate]
  ): AlertSet.Status =
    updates.foldLeft(status)(runStatusUpdate)

}
