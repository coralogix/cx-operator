package com.coralogix.operator.logic

import com.coralogix.operator
import com.coralogix.operator.client.model.{
  K8sNamespace,
  K8sResourceType,
  Object,
  ResourceMetadata,
  TypedWatchEvent
}
import com.coralogix.operator.client.{ ClusterResource, K8sFailure, NamespacedResource }
import com.coralogix.operator.logging.{ logFailure, ConvertableToThrowable, OperatorLogging }
import com.coralogix.operator.logic.Operator.OperatorContext
import izumi.reflect.Tag
import zio.clock.Clock
import zio.duration.durationInt
import zio.logging.{ log, Logging }
import zio.stream.ZStream
import zio.{ Cause, Fiber, Has, Schedule, URIO, ZIO }

/**
  * Core implementation of the operator logic.
  * Watches a stream and calls an event processor.
  *
  * An instance of this is tied to one particular resource type in one namespace.
  */
trait Operator[R, StatusT, T <: Object[StatusT]] {
  protected def watchStream(): ZStream[Clock, K8sFailure, TypedWatchEvent[T]]

  def processEvent(event: TypedWatchEvent[T]): ZIO[R, OperatorFailure, Unit]

  val context: OperatorContext
  val bufferSize: Int

  /**
    * Starts the operator on a forked fiber
    */
  def start(): URIO[R with Clock with Logging, Fiber.Runtime[Nothing, Unit]] =
    watchStream()
      .buffer(bufferSize)
      .mapError(KubernetesFailure.apply)
      .mapM(processEvent)
      .runDrain
      .foldCauseM(
        failure =>
          log.locally(OperatorLogging(context)) {
            logFailure(s"Watch stream failed", failure)
          },
        _ =>
          log.locally(operator.logging.OperatorLogging(context)) {
            log.error(s"Watch stream terminated")
          } *> ZIO.dieMessage("Watch stream should never terminate")
      )
      .repeat(
        (Schedule.exponential(base = 1.second, factor = 2.0) ||
          Schedule.spaced(30.seconds)).unit
      )
      .fork
}

abstract class NamespacedOperator[R, StatusT, T <: Object[StatusT]](
  client: NamespacedResource[StatusT, T],
  namespace: K8sNamespace
) extends Operator[R, StatusT, T] {
  override protected def watchStream(): ZStream[Clock, K8sFailure, TypedWatchEvent[T]] =
    client.watchForever(namespace)
}

abstract class ClusterOperator[R, StatusT, T <: Object[StatusT]](
  client: ClusterResource[StatusT, T]
) extends Operator[R, StatusT, T] {
  override protected def watchStream(): ZStream[Clock, K8sFailure, TypedWatchEvent[T]] =
    client.watchForever()
}

object Operator {

  /** Static contextual information for the event processors,
    * usable for implementing generic loggers/metrics etc.
    */
  case class OperatorContext(resourceType: K8sResourceType, namespace: Option[K8sNamespace])

  type EventProcessor[R, StatusT, T <: Object[StatusT]] =
    (OperatorContext, TypedWatchEvent[T]) => ZIO[R, OperatorFailure, Unit]

  def namespaced[R: Tag, StatusT: Tag, T <: Object[StatusT]: Tag: ResourceMetadata](
    eventProcessor: EventProcessor[R, StatusT, T]
  )(
    namespace: K8sNamespace,
    buffer: Int
  ): ZIO[Has[NamespacedResource[StatusT, T]], Nothing, Operator[R, StatusT, T]] =
    ZIO.service[NamespacedResource[StatusT, T]].map { client =>
      val ctx = OperatorContext(implicitly[ResourceMetadata[T]].resourceType, Some(namespace))
      new NamespacedOperator[R, StatusT, T](client, namespace) {
        override def processEvent(event: TypedWatchEvent[T]): ZIO[R, OperatorFailure, Unit] =
          eventProcessor(ctx, event)
        override val context: OperatorContext = ctx
        override val bufferSize: Int = buffer
      }
    }

  def cluster[R: Tag, StatusT: Tag, T <: Object[StatusT]: Tag: ResourceMetadata](
    eventProcessor: EventProcessor[R, StatusT, T]
  )(buffer: Int): ZIO[Has[ClusterResource[StatusT, T]], Nothing, Operator[R, StatusT, T]] =
    ZIO.service[ClusterResource[StatusT, T]].map { client =>
      val ctx = OperatorContext(implicitly[ResourceMetadata[T]].resourceType, None)
      new ClusterOperator[R, StatusT, T](client) {
        override def processEvent(event: TypedWatchEvent[T]): ZIO[R, OperatorFailure, Unit] =
          eventProcessor(ctx, event)
        override val context: OperatorContext = ctx
        override val bufferSize: Int = buffer
      }
    }

  /** Event processor aspect */
  trait Aspect[-R, StatusT, T <: Object[StatusT]] { self =>
    def apply[R1 <: R](
      f: EventProcessor[R1, StatusT, T]
    ): EventProcessor[R1, StatusT, T]

    final def >>>[R1 <: R](that: Aspect[R1, StatusT, T]): Aspect[R1, StatusT, T] =
      andThen(that)

    final def andThen[R1 <: R](that: Aspect[R1, StatusT, T]): Aspect[R1, StatusT, T] =
      new Aspect[R1, StatusT, T] {
        override def apply[R2 <: R1](
          f: EventProcessor[R2, StatusT, T]
        ): EventProcessor[R2, StatusT, T] =
          that(self(f))
      }
  }

  implicit class EventProcessorOps[R, StatusT, T <: Object[StatusT]](
    eventProcessor: EventProcessor[R, StatusT, T]
  ) {
    def @@[R1 <: R](aspect: Aspect[R1, StatusT, T]): EventProcessor[R1, StatusT, T] =
      aspect[R1](eventProcessor)
  }
}
