package com.coralogix.operator.client.model.generated.apimachinery.v1

import io.circe._
import io.circe.syntax._

// TODO: this should be generated from the K8s OpenAPI schema

case class Status(
  apiVersion: Option[String] = None,
  code: Option[Int] = None,
  details: Option[StatusDetails] = None,
  kind: Option[String] = None,
  message: Option[String] = None,
  metadata: Option[ListMeta] = None,
  reason: Option[String] = None,
  status: Option[String] = None
)

object Status {
  implicit val encodeStatus: Encoder.AsObject[Status] = {
    val readOnlyKeys = Set[String]()
    Encoder.AsObject
      .instance[Status](a =>
        JsonObject.fromIterable(
          Vector(
            ("apiVersion", a.apiVersion.asJson),
            ("code", a.code.asJson),
            ("details", a.details.asJson),
            ("kind", a.kind.asJson),
            ("message", a.message.asJson),
            ("metadata", a.metadata.asJson),
            ("reason", a.reason.asJson),
            ("status", a.status.asJson)
          )
        )
      )
      .mapJsonObject(_.filterKeys(key => !(readOnlyKeys contains key)))
  }
  implicit val decodeStatus: Decoder[Status] = new Decoder[Status] {
    final def apply(c: HCursor): Decoder.Result[Status] =
      for {
        v0 <- c.downField("apiVersion").as[Option[String]]
        v1 <- c.downField("code").as[Option[Int]]
        v2 <- c.downField("details").as[Option[StatusDetails]]
        v3 <- c.downField("kind").as[Option[String]]
        v4 <- c.downField("message").as[Option[String]]
        v5 <- c.downField("metadata").as[Option[ListMeta]]
        v6 <- c.downField("reason").as[Option[String]]
        v7 <- c.downField("status").as[Option[String]]
      } yield Status(v0, v1, v2, v3, v4, v5, v6, v7)
  }
}
