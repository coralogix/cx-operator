package com.coralogix.operator.client.model

import com.coralogix.operator.client.model.generated.apimachinery.v1.ListMeta
import io.circe._
import io.circe.syntax._

case class ObjectList[+T](metadata: Option[ListMeta], items: List[T])

object ObjectList {
  implicit def encodeObjectList[T: Encoder]: Encoder[ObjectList[T]] =
    lst =>
      Json.obj(
        "metadata" := lst.metadata,
        "items"    := lst.items
      )

  implicit def decodeObjectList[T: Decoder]: Decoder[ObjectList[T]] =
    (c: HCursor) =>
      for {
        metadata <- c.downField("metadata").as[Option[ListMeta]]
        items    <- c.downField("items").as[List[T]]
      } yield ObjectList(metadata, items)

}
