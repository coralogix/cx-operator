package com.coralogix.operator.logic.operators.alertset

import cats.implicits._
import com.coralogix.alerts.v1.MetricAlertConditionParameters.{ ArithmeticOperator, MetricSource }
import com.coralogix.alerts.v1.{
  Alert,
  AlertActiveTimeframe,
  AlertActiveWhen,
  AlertCondition,
  AlertFilters,
  AlertNotifications,
  AlertSeverity,
  ConditionParameters,
  CreateAlertRequest,
  Date,
  DayOfWeek,
  ImmediateCondition,
  LessThanCondition,
  MetricAlertConditionParameters,
  MetricAlertPromqlConditionParameters,
  MoreThanCondition,
  MoreThanUsualCondition,
  NewValueCondition,
  Time,
  TimeRange,
  Timeframe
}
import com.coralogix.zio.k8s.client.com.coralogix.definitions.alertset.v1.AlertSet
import com.coralogix.zio.k8s.client.com.coralogix.definitions.alertset.v1.AlertSet.Spec.Alerts
import com.coralogix.zio.k8s.client.com.coralogix.definitions.alertset.v1.AlertSet.Spec.Alerts.ActiveWhen.Timeframes.DaysOfWeek
import com.coralogix.zio.k8s.client.com.coralogix.definitions.alertset.v1.AlertSet.Spec.Alerts.Condition.Parameters
import com.coralogix.zio.k8s.client.com.coralogix.definitions.alertset.v1.AlertSet.Spec.Alerts.Condition.Type
import com.coralogix.zio.k8s.client.com.coralogix.definitions.alertset.v1.AlertSet.Spec.Alerts.Filters.FilterType
import com.coralogix.zio.k8s.client.com.coralogix.definitions.alertset.v1.AlertSet.Spec.Alerts.Filters.Severities
import com.coralogix.zio.k8s.client.com.coralogix.definitions.alertset.v1.AlertSet.Spec.Alerts.Filters.RatioAlerts.{
  Severities => RatioAlertSeverities
}
import com.coralogix.zio.k8s.client.com.coralogix.definitions.alertset.v1.AlertSet.Spec.Alerts.Severity
import com.coralogix.zio.k8s.client.model.{ primitives, Optional }
import com.coralogix.zio.k8s.client.model.primitives.AlertId
import com.coralogix.zio.k8s.operator.Operator.OperatorContext

import java.time.LocalDate
import scala.language.implicitConversions

object ModelTransformations {
  private implicit def toOption[T](opt: Optional[T]): Option[T] = opt.toOption

  def toCreateAlert(
    alert: AlertSet.Spec.Alerts,
    ctx: OperatorContext,
    setName: String
  ): Either[String, CreateAlertRequest] =
    alert.activeWhen.map(a => toActiveWhen(a).map(Some.apply)).getOrElse(Right(None)).map {
      activeWhen =>
        CreateAlertRequest(
          name = Some(alert.name.value),
          description = alert.description.orElse(defaultDescription(ctx, setName)),
          isActive = Some(alert.isActive),
          severity = toAlertSeverity(alert.severity),
          expiration = alert.expiration.map(toDate),
          condition = alert.condition.map(toCondition),
          notifications = alert.notifications.map(toNotifications),
          filters = alert.filters.map(toFilters),
          notifyEvery = alert.notifyEvery.map(_.value),
          activeWhen = activeWhen,
          notificationPayloadFilters =
            alert.notificationPayloadFilters.map(_.map(_.value)).getOrElse(Seq.empty)
        )
    }

  def toAlert(
    alert: AlertSet.Spec.Alerts,
    id: AlertId,
    ctx: OperatorContext,
    setName: String
  ): Either[String, Alert] =
    alert.activeWhen.map(a => toActiveWhen(a).map(Some.apply)).getOrElse(Right(None)).map {
      activeWhen =>
        Alert(
          id = Some(id.value),
          name = Some(alert.name.value),
          description = alert.description.orElse(defaultDescription(ctx, setName)),
          isActive = Some(alert.isActive),
          severity = toAlertSeverity(alert.severity),
          expiration = alert.expiration.map(toDate),
          condition = alert.condition.map(toCondition),
          notifications = alert.notifications.map(toNotifications),
          filters = alert.filters.map(toFilters),
          notifyEvery = alert.notifyEvery.map(_.value),
          activeWhen = activeWhen,
          notificationPayloadFilters =
            alert.notificationPayloadFilters.map(_.map(_.value)).getOrElse(Seq.empty)
        )
    }

  private def toAlertSeverity(severity: AlertSet.Spec.Alerts.Severity): AlertSeverity =
    severity match {
      case Severity.members.Info     => AlertSeverity.ALERT_SEVERITY_INFO_OR_UNSPECIFIED
      case Severity.members.Warning  => AlertSeverity.ALERT_SEVERITY_WARNING
      case Severity.members.Critical => AlertSeverity.ALERT_SEVERITY_CRITICAL
    }

