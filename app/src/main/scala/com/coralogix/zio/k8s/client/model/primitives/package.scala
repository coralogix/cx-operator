package com.coralogix.zio.k8s.client.model

package object primitives {

  final case class FieldName(value: String) extends AnyVal
  object FieldName extends ValueTypeImplicits[FieldName, String] {
    lazy val typeClasses = ValueTypeClasses(FieldName.apply, _.value)
  }

  final case class MethodName(value: String) extends AnyVal
  object MethodName extends ValueTypeImplicits[MethodName, String] {
    lazy val typeClasses = ValueTypeClasses(MethodName.apply, _.value)
  }

  final case class ClassName(value: String) extends AnyVal
  object ClassName extends ValueTypeImplicits[ClassName, String] {
    lazy val typeClasses = ValueTypeClasses(ClassName.apply, _.value)
  }

  final case class ApplicationName(value: String) extends AnyVal
  object ApplicationName extends ValueTypeImplicits[ApplicationName, String] {
    lazy val typeClasses = ValueTypeClasses(ApplicationName.apply, _.value)
  }

  final case class ComputerName(value: String) extends AnyVal
  object ComputerName extends ValueTypeImplicits[ComputerName, String] {
    lazy val typeClasses = ValueTypeClasses(ComputerName.apply, _.value)
  }

  final case class SubsystemName(value: String) extends AnyVal
  object SubsystemName extends ValueTypeImplicits[SubsystemName, String] {
    lazy val typeClasses = ValueTypeClasses(SubsystemName.apply, _.value)
  }

  final case class CategoryName(value: String) extends AnyVal
  object CategoryName extends ValueTypeImplicits[CategoryName, String] {
    lazy val typeClasses = ValueTypeClasses(CategoryName.apply, _.value)
  }

  final case class ThreadId(value: String) extends AnyVal
  object ThreadId extends ValueTypeImplicits[ThreadId, String] {
    lazy val typeClasses = ValueTypeClasses(ThreadId.apply, _.value)
  }

  final case class IPAddress(value: String) extends AnyVal
  object IPAddress extends ValueTypeImplicits[IPAddress, String] {
    lazy val typeClasses = ValueTypeClasses(IPAddress.apply, _.value)
  }

  final case class RuleGroupName(value: String) extends AnyVal
  object RuleGroupName extends ValueTypeImplicits[RuleGroupName, String] {
    override lazy val typeClasses = ValueTypeClasses(RuleGroupName.apply, _.value)
  }

  final case class RuleGroupId(value: String) extends AnyVal
  object RuleGroupId extends ValueTypeImplicits[RuleGroupId, String] {
    override lazy val typeClasses = ValueTypeClasses(RuleGroupId.apply, _.value)
  }

  final case class RuleName(value: String) extends AnyVal
  object RuleName extends ValueTypeImplicits[RuleName, String] {
    override lazy val typeClasses = ValueTypeClasses(RuleName.apply, _.value)
  }

  final case class RuleId(value: String) extends AnyVal
  object RuleId extends ValueTypeImplicits[RuleId, String] {
    override lazy val typeClasses = ValueTypeClasses(RuleId.apply, _.value)
  }

  final case class AlertName(value: String) extends AnyVal
  object AlertName extends ValueTypeImplicits[AlertName, String] {
    override lazy val typeClasses = ValueTypeClasses(AlertName.apply, _.value)
  }

  final case class Seconds(value: Double) extends AnyVal
  object Seconds extends ValueTypeImplicits[Seconds, Double] {
    override lazy val typeClasses = ValueTypeClasses(Seconds.apply, _.value)
  }

  final case class PayloadFilter(value: String) extends AnyVal
  object PayloadFilter extends ValueTypeImplicits[PayloadFilter, String] {
    override lazy val typeClasses = ValueTypeClasses(PayloadFilter.apply, _.value)
  }

  final case class IntegrationAlias(value: String) extends AnyVal
  object IntegrationAlias extends ValueTypeImplicits[IntegrationAlias, String] {
    override lazy val typeClasses = ValueTypeClasses(IntegrationAlias.apply, _.value)
  }

  final case class Time(value: String) extends AnyVal
  object Time extends ValueTypeImplicits[Time, String] {
    override lazy val typeClasses = ValueTypeClasses(Time.apply, _.value)
  }

  final case class AlertId(value: String) extends AnyVal
  object AlertId extends ValueTypeImplicits[AlertId, String] {
    override lazy val typeClasses = ValueTypeClasses(AlertId.apply, _.value)
  }

  final case class UniqueAlertId(value: String) extends AnyVal
  object UniqueAlertId extends ValueTypeImplicits[UniqueAlertId, String] {
    override lazy val typeClasses = ValueTypeClasses(UniqueAlertId.apply, _.value)
  }

  final case class EmailAddress(value: String) extends AnyVal
  object EmailAddress extends ValueTypeImplicits[EmailAddress, String] {
    override lazy val typeClasses = ValueTypeClasses(EmailAddress.apply, _.value)
  }

  final case class QueryAlias(value: String) extends AnyVal
  object QueryAlias extends ValueTypeImplicits[QueryAlias, String] {
    override lazy val typeClasses = ValueTypeClasses(QueryAlias.apply, _.value)
  }

  final case class Query(value: String) extends AnyVal
  object Query extends ValueTypeImplicits[Query, String] {
    override lazy val typeClasses = ValueTypeClasses(Query.apply, _.value)
  }

}
