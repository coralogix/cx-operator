package com.coralogix.operator.client.model

import com.coralogix.operator.client.model.generated.apimachinery.v1.WatchEvent
import com.coralogix.operator.client.{ DeserializationFailure, InvalidEvent, K8sFailure }
import io.circe.{ Decoder, Json }
import zio.IO

sealed trait TypedWatchEvent[+T] {
  val resourceVersion: Option[String]
  val namespace: Option[K8sNamespace]
}

case object Reseted extends TypedWatchEvent[Nothing] {
  override val resourceVersion: Option[String] = None
  override val namespace: Option[K8sNamespace] = None
}

final case class Added[StatusT, T <: Object[StatusT]](item: T) extends TypedWatchEvent[T] {
  override val resourceVersion: Option[String] = item.metadata.flatMap(_.resourceVersion)
  override val namespace: Option[K8sNamespace] =
    item.metadata.flatMap(_.namespace).map(K8sNamespace.apply)
}

final case class Modified[StatusT, T <: Object[StatusT]](item: T) extends TypedWatchEvent[T] {
  override val resourceVersion: Option[String] = item.metadata.flatMap(_.resourceVersion)
  override val namespace: Option[K8sNamespace] =
    item.metadata.flatMap(_.namespace).map(K8sNamespace.apply)
}

final case class Deleted[StatusT, T <: Object[StatusT]](item: T) extends TypedWatchEvent[T] {
  override val resourceVersion: Option[String] = item.metadata.flatMap(_.resourceVersion)
  override val namespace: Option[K8sNamespace] =
    item.metadata.flatMap(_.namespace).map(K8sNamespace.apply)
}

object TypedWatchEvent {
  private def parseOrFail[T: Decoder](json: Json): IO[K8sFailure, T] =
    IO.fromEither(implicitly[Decoder[T]].decodeAccumulating(json.hcursor).toEither)
      .mapError(DeserializationFailure.apply)

  def from[StatusT, T <: Object[StatusT]: Decoder](
    event: WatchEvent
  ): IO[K8sFailure, TypedWatchEvent[T]] =
    event.`type` match {
      case "ADDED" =>
        parseOrFail[T](event.`object`).map(Added.apply[StatusT, T])
      case "MODIFIED" =>
        parseOrFail[T](event.`object`).map(Modified.apply[StatusT, T])
      case "DELETED" =>
        parseOrFail[T](event.`object`).map(Deleted.apply[StatusT, T])
      case _ =>
        IO.fail(InvalidEvent(event.`type`))
    }
}
