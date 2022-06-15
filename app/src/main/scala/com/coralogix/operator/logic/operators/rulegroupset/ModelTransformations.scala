package com.coralogix.operator.logic.operators.rulegroupset

import com.coralogix.rules.v1.ExtractTimestampParameters.FormatStandard
import com.coralogix.zio.k8s.client.com.coralogix.definitions.rulegroupset.v1.RuleGroupSet
import com.coralogix.zio.k8s.client.com.coralogix.definitions.rulegroupset.v1.RuleGroupSet.Spec.RuleGroupsSequence.AndSequence.OrGroup
import com.coralogix.zio.k8s.client.com.coralogix.definitions.rulegroupset.v1.RuleGroupSet.Spec.RuleGroupsSequence.AndSequence.OrGroup.JsonExtract.DestField
import com.coralogix.zio.k8s.client.com.coralogix.definitions.rulegroupset.v1.RuleGroupSet.Spec.RuleGroupsSequence.Matcher.Severities
import com.coralogix.rules.v1.JsonExtractParameters.DestinationField
import com.coralogix.rules.v1.CreateRuleGroupRequest
import com.coralogix.rules.v1.CreateRuleGroupRequest.CreateRuleSubgroup
import com.coralogix.rules.v1.CreateRuleGroupRequest.CreateRuleSubgroup.CreateRule
import com.coralogix.rules.v1.RuleMatcher.Constraint
import com.coralogix.rules.v1.{AllowParameters, ApplicationNameConstraint, BlockParameters, ExtractParameters, ExtractTimestampParameters, JsonExtractParameters, JsonParseParameters, JsonStringifyParameters, ParseParameters, RemoveFieldsParameters, ReplaceParameters, RuleMatcher, RuleParameters, SeverityConstraint, SubsystemNameConstraint}
import com.coralogix.zio.k8s.client.com.coralogix.definitions.rulegroupset.v1.RuleGroupSet.Spec.RuleGroupsSequence.AndSequence.OrGroup.ExtractTimestamp.Standard
import com.coralogix.zio.k8s.client.com.coralogix.definitions.rulegroupset.v1.RuleGroupSet.Spec.RuleGroupsSequence.AndSequence.OrGroup.ExtractTimestamp.Standard.members
import com.coralogix.zio.k8s.client.model.Optional
import com.coralogix.zio.k8s.operator.Operator.OperatorContext

import scala.language.implicitConversions

/** Transformation between the gRPC rules API and the k8s CRD's models */
object ModelTransformations {
  private implicit def toOption[T](opt: Optional[T]): Option[T] = opt.toOption

  private val Creator = "coralogix-kubernetes-operator"

  case class RuleGroupWithIndex(ruleGroup: RuleGroupSet.Spec.RuleGroupsSequence, index: Int)

  private def toSeverityConstraintValue(
    severity: RuleGroupSet.Spec.RuleGroupsSequence.Matcher.Severities
  ): SeverityConstraint.Value.Recognized =
    severity match {
      case Severities.members.Debug    => SeverityConstraint.Value.VALUE_DEBUG_OR_UNSPECIFIED
      case Severities.members.Verbose  => SeverityConstraint.Value.VALUE_VERBOSE
      case Severities.members.Info     => SeverityConstraint.Value.VALUE_INFO
      case Severities.members.Warning  => SeverityConstraint.Value.VALUE_WARNING
      case Severities.members.Error    => SeverityConstraint.Value.VALUE_ERROR
      case Severities.members.Critical => SeverityConstraint.Value.VALUE_CRITICAL
    }

  private def toGrpcRuleMatchers(
    matcher: RuleGroupSet.Spec.RuleGroupsSequence.Matcher
  ): Seq[RuleMatcher] =
    (matcher.applications
      .map(
        _.map(name =>
          RuleMatcher(Constraint.ApplicationName(ApplicationNameConstraint(Some(name.value))))
        )
      )
      .toOption
      .toVector ++
      matcher.severities
        .map(
          _.map(severity =>
            RuleMatcher(
              Constraint.Severity(SeverityConstraint(toSeverityConstraintValue(severity)))
            )
          )
        )
        .toOption
        .toVector ++
      matcher.subsystems
        .map(
          _.map(name =>
            RuleMatcher(Constraint.SubsystemName(SubsystemNameConstraint(Some(name.value))))
          )
        )
        .toOption
        .toVector).flatten

  private def toDestinationField(field: DestField): DestinationField =
    field match {
      case DestField.members.Category   => DestinationField.DESTINATION_FIELD_CATEGORY_OR_UNSPECIFIED
      case DestField.members.Classname  => DestinationField.DESTINATION_FIELD_CLASSNAME
      case DestField.members.Methodname => DestinationField.DESTINATION_FIELD_METHODNAME
      case DestField.members.Threadid   => DestinationField.DESTINATION_FIELD_THREADID
      case DestField.members.Severity   => DestinationField.DESTINATION_FIELD_SEVERITY
    }

