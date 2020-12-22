package com.coralogix.operator.logic

import com.coralogix.operator.client.K8sFailure.syntax._
import com.coralogix.operator.client.crd.CustomResourceDefinitions
import com.coralogix.operator.client.model.ResourceMetadata
import com.coralogix.operator.client.model.generated.apiextensions.v1.CustomResourceDefinition
import com.coralogix.operator.client.{ crd, K8sFailure }
import zio.ZIO
import zio.blocking.Blocking
import zio.logging.{ log, Logging }

object Registration {
  def registerIfMissing[T](
    metadata: ResourceMetadata[T],
    customResourceDefinition: ZIO[Blocking, Throwable, CustomResourceDefinition]
  ): ZIO[Logging with Blocking with CustomResourceDefinitions, Throwable, Unit] = {
    val name = s"${metadata.resourceType.resourceType}.${metadata.resourceType.group}"
    log.info(s"Checking that $name CRD is registered") *>
      ZIO.whenM(
        crd
          .get(name)
          .ifFound
          .bimap(registrationFailure, _.isEmpty)
      )(register(customResourceDefinition))
  }

  private def register(
    customResourceDefinition: ZIO[Logging with Blocking, Throwable, CustomResourceDefinition]
  ): ZIO[CustomResourceDefinitions with Logging with Blocking, Throwable, Unit] =
    for {
      definition <- customResourceDefinition
      _          <- crd.create(definition).mapError(registrationFailure)
      name       <- definition.getName.mapError(registrationFailure)
      _          <- log.info(s"Registered $name CRD")
    } yield ()

  private def registrationFailure(failure: K8sFailure): Throwable =
    new RuntimeException(
      s"CRD registration failed",
      OperatorFailure.toThrowable.toThrowable(KubernetesFailure(failure))
    )
}