  private def toDate(localDate: LocalDate): Date =
    Date(localDate.getYear, localDate.getMonthValue, localDate.getDayOfMonth)

  private def toCondition(condition: AlertSet.Spec.Alerts.Condition): AlertCondition =
    AlertCondition(
      condition.`type` match {
        case Type.members.Immediate => AlertCondition.Condition.Immediate(ImmediateCondition())
        case Type.members.LessThan =>
          AlertCondition.Condition.LessThan(
            LessThanCondition(condition.parameters.map(toConditionParameters))
          )
        case Type.members.MoreThan =>
          AlertCondition.Condition.MoreThan(
            MoreThanCondition(condition.parameters.map(toConditionParameters))
          )
        case Type.members.MoreThanUsual =>
          AlertCondition.Condition.MoreThanUsual(
            MoreThanUsualCondition(condition.parameters.map(toConditionParameters))
          )
        case Type.members.NewValue =>
          AlertCondition.Condition.NewValue(
            NewValueCondition(condition.parameters.map(toConditionParameters))
          )
      }
    )

  private def toConditionParameters(condition: Alerts.Condition.Parameters): ConditionParameters =
    ConditionParameters(
      threshold = condition.threshold,
      timeframe = toTimeframe(condition.timeframe),
      groupBy = condition.groupBy.toList,
      notifyOnResolved = condition.notifyOnResolved,
      metricAlertParameters = condition.metricAlertParameters.map(toMetricAlertParameters).toOption,
      metricAlertPromqlParameters =
        condition.metricAlertPromqlParameters.map(toMetricAlertPromqlParameters).toOption
    )

  private def toMetricAlertParameters(
    parameters: Alerts.Condition.Parameters.MetricAlertParameters
  ): MetricAlertConditionParameters =
    MetricAlertConditionParameters(
      metricField = parameters.metricField,
      metricSource = parameters.metricSource
        .map(toMetricSource)
        .getOrElse(MetricSource.METRIC_SOURCE_LOGS2METRICS_OR_UNSPECIFIED),
      arithmeticOperator = parameters.arithmeticOperator
        .map(toArithmeticOperator)
        .getOrElse(
          MetricAlertConditionParameters.ArithmeticOperator.ARITHMETIC_OPERATOR_AVG_OR_UNSPECIFIED
        ),
      arithmeticOperatorModifier = parameters.arithmeticOperatorModifier,
      sampleThresholdPercentage = parameters.sampleThresholdPercentage,
      nonNullPercentage = parameters.nonNullPercentage,
      swapNullValues = parameters.swapNullValues
    )

  private def toMetricSource(
    source: Alerts.Condition.Parameters.MetricAlertParameters.MetricSource
  ): MetricSource =
    source match {
      case Alerts.Condition.Parameters.MetricAlertParameters.MetricSource.members.Logs2Metrics =>
        MetricSource.METRIC_SOURCE_LOGS2METRICS_OR_UNSPECIFIED
      case Alerts.Condition.Parameters.MetricAlertParameters.MetricSource.members.Prometheus =>
        MetricSource.METRIC_SOURCE_PROMETHEUS
    }

  private def toArithmeticOperator(
    operator: Alerts.Condition.Parameters.MetricAlertParameters.ArithmeticOperator
  ): ArithmeticOperator =
    operator match {
      case Alerts.Condition.Parameters.MetricAlertParameters.ArithmeticOperator.members.Avg =>
        ArithmeticOperator.ARITHMETIC_OPERATOR_AVG_OR_UNSPECIFIED
      case Alerts.Condition.Parameters.MetricAlertParameters.ArithmeticOperator.members.Count =>
        ArithmeticOperator.ARITHMETIC_OPERATOR_COUNT
      case Alerts.Condition.Parameters.MetricAlertParameters.ArithmeticOperator.members.Max =>
        ArithmeticOperator.ARITHMETIC_OPERATOR_MAX
      case Alerts.Condition.Parameters.MetricAlertParameters.ArithmeticOperator.members.Min =>
        ArithmeticOperator.ARITHMETIC_OPERATOR_MIN
      case Alerts.Condition.Parameters.MetricAlertParameters.ArithmeticOperator.members.Sum =>
        ArithmeticOperator.ARITHMETIC_OPERATOR_SUM
      case Alerts.Condition.Parameters.MetricAlertParameters.ArithmeticOperator.members.Percentile =>
        ArithmeticOperator.ARITHMETIC_OPERATOR_PERCENTILE
    }

