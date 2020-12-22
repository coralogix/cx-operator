package com.coralogix.operator.client.model.generated.apimachinery.v1

import com.coralogix.operator.client.model.ManagedFieldsEntry
import io.circe._
import io.circe.syntax._

// TODO: this should be generated from the K8s OpenAPI schema

case class ObjectMeta(
  annotations: Option[Map[String, String]] = None,
  clusterName: Option[String] = None,
  creationTimestamp: Option[java.time.OffsetDateTime] = None,
  deletionGracePeriodSeconds: Option[Long] = None,
  deletionTimestamp: Option[java.time.OffsetDateTime] = None,
  finalizers: Option[Vector[String]] = None,
  generateName: Option[String] = None,
  generation: Option[Long] = None,
  labels: Option[Map[String, String]] = None,
  managedFields: Option[Vector[ManagedFieldsEntry]] = None,
  name: Option[String] = None,
  namespace: Option[String] = None,
  ownerReferences: Option[Vector[OwnerReference]] = None,
  resourceVersion: Option[String] = None,
  selfLink: Option[String] = None,
  uid: Option[String] = None
)

object ObjectMeta {
  implicit val encodeObjectMeta: Encoder.AsObject[ObjectMeta] = {
    val readOnlyKeys = Set[String]()
    Encoder.AsObject
      .instance[ObjectMeta](a =>
        JsonObject.fromIterable(
          Vector(
            ("annotations", a.annotations.asJson),
            ("clusterName", a.clusterName.asJson),
            ("creationTimestamp", a.creationTimestamp.asJson),
            ("deletionGracePeriodSeconds", a.deletionGracePeriodSeconds.asJson),
            ("deletionTimestamp", a.deletionTimestamp.asJson),
            ("finalizers", a.finalizers.asJson),
            ("generateName", a.generateName.asJson),
            ("generation", a.generation.asJson),
            ("labels", a.labels.asJson),
            ("managedFields", a.managedFields.asJson),
            ("name", a.name.asJson),
            ("namespace", a.namespace.asJson),
            ("ownerReferences", a.ownerReferences.asJson),
            ("resourceVersion", a.resourceVersion.asJson),
            ("selfLink", a.selfLink.asJson),
            ("uid", a.uid.asJson)
          )
        )
      )
      .mapJsonObject(_.filterKeys(key => !(readOnlyKeys contains key)))
  }
  implicit val decodeObjectMeta: Decoder[ObjectMeta] = new Decoder[ObjectMeta] {
    final def apply(c: HCursor): Decoder.Result[ObjectMeta] =
      for {
        v0  <- c.downField("annotations").as[Option[Map[String, String]]]
        v1  <- c.downField("clusterName").as[Option[String]]
        v2  <- c.downField("creationTimestamp").as[Option[java.time.OffsetDateTime]]
        v3  <- c.downField("deletionGracePeriodSeconds").as[Option[Long]]
        v4  <- c.downField("deletionTimestamp").as[Option[java.time.OffsetDateTime]]
        v5  <- c.downField("finalizers").as[Option[Vector[String]]]
        v6  <- c.downField("generateName").as[Option[String]]
        v7  <- c.downField("generation").as[Option[Long]]
        v8  <- c.downField("labels").as[Option[Map[String, String]]]
        v9  <- c.downField("managedFields").as[Option[Vector[ManagedFieldsEntry]]]
        v10 <- c.downField("name").as[Option[String]]
        v11 <- c.downField("namespace").as[Option[String]]
        v12 <- c.downField("ownerReferences").as[Option[Vector[OwnerReference]]]
        v13 <- c.downField("resourceVersion").as[Option[String]]
        v14 <- c.downField("selfLink").as[Option[String]]
        v15 <- c.downField("uid").as[Option[String]]
      } yield ObjectMeta(v0, v1, v2, v3, v4, v5, v6, v7, v8, v9, v10, v11, v12, v13, v14, v15)
  }
}
