package com.coralogix.operator.monitoring

import com.coralogix.zio.k8s.client.model._
import zio.ZIO
import zio.metrics.prometheus.helpers._
import zio.metrics.prometheus.{ Counter, Histogram, Registry }

case class OperatorMetrics(
  eventCounter: Counter,
  eventProcessingTime: Histogram
)

object OperatorMetrics {
  val eventTypeLabel: String = "event_type"
  val resourceNameLabel: String = "resource_name"
  val namespaceLabel: String = "namespace"

  def make: ZIO[Registry, Throwable, OperatorMetrics] =
    for {
      eventCounter <- counter.register(
                        "events_total",
                        Array(
                          eventTypeLabel,
                          resourceNameLabel,
                          namespaceLabel
                        )
                      )
      eventProcessingTime <- histogram.register(
                               "event_processing_time_seconds",
                               Array(
                                 eventTypeLabel,
                                 resourceNameLabel,
                                 namespaceLabel
                               )
                             )
    } yield OperatorMetrics(eventCounter, eventProcessingTime)

  def labels(
    event: TypedWatchEvent[_],
    resourceName: String,
    namespace: Option[K8sNamespace]
  ): Array[String] = {
    val eventType = event match {
      case Reseted =>
        "reseted"
      case Added(_) =>
        "added"
      case Modified(_) =>
        "modified"
      case Deleted(_) =>
        "deleted"
    }
    Array(eventType, resourceName, namespace.map(_.value).getOrElse(""))
  }
}
