package com.coralogix.operator.client.model.generated.apimachinery.v1

import io.circe._
import io.circe.syntax._

// TODO: this should be generated from the K8s OpenAPI schema

case class WatchEvent(`object`: io.circe.Json, `type`: String)
object WatchEvent {
  implicit val encodeWatchEvent: Encoder.AsObject[WatchEvent] = {
    val readOnlyKeys = Set[String]()
    Encoder.AsObject
      .instance[WatchEvent](a =>
        JsonObject.fromIterable(Vector(("object", a.`object`.asJson), ("type", a.`type`.asJson)))
      )
      .mapJsonObject(_.filterKeys(key => !(readOnlyKeys contains key)))
  }
  implicit val decodeWatchEvent: Decoder[WatchEvent] = new Decoder[WatchEvent] {
    final def apply(c: HCursor): Decoder.Result[WatchEvent] =
      for {
        v0 <- c.downField("object").as[io.circe.Json]
        v1 <- c.downField("type").as[String]
      } yield WatchEvent(v0, v1)
  }
}
