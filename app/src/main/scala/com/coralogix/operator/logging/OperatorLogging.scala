package com.coralogix.operator.logging

import com.coralogix.operator.logic.Operator
import zio.logging.{ LogAnnotation, LogContext }

object OperatorLogging {
  def apply(operatorContext: Operator.OperatorContext)(logContext: LogContext): LogContext =
    logContext
      .annotate(LogAnnotation.Name, s"${operatorContext.resourceType.resourceType}Operator" :: Nil)
      .annotate(logResourceType, Some(operatorContext.resourceType.resourceType))
      .annotate(logNamespace, operatorContext.namespace)
}
