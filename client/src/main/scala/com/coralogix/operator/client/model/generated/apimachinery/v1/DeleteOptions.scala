package com.coralogix.operator.client.model.generated.apimachinery.v1

import io.circe._
import io.circe.syntax._

// TODO: this should be generated from the K8s OpenAPI schema

case class DeleteOptions(
  apiVersion: Option[String] = None,
  dryRun: Option[Vector[String]] = None,
  gracePeriodSeconds: Option[Long] = None,
  kind: Option[String] = None,
  orphanDependents: Option[Boolean] = None,
  preconditions: Option[Preconditions] = None,
  propagationPolicy: Option[String] = None
)

object DeleteOptions {
  implicit val encodeDeleteOptions: Encoder.AsObject[DeleteOptions] = {
    val readOnlyKeys = Set[String]()
    Encoder.AsObject
      .instance[DeleteOptions](a =>
        JsonObject.fromIterable(
          Vector(
            ("apiVersion", a.apiVersion.asJson),
            ("dryRun", a.dryRun.asJson),
            ("gracePeriodSeconds", a.gracePeriodSeconds.asJson),
            ("kind", a.kind.asJson),
            ("orphanDependents", a.orphanDependents.asJson),
            ("preconditions", a.preconditions.asJson),
            ("propagationPolicy", a.propagationPolicy.asJson)
          )
        )
      )
      .mapJsonObject(_.filterKeys(key => !(readOnlyKeys contains key)))
  }

  implicit val decodeDeleteOptions: Decoder[DeleteOptions] = new Decoder[DeleteOptions] {
    final def apply(c: HCursor): Decoder.Result[DeleteOptions] =
      for {
        v0 <- c.downField("apiVersion").as[Option[String]]
        v1 <- c.downField("dryRun").as[Option[Vector[String]]]
        v2 <- c.downField("gracePeriodSeconds").as[Option[Long]]
        v3 <- c.downField("kind").as[Option[String]]
        v4 <- c.downField("orphanDependents").as[Option[Boolean]]
        v5 <- c.downField("preconditions").as[Option[Preconditions]]
        v6 <- c.downField("propagationPolicy").as[Option[String]]
      } yield DeleteOptions(v0, v1, v2, v3, v4, v5, v6)
  }
}
