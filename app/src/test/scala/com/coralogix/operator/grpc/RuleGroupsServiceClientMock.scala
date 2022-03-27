package com.coralogix.operator.grpc

import com.coralogix.rules.grpc.external.v1.RuleGroupsService.{
  CreateRuleGroupRequest,
  CreateRuleGroupResponse,
  DeleteRuleGroupRequest,
  DeleteRuleGroupResponse,
  GetRuleGroupRequest,
  GetRuleGroupResponse,
  UpdateRuleGroupRequest,
  UpdateRuleGroupResponse
}
import com.coralogix.rules.grpc.external.v1.RuleGroupsService.ZioRuleGroupsService.RuleGroupsServiceClient
import io.grpc.{ CallOptions, Status }
import scalapb.zio_grpc.SafeMetadata
import zio.test.mock
import zio.{ Has, IO, ULayer, URLayer, ZIO, ZLayer }
import zio.test.mock.Mock
import zio.test.mock.Proxy

object RuleGroupsServiceClientMock extends Mock[RuleGroupsServiceClient] {
  object GetRuleGroup extends Effect[GetRuleGroupRequest, Status, GetRuleGroupResponse]
  object CreateRuleGroup extends Effect[CreateRuleGroupRequest, Status, CreateRuleGroupResponse]
  object UpdateRuleGroup extends Effect[UpdateRuleGroupRequest, Status, UpdateRuleGroupResponse]
  object DeleteRuleGroup extends Effect[DeleteRuleGroupRequest, Status, DeleteRuleGroupResponse]

  override val compose: URLayer[Has[Proxy], RuleGroupsServiceClient] =
    ZLayer.fromService { proxy =>
      new RuleGroupsServiceClient.Service {
        override def getRuleGroup(
          request: GetRuleGroupRequest
        ): ZIO[Any, Status, GetRuleGroupResponse] = proxy(GetRuleGroup, request)

        override def createRuleGroup(
          request: CreateRuleGroupRequest
        ): ZIO[Any, Status, CreateRuleGroupResponse] = proxy(CreateRuleGroup, request)

        override def updateRuleGroup(
          request: UpdateRuleGroupRequest
        ): ZIO[Any, Status, UpdateRuleGroupResponse] = proxy(UpdateRuleGroup, request)

        override def deleteRuleGroup(
          request: DeleteRuleGroupRequest
        ): ZIO[Any, Status, DeleteRuleGroupResponse] = proxy(DeleteRuleGroup, request)

        override def withMetadataM[C](
          headersEffect: ZIO[C, Status, SafeMetadata]
        ): RuleGroupsServiceClient.ZService[Any, C] =
          this.asInstanceOf[RuleGroupsServiceClient.ZService[Any, C]]

        override def withCallOptionsM(
          callOptions: IO[Status, CallOptions]
        ): RuleGroupsServiceClient.ZService[Any, Any] = this

        override def mapCallOptionsM(
          f: CallOptions => IO[Status, CallOptions]
        ): RuleGroupsServiceClient.ZService[Any, Any] = this
      }
    }

  val failing: ULayer[RuleGroupsServiceClient] =
    ZLayer.succeed(
      new RuleGroupsServiceClient.Service {
        override def getRuleGroup(
          request: GetRuleGroupRequest
        ): ZIO[Any, Status, GetRuleGroupResponse] = ???
        override def createRuleGroup(
          request: CreateRuleGroupRequest
        ): ZIO[Any, Status, CreateRuleGroupResponse] = ???
        override def updateRuleGroup(
          request: UpdateRuleGroupRequest
        ): ZIO[Any, Status, UpdateRuleGroupResponse] = ???
        override def deleteRuleGroup(
          request: DeleteRuleGroupRequest
        ): ZIO[Any, Status, DeleteRuleGroupResponse] = ???
        override def withMetadataM[C](
          headersEffect: ZIO[C, Status, SafeMetadata]
        ): RuleGroupsServiceClient.ZService[Any, C] = ???
        override def withCallOptionsM(
          callOptions: IO[Status, CallOptions]
        ): RuleGroupsServiceClient.ZService[Any, Any] = ???
        override def mapCallOptionsM(
          f: CallOptions => IO[Status, CallOptions]
        ): RuleGroupsServiceClient.ZService[Any, Any] = ???
      }
    )
}
