package com.coralogix.operator.logic

import com.coralogix.zio.k8s.operator.OperatorLogging.ConvertableToThrowable

sealed trait CoralogixOperatorFailure
case class GrpcFailure(status: io.grpc.Status) extends CoralogixOperatorFailure
case class UndefinedGrpcField(name: String) extends CoralogixOperatorFailure
case class CustomResourceError(details: String) extends CoralogixOperatorFailure
case object ProvisioningFailed extends CoralogixOperatorFailure

object CoralogixOperatorFailure {
  implicit val toThrowable: ConvertableToThrowable[CoralogixOperatorFailure] = {
    case GrpcFailure(status) =>
      status.asException()
    case UndefinedGrpcField(fieldName) =>
      new RuntimeException(s"Undefined field in gRPC data: $fieldName")
    case CustomResourceError(details) =>
      new RuntimeException(s"Error in the custom resource: $details")
    case ProvisioningFailed =>
      new RuntimeException(s"Provisioning failed")
  }
}
