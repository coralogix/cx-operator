package com.coralogix.operator.grpc

import grpc.health.v1.Health.HealthCheckResponse.ServingStatus
import grpc.health.v1.Health.{ HealthCheckRequest, HealthCheckResponse, ZioHealth }
import io.grpc.Status
import zio.{ Has, ZIO, ZLayer }

class Health extends ZioHealth.Health {
  override def check(request: HealthCheckRequest): ZIO[Any, Status, HealthCheckResponse] = {
    val healthCheck = request.service match {
      case "Health" | "" =>
        // Liveness
        ZIO.succeed(ServingStatus.SERVING)
      case "CoralogixOperator" =>
        // Readiness
        ZIO.succeed(ServingStatus.SERVING)
      case _ =>
        ZIO.succeed(ServingStatus.UNKNOWN)
    }
    healthCheck.map(HealthCheckResponse(_))
  }
}

object Health {
  val make: ZIO[Any, Nothing, Health] =
    ZIO.succeed(new Health())

  val live: ZLayer[Any, Nothing, Has[ZioHealth.Health]] =
    make.toLayer
}
