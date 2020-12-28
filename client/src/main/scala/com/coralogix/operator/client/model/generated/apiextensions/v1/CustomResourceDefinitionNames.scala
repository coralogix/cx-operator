/*
 * This file was generated by guardrail (https://github.com/twilio/guardrail).
 * Modifications will be overwritten; instead edit the OpenAPI/Swagger spec file.
 */
package com.coralogix.operator.client.model.generated.apiextensions.v1

import io.circe._
import io.circe.syntax._
case class CustomResourceDefinitionNames(
  categories: Option[Vector[String]] = None,
  kind: String,
  listKind: Option[String] = None,
  plural: String,
  shortNames: Option[Vector[String]] = None,
  singular: Option[String] = None
)
object CustomResourceDefinitionNames {
  implicit val encodeCustomResourceDefinitionNames
    : Encoder.AsObject[CustomResourceDefinitionNames] = {
    val readOnlyKeys = Set[String]()
    Encoder.AsObject
      .instance[CustomResourceDefinitionNames](a =>
        JsonObject.fromIterable(
          Vector(
            ("categories", a.categories.asJson),
            ("kind", a.kind.asJson),
            ("listKind", a.listKind.asJson),
            ("plural", a.plural.asJson),
            ("shortNames", a.shortNames.asJson),
            ("singular", a.singular.asJson)
          )
        )
      )
      .mapJsonObject(_.filterKeys(key => !(readOnlyKeys contains key)))
  }
  implicit val decodeCustomResourceDefinitionNames: Decoder[CustomResourceDefinitionNames] =
    new Decoder[CustomResourceDefinitionNames] {
      final def apply(c: HCursor): Decoder.Result[CustomResourceDefinitionNames] =
        for {
          v0 <- c.downField("categories").as[Option[Vector[String]]]
          v1 <- c.downField("kind").as[String]
          v2 <- c.downField("listKind").as[Option[String]]
          v3 <- c.downField("plural").as[String]
          v4 <- c.downField("shortNames").as[Option[Vector[String]]]
          v5 <- c.downField("singular").as[Option[String]]
        } yield CustomResourceDefinitionNames(v0, v1, v2, v3, v4, v5)
    }
}