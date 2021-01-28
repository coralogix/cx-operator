package com.coralogix.operator.logic.operators.alertset

import cats.Traverse
import cats.instances.either._
import cats.instances.vector._
import com.coralogix.alerts.grpc.external.v1.{
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
import com.coralogix.zio.k8s.client.model.primitives
import com.coralogix.zio.k8s.client.model.primitives.AlertId

import java.time.LocalDate

object ModelTransformations {

  def toCreateAlert(alert: AlertSet.Spec.Alerts): Either[String, CreateAlertRequest] =
    alert.activeWhen.map(a => toActiveWhen(a).map(Some.apply)).getOrElse(Right(None)).map {
      activeWhen =>
        CreateAlertRequest(
          name = Some(alert.name.value),
          description = alert.description,
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

  def toAlert(alert: AlertSet.Spec.Alerts, id: AlertId): Either[String, Alert] =
    alert.activeWhen.map(a => toActiveWhen(a).map(Some.apply)).getOrElse(Right(None)).map {
      activeWhen =>
        Alert(
          id = Some(id.value),
          name = Some(alert.name.value),
          description = alert.description,
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
      case Severity.members.Info     => AlertSeverity.INFO
      case Severity.members.Warning  => AlertSeverity.WARNING
      case Severity.members.Critical => AlertSeverity.CRITICAL
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
      groupBy = condition.groupBy
    )

  private def toTimeframe(timeframe: Alerts.Condition.Parameters.Timeframe): Timeframe =
    timeframe match {
      case Parameters.Timeframe.members.`5Min`  => Timeframe._5Min
      case Parameters.Timeframe.members.`10Min` => Timeframe._10Min
      case Parameters.Timeframe.members.`20Min` => Timeframe._20Min
      case Parameters.Timeframe.members.`30Min` => Timeframe._30Min
      case Parameters.Timeframe.members.`1H`    => Timeframe._1H
      case Parameters.Timeframe.members.`2H`    => Timeframe._2H
      case Parameters.Timeframe.members.`3H`    => Timeframe._3H
      case Parameters.Timeframe.members.`4H`    => Timeframe._4H
      case Parameters.Timeframe.members.`6H`    => Timeframe._6H
      case Parameters.Timeframe.members.`12H`   => Timeframe._12H
      case Parameters.Timeframe.members.`24H`   => Timeframe._24H
      case Parameters.Timeframe.members.`48H`   => Timeframe._48H
      case Parameters.Timeframe.members.`72H`   => Timeframe._72H
      case Parameters.Timeframe.members.`1W`    => Timeframe._1W
      case Parameters.Timeframe.members.`1M`    => Timeframe._1M
      case Parameters.Timeframe.members.`2M`    => Timeframe._2M
      case Parameters.Timeframe.members.`3M`    => Timeframe._3M
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
      case Severities.members.Debug    => AlertFilters.LogSeverity.DEBUG
      case Severities.members.Verbose  => AlertFilters.LogSeverity.VERBOSE
      case Severities.members.Info     => AlertFilters.LogSeverity.INFO
      case Severities.members.Warning  => AlertFilters.LogSeverity.WARNING
      case Severities.members.Error    => AlertFilters.LogSeverity.ERROR
      case Severities.members.Critical => AlertFilters.LogSeverity.CRITICAL
    }

  private def toLogSeverity(
    severity: Alerts.Filters.RatioAlerts.Severities
  ): AlertFilters.LogSeverity =
    severity match {
      case RatioAlertSeverities.members.Debug    => AlertFilters.LogSeverity.DEBUG
      case RatioAlertSeverities.members.Verbose  => AlertFilters.LogSeverity.VERBOSE
      case RatioAlertSeverities.members.Info     => AlertFilters.LogSeverity.INFO
      case RatioAlertSeverities.members.Warning  => AlertFilters.LogSeverity.WARNING
      case RatioAlertSeverities.members.Error    => AlertFilters.LogSeverity.ERROR
      case RatioAlertSeverities.members.Critical => AlertFilters.LogSeverity.CRITICAL
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
      case FilterType.members.Text     => AlertFilters.FilterType.TEXT
      case FilterType.members.Template => AlertFilters.FilterType.TEMPLATE
      case FilterType.members.Ratio    => AlertFilters.FilterType.RATIO
    }

  private def toActiveWhen(activeWhen: Alerts.ActiveWhen): Either[String, AlertActiveWhen] =
    Traverse[Vector].sequence(activeWhen.timeframes.map(toActiveTimeframe)).map(AlertActiveWhen(_))

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
      case DaysOfWeek.members.Monday    => DayOfWeek.MONDAY
      case DaysOfWeek.members.Tuesday   => DayOfWeek.TUESDAY
      case DaysOfWeek.members.Wednesday => DayOfWeek.WEDNESDAY
      case DaysOfWeek.members.Thursday  => DayOfWeek.THURSDAY
      case DaysOfWeek.members.Friday    => DayOfWeek.FRIDAY
      case DaysOfWeek.members.Saturday  => DayOfWeek.SATURDAY
      case DaysOfWeek.members.Sunday    => DayOfWeek.SUNDAY
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
}
