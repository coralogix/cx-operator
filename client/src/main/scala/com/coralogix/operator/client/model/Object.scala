package com.coralogix.operator.client.model

import com.coralogix.operator.client.model.generated.apimachinery.v1.ObjectMeta
import com.coralogix.operator.client.{ K8sFailure, UndefinedField }
import zio.{ IO, ZIO }

/** Object of a custom resource */
trait Object[+Status] {
  def metadata: Option[ObjectMeta]
  def status: Option[Status]

  def getName: IO[K8sFailure, String] =
    ZIO.fromEither(metadata.flatMap(_.name).toRight(UndefinedField("metadata.name")))

  def generation: Long = metadata.flatMap(_.generation).getOrElse(0L)
}
