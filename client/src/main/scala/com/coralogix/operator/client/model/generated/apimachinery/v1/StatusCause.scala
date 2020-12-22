package com.coralogix.operator.client.model.generated.apimachinery.v1

import io.circe._
import io.circe.syntax._

// TODO: this should be generated from the K8s OpenAPI schema

case class StatusCause(
  field: Option[String] = None,
  message: Option[String] = None,
  reason: Option[String] = None
)
object StatusCause {
  implicit val encodeStatusCause: Encoder.AsObject[StatusCause] = {
    val readOnlyKeys = Set[String]()
    Encoder.AsObject
      .instance[StatusCause](a =>
        JsonObject.fromIterable(
          Vector(
            ("field", a.field.asJson),
            ("message", a.message.asJson),
            ("reason", a.reason.asJson)
          )
        )
      )
      .mapJsonObject(_.filterKeys(key => !(readOnlyKeys contains key)))
  }

  implicit val decodeStatusCause: Decoder[StatusCause] = new Decoder[StatusCause] {
    final def apply(c: HCursor): Decoder.Result[StatusCause] =
      for {
        v0 <- c.downField("field").as[Option[String]]
        v1 <- c.downField("message").as[Option[String]]
        v2 <- c.downField("reason").as[Option[String]]
      } yield StatusCause(v0, v1, v2)
  }
}
