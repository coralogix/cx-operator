package com.coralogix.operator.client.model

import io.circe._
import io.circe.syntax._

case class ManagedFieldsEntry(
  apiVersion: Option[String] = None,
  fieldsType: Option[String] = None,
  fieldsV1: Option[io.circe.Json] = None,
  manager: Option[String] = None,
  operation: Option[String] = None,
  time: Option[java.time.OffsetDateTime] = None
)

object ManagedFieldsEntry {
  implicit val managedFieldsEntryEncoder: Encoder.AsObject[ManagedFieldsEntry] = {
    val readOnlyKeys = Set[String]()
    Encoder.AsObject
      .instance[ManagedFieldsEntry](a =>
        JsonObject.fromIterable(
          Vector(
            ("apiVersion", a.apiVersion.asJson),
            ("fieldsType", a.fieldsType.asJson),
            ("fieldsV1", a.fieldsV1.asJson),
            ("manager", a.manager.asJson),
            ("operation", a.operation.asJson),
            ("time", a.time.asJson)
          )
        )
      )
      .mapJsonObject(_.filterKeys(key => !(readOnlyKeys contains key)))
  }
  implicit val managedFieldsEntryDecoder: Decoder[ManagedFieldsEntry] =
    new Decoder[ManagedFieldsEntry] {
      final def apply(c: HCursor): Decoder.Result[ManagedFieldsEntry] =
        for {
          v0 <- c.downField("apiVersion").as[Option[String]]
          v1 <- c.downField("fieldsType").as[Option[String]]
          v2 <- c.downField("fieldsV1").as[Option[io.circe.Json]]
          v3 <- c.downField("manager").as[Option[String]]
          v4 <- c.downField("operation").as[Option[String]]
          v5 <- c.downField("time").as[Option[java.time.OffsetDateTime]]
        } yield ManagedFieldsEntry(v0, v1, v2, v3, v4, v5)
    }
}
