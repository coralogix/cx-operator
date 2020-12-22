package com.coralogix.operator.monitoring

import io.grpc.Status
import zio.ZIO
import zio.metrics.prometheus.helpers.{ counter, gauge, histogram }
import zio.metrics.prometheus.{ Counter, Gauge, Histogram, Registry }

case class ClientMetrics(
  callsCount: Counter,
  callTime: Histogram,
  inFlight: Gauge,
  inQueue: Gauge
)

object ClientMetrics {
  val serviceNameLabel: String = "service_name"
  val methodNameLabel: String = "method_name"
  val statusLabel: String = "status"

  def labels(serviceName: String, methodName: String, status: Status): Array[String] =
    Array(serviceName, methodName, status.getCode.name())

  def make: ZIO[Registry, Throwable, ClientMetrics] =
    for {
      callCounter <- counter.register(
                       "grpc_calls_total",
                       Array(
                         serviceNameLabel,
                         methodNameLabel,
                         statusLabel
                       )
                     )
      callTime <- histogram.register(
                    "grpc_call_time_seconds",
                    Array(
                      serviceNameLabel,
                      methodNameLabel,
                      statusLabel
                    )
                  )

      inFlight <- gauge.register(
                    "grpc_in_flight",
                    Array(serviceNameLabel)
                  )

      inQueue <- gauge.register(
                   "grpc_in_queue",
                   Array(serviceNameLabel)
                 )
    } yield ClientMetrics(callCounter, callTime, inFlight, inQueue)
}
