package com.coralogix.operator.logic.operators.coralogixlogger

import com.coralogix.operator.logic.aspects._
import com.coralogix.operator.logic.Operator
import com.coralogix.operator.logic.Operator.EventProcessor
import com.coralogix.operator.monitoring.OperatorMetrics
import zio.ZIO
import zio.clock.Clock
import zio.k8s.client.com.coralogix.loggers.coralogixlogger.{ v1 => coralogixlogger }
import zio.k8s.client.com.coralogix.loggers.coralogixlogger.v1.metadata
import zio.k8s.client.com.coralogix.loggers.definitions.coralogixlogger.v1.Coralogixlogger
import zio.k8s.client.model.K8sNamespace
import zio.logging.Logging

object CoralogixloggerOperator {

  private def eventProcessor(): EventProcessor[
    Logging with coralogixlogger.Coralogixloggers,
    Coralogixlogger
  ] = ???

  def forNamespace(
    namespace: K8sNamespace,
    buffer: Int,
    metrics: OperatorMetrics
  ): ZIO[coralogixlogger.Coralogixloggers, Nothing, Operator[
    Clock with Logging with coralogixlogger.Coralogixloggers,
    Coralogixlogger
  ]] =
    Operator.namespaced(
      eventProcessor() @@ logEvents @@ metered(metrics)
    )(Some(namespace), buffer)

  def forAllNamespaces(
    buffer: Int,
    metrics: OperatorMetrics
  ): ZIO[coralogixlogger.Coralogixloggers, Nothing, Operator[
    Clock with Logging with coralogixlogger.Coralogixloggers,
    Coralogixlogger
  ]] =
    Operator.namespaced(
      eventProcessor() @@ logEvents @@ metered(metrics)
    )(None, buffer)
}
