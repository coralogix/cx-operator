package com.coralogix.operator.logic.operators.apikeys

import com.coralogix.users.v2beta1.ApiKeyType
import com.coralogix.users.v2beta1.GetApiKeyRequest.Id.{ CompanyId, UserId }
import com.coralogix.zio.k8s.client.com.coralogix.definitions.apikeyset.v1.ApiKeySet
import com.coralogix.zio.k8s.quicklens._
import com.softwaremill.quicklens._

sealed trait StatusUpdate
object StatusUpdate {
  final case class CreateUserApiKey(tpe: ApiKeyType, id: UserId, tokenValue: Option[String])
      extends StatusUpdate
  final case class CreateCompanyApiKey(tpe: ApiKeyType, id: CompanyId, tokenValue: Option[String])
      extends StatusUpdate
  // TODO how should we cope with no delete? Add it? Make it no-op?
  final case class DeleteUserApiKey(tpe: ApiKeyType) extends StatusUpdate
  final case class UpdateLastUploadedGeneration(generation: Long) extends StatusUpdate
  final case class RecordFailure(tokenValue: String, failure: String) extends StatusUpdate
  final case object ClearFailures extends StatusUpdate

  // TODO it all :) -- missing failures part from CRD yaml
  private def runStatusUpdate(
    status: ApiKeySet.Status,
    update: StatusUpdate
  ): ApiKeySet.Status =
    update match {
      case StatusUpdate.CreateUserApiKey(tpe, id, token) =>
        modify(runStatusUpdate(status, DeleteUserApiKey(name)))(
          _.alertIds.atOrElse(Vector.empty)
        )(_ :+ ApiKeySet.Status.AlertIds(name, id))
      case StatusUpdate.DeleteUserApiKey(name) =>
        modify(status)(_.alertIds.each)(_.filterNot(_.name == name))
      case StatusUpdate.UpdateLastUploadedGeneration(generation) =>
        modify(status)(_.lastUploadedGeneration).setTo(Some(generation))
      case StatusUpdate.RecordFailure(name, failure) =>
        modify(status)(_.failures.atOrElse(Vector.empty))(
          _ :+ ApiKeySet.Status.Failures(name, failure)
        )
      case StatusUpdate.ClearFailures =>
        modify(status)(_.failures).setTo(None)
    }

  def runStatusUpdates(
    status: ApiKeySet.Status,
    updates: Vector[StatusUpdate]
  ): ApiKeySet.Status =
    updates.foldLeft(status)(runStatusUpdate)

}
