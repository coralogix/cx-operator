package com.coralogix.operator.client.model.generated.apimachinery.v1

import io.circe._
import io.circe.syntax._

// TODO: this should be generated from the K8s OpenAPI schema

case class OwnerReference(
  apiVersion: String,
  blockOwnerDeletion: Option[Boolean] = None,
  controller: Option[Boolean] = None,
  kind: String,
  name: String,
  uid: String
)

object OwnerReference {
  implicit val ownerReferenceEncoder: Encoder.AsObject[OwnerReference] = {
    val readOnlyKeys = Set[String]()
    Encoder.AsObject
      .instance[OwnerReference](a =>
        JsonObject.fromIterable(
          Vector(
            ("apiVersion", a.apiVersion.asJson),
            ("blockOwnerDeletion", a.blockOwnerDeletion.asJson),
            ("controller", a.controller.asJson),
            ("kind", a.kind.asJson),
            ("name", a.name.asJson),
            ("uid", a.uid.asJson)
          )
        )
      )
      .mapJsonObject(_.filterKeys(key => !(readOnlyKeys contains key)))
  }
  implicit val ownerReferenceDecoder: Decoder[OwnerReference] = new Decoder[OwnerReference] {
    final def apply(c: HCursor): Decoder.Result[OwnerReference] =
      for {
        v0 <- c.downField("apiVersion").as[String]
        v1 <- c.downField("blockOwnerDeletion").as[Option[Boolean]]
        v2 <- c.downField("controller").as[Option[Boolean]]
        v3 <- c.downField("kind").as[String]
        v4 <- c.downField("name").as[String]
        v5 <- c.downField("uid").as[String]
      } yield OwnerReference(v0, v1, v2, v3, v4, v5)
  }
}
