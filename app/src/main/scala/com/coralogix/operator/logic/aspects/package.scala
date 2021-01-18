package com.coralogix.operator.logic

import com.coralogix.operator.monitoring.OperatorMetrics
import com.coralogix.zio.k8s.operator.Operator._
import zio.clock.Clock

package object aspects {

  /**
    * Measures execution time and occurrence per event type
    *
    * @param operatorMetrics Pre-created shared Prometheus metric objects
    */
  def metered[T <: Object, E](
    operatorMetrics: OperatorMetrics
  ): Aspect[Clock, E, T] =
    new Aspect[Clock, E, T] {
      override def apply[R1 <: Clock, E1 >: E](
        f: EventProcessor[R1, E1, T]
      ): EventProcessor[R1, E1, T] =
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
