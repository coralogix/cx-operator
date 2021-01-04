package com.coralogix.operator.logic

import com.coralogix.operator.client.model.{ Added, Deleted, Modified, Object, Reseted }
import com.coralogix.operator.logging.OperatorLogging
import com.coralogix.operator.logic.Operator._
import com.coralogix.operator.monitoring.OperatorMetrics
import zio.Cause
import zio.clock.Clock
import zio.logging.{ log, Logging }

package object aspects {

  /**
    * Logs each watch event and event processor failures
    */
  def logEvents[StatusT, T <: Object[StatusT]]: Aspect[Logging, StatusT, T] =
    new Aspect[Logging, StatusT, T] {
      override def apply[R1 <: Logging](
        f: EventProcessor[R1, StatusT, T]
      ): EventProcessor[R1, StatusT, T] =
        (ctx, event) =>
          log.locally(OperatorLogging(ctx.withSpecificNamespace(event.namespace))) {
            (event match {
              case event @ Reseted =>
                log.debug(s"State reseted") *>
                  f(ctx, event)
              case event @ Added(resource) =>
                log.debug(s"Resource added: ${resource.metadata.flatMap(_.name).getOrElse("?")}") *>
                  f(ctx, event)
              case event @ Modified(resource) =>
                log.debug(
                  s"Resource modified: ${resource.metadata.flatMap(_.name).getOrElse("?")}"
                ) *>
                  f(ctx, event)
              case event @ Deleted(resource) =>
                log.debug(
                  s"Resource deleted: ${resource.metadata.flatMap(_.name).getOrElse("?")}"
                ) *>
                  f(ctx, event)
            }).tapError { failure =>
              log.error(
                s"Failed to process ${event.getClass.getSimpleName} event",
                Cause.fail(failure)
              )
            }
          }
    }

  /**
    * Measures execution time and occurrence per event type
    *
    * @param operatorMetrics Pre-created shared Prometheus metric objects
    */
  def metered[StatusT, T <: Object[StatusT]](
    operatorMetrics: OperatorMetrics
  ): Aspect[Clock, StatusT, T] =
    new Aspect[Clock, StatusT, T] {
      override def apply[R1 <: Clock](
        f: EventProcessor[R1, StatusT, T]
      ): EventProcessor[R1, StatusT, T] =
        (ctx, event) => {
          val labels = OperatorMetrics.labels(
            event,
            ctx.resourceType.resourceType,
            ctx.namespace.orElse(event.namespace)
          )

          operatorMetrics.eventCounter.inc(labels).ignore *>
            f(ctx, event).timed.flatMap {
              case (duration, result) =>
                operatorMetrics.eventProcessingTime
                  .observe(
                    duration.toMillis.toDouble / 1000.0,
                    labels
                  )
                  .ignore
                  .as(result)
            }
        }
    }

}
