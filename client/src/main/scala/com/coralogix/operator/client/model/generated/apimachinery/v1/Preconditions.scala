package com.coralogix.operator.client.model.generated.apimachinery.v1

import io.circe._
import io.circe.syntax._

// TODO: this should be generated from the K8s OpenAPI schema

case class Preconditions(resourceVersion: Option[String] = None, uid: Option[String] = None)
object Preconditions {
  implicit val encodePreconditions: Encoder.AsObject[Preconditions] = {
    val readOnlyKeys = Set[String]()
    Encoder.AsObject
      .instance[Preconditions](a =>
        JsonObject.fromIterable(
          Vector(("resourceVersion", a.resourceVersion.asJson), ("uid", a.uid.asJson))
        )
      )
      .mapJsonObject(_.filterKeys(key => !(readOnlyKeys contains key)))
  }
  implicit val decodePreconditions: Decoder[Preconditions] = new Decoder[Preconditions] {
    final def apply(c: HCursor): Decoder.Result[Preconditions] =
      for {
        v0 <- c.downField("resourceVersion").as[Option[String]]
        v1 <- c.downField("uid").as[Option[String]]
      } yield Preconditions(v0, v1)
  }
}
