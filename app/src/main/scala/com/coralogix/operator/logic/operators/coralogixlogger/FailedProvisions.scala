package com.coralogix.operator.logic.operators.coralogixlogger

import com.coralogix.operator.logic.CoralogixOperatorFailure
import com.coralogix.zio.k8s.client.com.coralogix.loggers.definitions.coralogixlogger.v1.Coralogixlogger
import com.coralogix.zio.k8s.operator.{ KubernetesFailure, OperatorFailure }
import zio.{ IO, UIO, ZIO }
import zio.stm.TMap

class FailedProvisions(failedProvisions: TMap[String, Option[String]]) {
  def recordFailure(of: Coralogixlogger): IO[OperatorFailure[CoralogixOperatorFailure], Unit] =
    for {
      name <- of.getName.mapError(KubernetesFailure)
      resourceVersion = of.metadata.flatMap(_.resourceVersion)
      _ <- failedProvisions.put(name, resourceVersion).commit
    } yield ()

  def isRecordedFailure(
    resource: Coralogixlogger
  ): IO[OperatorFailure[CoralogixOperatorFailure], Boolean] =
    for {
      name <- resource.getName.mapError(KubernetesFailure)
      found <- (for {
                   entry <- failedProvisions.get(name)
                   result = entry match {
                              case Some(failedResourceVersion) =>
                                resource.metadata.flatMap(
                                  _.resourceVersion
                                ) == failedResourceVersion
                              case None =>
                                false
                            }
                 } yield result).commit
    } yield found
}

object FailedProvisions {
  def make: UIO[FailedProvisions] =
    TMap.make[String, Option[String]]().commit.map(new FailedProvisions(_))
}