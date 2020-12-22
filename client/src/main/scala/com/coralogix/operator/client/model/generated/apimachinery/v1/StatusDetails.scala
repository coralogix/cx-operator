package com.coralogix.operator.client.model.generated.apimachinery.v1

import io.circe._
import io.circe.syntax._

// TODO: this should be generated from the K8s OpenAPI schema

case class StatusDetails(
  causes: Option[Vector[StatusCause]] = None,
  group: Option[String] = None,
  kind: Option[String] = None,
  name: Option[String] = None,
  retryAfterSeconds: Option[Int] = None,
  uid: Option[String] = None
)

object StatusDetails {
  implicit val encodeStatusDetails: Encoder.AsObject[StatusDetails] = {
    val readOnlyKeys = Set[String]()
    Encoder.AsObject
      .instance[StatusDetails](a =>
        JsonObject.fromIterable(
          Vector(
            ("causes", a.causes.asJson),
            ("group", a.group.asJson),
            ("kind", a.kind.asJson),
            ("name", a.name.asJson),
            ("retryAfterSeconds", a.retryAfterSeconds.asJson),
            ("uid", a.uid.asJson)
          )
        )
      )
      .mapJsonObject(_.filterKeys(key => !(readOnlyKeys contains key)))
  }
  implicit val decodeStatusDetails: Decoder[StatusDetails] = new Decoder[StatusDetails] {
    final def apply(c: HCursor): Decoder.Result[StatusDetails] =
      for {
        v0 <- c.downField("causes").as[Option[Vector[StatusCause]]]
        v1 <- c.downField("group").as[Option[String]]
        v2 <- c.downField("kind").as[Option[String]]
        v3 <- c.downField("name").as[Option[String]]
        v4 <- c.downField("retryAfterSeconds").as[Option[Int]]
        v5 <- c.downField("uid").as[Option[String]]
      } yield StatusDetails(v0, v1, v2, v3, v4, v5)
  }
}