  private def toFormatStandard(standard: Standard): FormatStandard =
    standard match {
      case members.Strftime  => FormatStandard.FORMAT_STANDARD_STRFTIME_OR_UNSPECIFIED
      case members.Javasdf   => FormatStandard.FORMAT_STANDARD_JAVASDF
      case members.Golang    => FormatStandard.FORMAT_STANDARD_GOLANG
      case members.Secondsts => FormatStandard.FORMAT_STANDARD_SECONDSTS
      case members.Millits   => FormatStandard.FORMAT_STANDARD_MILLITS
      case members.Microts   => FormatStandard.FORMAT_STANDARD_MICROTS
      case members.Nanots    => FormatStandard.FORMAT_STANDARD_NANOTS
    }

  private def toParameters(rule: OrGroup): RuleParameters =
    RuleParameters(
      rule.extract.map(p =>
        RuleParameters.RuleParameters.ExtractParameters(ExtractParameters(rule = Some(p.rule)))
      ) orElse
        rule.jsonExtract.map(p =>
          RuleParameters.RuleParameters.JsonExtractParameters(
            JsonExtractParameters(toDestinationField(p.destField), Some(p.rule))
          )
        ) orElse
        rule.replace.map(p =>
          RuleParameters.RuleParameters.ReplaceParameters(
            ReplaceParameters(
              destinationField = Some(p.destField.value),
              replaceNewVal = Some(p.newValue),
              rule = Some(p.rule)
            )
          )
        ) orElse
        rule.parse.map(p =>
          RuleParameters.RuleParameters.ParseParameters(
            ParseParameters(destinationField = Some(p.destField.value), rule = Some(p.rule))
          )
        ) orElse
        rule.allow.map(p =>
          RuleParameters.RuleParameters.AllowParameters(
            AllowParameters(
              keepBlockedLogs = Some(p.keepBlockedLogs),
              rule = Some(p.rule)
            )
          )
        ) orElse
        rule.block.map(p =>
          RuleParameters.RuleParameters.BlockParameters(
            BlockParameters(keepBlockedLogs = Some(p.keepBlockedLogs), rule = Some(p.rule))
          )
        ) orElse
        rule.extractTimestamp.map(p =>
          RuleParameters.RuleParameters.ExtractTimestampParameters(
            ExtractTimestampParameters(
              standard = toFormatStandard(p.standard),
              format = Some(p.format)
            )
          )
        ) orElse
        rule.removeFields.map(p =>
          RuleParameters.RuleParameters.RemoveFieldsParameters(
            RemoveFieldsParameters(
              fields = p.fields
            )
          )
        ) orElse
        rule.jsonStringify.map(p =>
          RuleParameters.RuleParameters.JsonStringifyParameters(
            JsonStringifyParameters(
              destinationField = Some(p.destField.value),
              deleteSource = Some(p.deleteSource),
            )
          )
        ) orElse
        rule.jsonParse.map(p =>
          RuleParameters.RuleParameters.JsonParseParameters(
            JsonParseParameters(
              destinationField = Some(p.destField.value),
              deleteSource = Some(p.deleteSource),
              overrideDest = Some(p.overrideDest),
              escapedValue = Some(p.escapedValue),
            )
          )
        )
        getOrElse RuleParameters.RuleParameters.Empty
    )

  private def toCreateRule(
    rule: RuleGroupSet.Spec.RuleGroupsSequence.AndSequence.OrGroup,
    index: Int
  ): CreateRule =
    CreateRule(
      name = Some(rule.name.value),
      description = rule.description,
      sourceField = Some(rule.sourceField.value),
      parameters = Some(toParameters(rule)),
      enabled = Some(rule.enabled),
      order = Some(index)
    )

  private def toCreateRuleSubgroups(
    subGroup: RuleGroupSet.Spec.RuleGroupsSequence.AndSequence,
    index: Int
  ): CreateRuleSubgroup =
    CreateRuleSubgroup(
      rules = subGroup.orGroup.zipWithIndex
        .map {
          case (rule, idx) => toCreateRule(rule, idx + 1) // rules-api uses 1-based indexing
        },
      enabled = None,
      order = Some(index)
    )

  def toCreateRuleGroup(
    item: RuleGroupWithIndex,
    startOrder: Option[Int],
    ctx: OperatorContext,
    setName: String
  ): CreateRuleGroupRequest =
    CreateRuleGroupRequest(
      name = Some(item.ruleGroup.name.value),
      description = item.ruleGroup.description.orElse(defaultDescription(ctx, setName)),
      enabled = item.ruleGroup.enabled,
      hidden = item.ruleGroup.hidden,
      creator = Some(Creator),
      ruleMatchers = toGrpcRuleMatchers(item.ruleGroup.matcher),
      ruleSubgroups = item.ruleGroup.andSequence.zipWithIndex
        .map {
          case (subGroup, idx) =>
            toCreateRuleSubgroups(subGroup, idx + 1) // rules-api uses 1-based indexing
        },
      order = Some(
        item.ruleGroup.order
          .orElse(startOrder.map(_ + item.index))
          .getOrElse(item.index)
      )
    )

  private def defaultDescription(ctx: OperatorContext, setName: String): Optional[String] =
    Optional.Present(
      s"Managed by Coralogix Operator (${ctx.namespace.map(ns => s"$ns namespace").getOrElse("cluster")}, $setName rule group set)"
    )
}
