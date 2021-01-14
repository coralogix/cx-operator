package com.coralogix.operator.logging

import com.coralogix.zio.k8s.client.model.K8sNamespace
import com.coralogix.zio.k8s.operator.OperatorLogging.{ logNamespace, logResourceType }
import zio.Cause
import zio.logging.{ LogAnnotation, LogContext, LogFormat }

import java.time.format.DateTimeFormatter

class OperatorLogFormat extends LogFormat[String] {
  private val builder = new StringBuilder()

  override def format(context: LogContext, line: String): String = {
    val date = context.get(LogAnnotation.Timestamp).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
    val level = context(LogAnnotation.Level)
    val loggerName = context(LogAnnotation.Name)
    val maybeError = context
      .get(LogAnnotation.Throwable)
      .map(Cause.fail)
      .orElse(context.get(LogAnnotation.Cause))
      .map(cause => System.lineSeparator() + cause.prettyPrint)

    builder.clear()
    builder.append('[')
    builder.append(level.toUpperCase)
    builder.append("] ")
    builder.append(date)
    builder.append(' ')
    builder.append(loggerName)
    builder.append(' ')
    (context.get(logResourceType), context.get(logNamespace)) match {
      case (Some(rt), Some(K8sNamespace(ns))) =>
        builder.append('[')
        builder.append(rt)
        builder.append('@')
        builder.append(ns)
        builder.append("] ")
      case (Some(rt), None) =>
        builder.append('[')
        builder.append(rt)
        builder.append("] ")
      case _ =>
    }
    builder.append(line)
    maybeError match {
      case Some(error) =>
        builder.append(error)
      case None =>
    }
    builder.toString()
  }
}