  private def toMetricAlertPromqlParameters(
    parameters: Alerts.Condition.Parameters.MetricAlertPromqlParameters
  ): MetricAlertPromqlConditionParameters =
    MetricAlertPromqlConditionParameters(
      promqlText = parameters.promqlText,
      arithmeticOperatorModifier = parameters.arithmeticOperatorModifier,
      sampleThresholdPercentage = parameters.sampleThresholdPercentage,
      nonNullPercentage = parameters.nonNullPercentage,
      swapNullValues = parameters.swapNullValues
    )

  private def toTimeframe(timeframe: Alerts.Condition.Parameters.Timeframe): Timeframe =
    timeframe match {
      case Parameters.Timeframe.members.`5Min`  => Timeframe.TIMEFRAME_5_MIN_OR_UNSPECIFIED
      case Parameters.Timeframe.members.`10Min` => Timeframe.TIMEFRAME_10_MIN
      case Parameters.Timeframe.members.`20Min` => Timeframe.TIMEFRAME_20_MIN
      case Parameters.Timeframe.members.`30Min` => Timeframe.TIMEFRAME_30_MIN
      case Parameters.Timeframe.members.`1H`    => Timeframe.TIMEFRAME_1_H
      case Parameters.Timeframe.members.`2H`    => Timeframe.TIMEFRAME_2_H
      case Parameters.Timeframe.members.`3H`    => Timeframe.TIMEFRAME_3_H
      case Parameters.Timeframe.members.`4H`    => Timeframe.TIMEFRAME_4_H
      case Parameters.Timeframe.members.`6H`    => Timeframe.TIMEFRAME_6_H
      case Parameters.Timeframe.members.`12H`   => Timeframe.TIMEFRAME_12_H
      case Parameters.Timeframe.members.`24H`   => Timeframe.TIMEFRAME_24_H
      case Parameters.Timeframe.members.`48H`   => Timeframe.TIMEFRAME_48_H
      case Parameters.Timeframe.members.`72H`   => Timeframe.TIMEFRAME_72_H
      case Parameters.Timeframe.members.`1W`    => Timeframe.TIMEFRAME_1_W
      case Parameters.Timeframe.members.`1M`    => Timeframe.TIMEFRAME_1_M
      case Parameters.Timeframe.members.`2M`    => Timeframe.TIMEFRAME_2_M
      case Parameters.Timeframe.members.`3M`    => Timeframe.TIMEFRAME_3_M
    }

  private def toNotifications(notifications: Alerts.Notifications): AlertNotifications =
    AlertNotifications(
      emails = notifications.emails.map(_.map(_.value)).getOrElse(Seq.empty),
      integrations = notifications.integrations.map(_.map(_.value)).getOrElse(Seq.empty)
    )

  private def toFilters(filters: Alerts.Filters): AlertFilters =
    AlertFilters(
      severities = filters.severities.map(_.map(toLogSeverity)).getOrElse(Seq.empty),
      metadata = filters.metadata.map(toMetadata),
      alias = filters.alias.map(_.value),
      text = filters.text.map(_.value),
      ratioAlerts = filters.ratioAlerts.map(_.map(toRatioAlerts)).getOrElse(Seq.empty),
      filterType = toFilterType(filters.filterType)
    )

  private def toLogSeverity(severity: Alerts.Filters.Severities): AlertFilters.LogSeverity =
    severity match {
      case Severities.members.Debug    => AlertFilters.LogSeverity.LOG_SEVERITY_DEBUG_OR_UNSPECIFIED
      case Severities.members.Verbose  => AlertFilters.LogSeverity.LOG_SEVERITY_VERBOSE
      case Severities.members.Info     => AlertFilters.LogSeverity.LOG_SEVERITY_INFO
      case Severities.members.Warning  => AlertFilters.LogSeverity.LOG_SEVERITY_WARNING
      case Severities.members.Error    => AlertFilters.LogSeverity.LOG_SEVERITY_ERROR
      case Severities.members.Critical => AlertFilters.LogSeverity.LOG_SEVERITY_CRITICAL
    }

  private def toLogSeverity(
    severity: Alerts.Filters.RatioAlerts.Severities
  ): AlertFilters.LogSeverity =
    severity match {
      case RatioAlertSeverities.members.Debug =>
        AlertFilters.LogSeverity.LOG_SEVERITY_DEBUG_OR_UNSPECIFIED
      case RatioAlertSeverities.members.Verbose  => AlertFilters.LogSeverity.LOG_SEVERITY_VERBOSE
      case RatioAlertSeverities.members.Info     => AlertFilters.LogSeverity.LOG_SEVERITY_INFO
      case RatioAlertSeverities.members.Warning  => AlertFilters.LogSeverity.LOG_SEVERITY_WARNING
      case RatioAlertSeverities.members.Error    => AlertFilters.LogSeverity.LOG_SEVERITY_ERROR
      case RatioAlertSeverities.members.Critical => AlertFilters.LogSeverity.LOG_SEVERITY_CRITICAL
    }

