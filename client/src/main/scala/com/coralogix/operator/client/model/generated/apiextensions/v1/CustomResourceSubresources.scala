/*
 * This file was generated by guardrail (https://github.com/twilio/guardrail).
 * Modifications will be overwritten; instead edit the OpenAPI/Swagger spec file.
 */
package com.coralogix.operator.client.model.generated.apiextensions.v1

import io.circe._
import io.circe.syntax._
case class CustomResourceSubresources(
  scale: Option[CustomResourceSubresourceScale] = None,
  status: Option[io.circe.Json] = None
)
object CustomResourceSubresources {
  implicit val encodeCustomResourceSubresources: Encoder.AsObject[CustomResourceSubresources] = {
    val readOnlyKeys = Set[String]()
    Encoder.AsObject
      .instance[CustomResourceSubresources](a =>
        JsonObject.fromIterable(Vector(("scale", a.scale.asJson), ("status", a.status.asJson)))
      )
      .mapJsonObject(_.filterKeys(key => !(readOnlyKeys contains key)))
  }
  implicit val decodeCustomResourceSubresources: Decoder[CustomResourceSubresources] =
    new Decoder[CustomResourceSubresources] {
      final def apply(c: HCursor): Decoder.Result[CustomResourceSubresources] =
        for {
          v0 <- c.downField("scale").as[Option[CustomResourceSubresourceScale]]
          v1 <- c.downField("status").as[Option[io.circe.Json]]
        } yield CustomResourceSubresources(v0, v1)
    }
}
