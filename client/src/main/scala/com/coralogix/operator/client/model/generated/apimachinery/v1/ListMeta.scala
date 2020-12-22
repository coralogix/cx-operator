package com.coralogix.operator.client.model.generated.apimachinery.v1

import io.circe._
import io.circe.syntax._

// TODO: this should be generated from the K8s OpenAPI schema

case class ListMeta(
  continue: Option[String] = None,
  remainingItemCount: Option[Long] = None,
  resourceVersion: Option[String] = None,
  selfLink: Option[String] = None
)
object ListMeta {
  implicit val encodeListMeta: Encoder.AsObject[ListMeta] = {
    val readOnlyKeys = Set[String]()
    Encoder.AsObject
      .instance[ListMeta](a =>
        JsonObject.fromIterable(
          Vector(
            ("continue", a.continue.asJson),
            ("remainingItemCount", a.remainingItemCount.asJson),
            ("resourceVersion", a.resourceVersion.asJson),
            ("selfLink", a.selfLink.asJson)
          )
        )
      )
      .mapJsonObject(_.filterKeys(key => !(readOnlyKeys contains key)))
  }
  implicit val decodeListMeta: Decoder[ListMeta] = new Decoder[ListMeta] {
    final def apply(c: HCursor): Decoder.Result[ListMeta] =
      for {
        v0 <- c.downField("continue").as[Option[String]]
        v1 <- c.downField("remainingItemCount").as[Option[Long]]
        v2 <- c.downField("resourceVersion").as[Option[String]]
        v3 <- c.downField("selfLink").as[Option[String]]
      } yield ListMeta(v0, v1, v2, v3)
  }
}