  private def toMetadata(metadata: Alerts.Filters.Metadata): AlertFilters.MetadataFilters =
    AlertFilters.MetadataFilters(
      categories = metadata.categories.map(_.map(_.value)).getOrElse(Seq.empty),
      applications = metadata.applications.map(_.map(_.value)).getOrElse(Seq.empty),
      subsystems = metadata.subsystems.map(_.map(_.value)).getOrElse(Seq.empty),
      computers = metadata.computers.map(_.map(_.value)).getOrElse(Seq.empty),
      classes = metadata.classes.map(_.map(_.value)).getOrElse(Seq.empty),
      methods = metadata.methods.map(_.map(_.value)).getOrElse(Seq.empty),
      ipAddresses = metadata.ipAddresses.map(_.map(_.value)).getOrElse(Seq.empty)
    )

  private def toRatioAlerts(ratioAlerts: Alerts.Filters.RatioAlerts): AlertFilters.RatioAlert =
    AlertFilters.RatioAlert(
      alias = ratioAlerts.alias.map(_.value),
      text = ratioAlerts.text.map(_.value),
      severities = ratioAlerts.severities.map(_.map(toLogSeverity)).getOrElse(Seq.empty),
      applications = ratioAlerts.applications.map(_.map(_.value)).getOrElse(Seq.empty),
      subsystems = ratioAlerts.subsystems.map(_.map(_.value)).getOrElse(Seq.empty)
    )

  private def toFilterType(filterType: Alerts.Filters.FilterType): AlertFilters.FilterType =
    filterType match {
      case FilterType.members.Text     => AlertFilters.FilterType.FILTER_TYPE_TEXT_OR_UNSPECIFIED
      case FilterType.members.Template => AlertFilters.FilterType.FILTER_TYPE_TEMPLATE
      case FilterType.members.Ratio    => AlertFilters.FilterType.FILTER_TYPE_RATIO
      case FilterType.members.Metric   => AlertFilters.FilterType.FILTER_TYPE_METRIC
    }

  private def toActiveWhen(activeWhen: Alerts.ActiveWhen): Either[String, AlertActiveWhen] =
    activeWhen.timeframes.map(toActiveTimeframe).sequence.map(AlertActiveWhen(_))

  private def toActiveTimeframe(
    timeframe: Alerts.ActiveWhen.Timeframes
  ): Either[String, AlertActiveTimeframe] =
    timeframe.range.map(r => toRange(r).map(Some.apply)).getOrElse(Right(None)).map { range =>
      AlertActiveTimeframe(
        daysOfWeek = timeframe.daysOfWeek.map(_.map(toDayOfWeek)).getOrElse(Seq.empty),
        range = range
      )
    }

  private def toDayOfWeek(day: Alerts.ActiveWhen.Timeframes.DaysOfWeek): DayOfWeek =
    day match {
      case DaysOfWeek.members.Monday    => DayOfWeek.DAY_OF_WEEK_MONDAY_OR_UNSPECIFIED
      case DaysOfWeek.members.Tuesday   => DayOfWeek.DAY_OF_WEEK_TUESDAY
      case DaysOfWeek.members.Wednesday => DayOfWeek.DAY_OF_WEEK_WEDNESDAY
      case DaysOfWeek.members.Thursday  => DayOfWeek.DAY_OF_WEEK_THURSDAY
      case DaysOfWeek.members.Friday    => DayOfWeek.DAY_OF_WEEK_FRIDAY
      case DaysOfWeek.members.Saturday  => DayOfWeek.DAY_OF_WEEK_SATURDAY
      case DaysOfWeek.members.Sunday    => DayOfWeek.DAY_OF_WEEK_SUNDAY
    }

  private def toRange(range: Alerts.ActiveWhen.Timeframes.Range): Either[String, TimeRange] =
    for {
      start <- toTime(range.start)
      end   <- toTime(range.end)
    } yield TimeRange(
      start = Some(start),
      end = Some(end)
    )

  private def toTime(time: primitives.Time): Either[String, Time] = {
    val parts = time.value.split(':').map(_.toIntOption).toList
    parts match {
      case List(Some(hour), Some(minute), Some(second)) => Right(Time(hour, minute, second))
      case List(Some(hour), Some(minute))               => Right(Time(hour, minute, 0))
      case List(Some(hour))                             => Right(Time(hour, 0, 0))
      case _                                            => Left(s"Invalid time value: ${time.value}; must be HH:MM:SS")
    }
  }

  private def defaultDescription(ctx: OperatorContext, setName: String): Optional[String] =
    Optional.Present(
      s"Managed by Coralogix Operator (${ctx.namespace.map(ns => s"$ns namespace").getOrElse("cluster")}, $setName alert set)"
    )
}
