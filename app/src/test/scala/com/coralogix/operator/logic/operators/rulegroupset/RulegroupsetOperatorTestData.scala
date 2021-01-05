package com.coralogix.operator.logic.operators.rulegroupset

import com.coralogix.operator.client.definitions.rulegroupset.v1.Rulegroupset
import com.coralogix.operator.client.definitions.rulegroupset.v1.Rulegroupset.Spec.RuleGroupsSequence
import com.coralogix.operator.client.definitions.rulegroupset.v1.Rulegroupset.Spec.RuleGroupsSequence.AndSequence.OrGroup
import com.coralogix.operator.client.definitions.rulegroupset.v1.Rulegroupset.Spec.RuleGroupsSequence.AndSequence.OrGroup.Allow
import com.coralogix.operator.client.definitions.rulegroupset.v1.Rulegroupset.Spec.RuleGroupsSequence.Matcher.Severities
import com.coralogix.operator.client.definitions.rulegroupset.v1.Rulegroupset.Spec.RuleGroupsSequence.{
  AndSequence,
  Matcher
}
import com.coralogix.operator.client.model.generated.apimachinery.v1.ObjectMeta
import com.coralogix.operator.client.model.primitives.{
  ApplicationName,
  FieldName,
  RuleGroupName,
  RuleName
}
import com.coralogix.rules.grpc.external.v1.RuleGroupsService.{
  CreateRuleGroupResponse,
  UpdateRuleGroupResponse
}
import com.coralogix.rules.grpc.external.v1.RuleMatcher.Constraint
import com.coralogix.rules.grpc.external.v1._
import io.circe.Json

trait RulegroupsetOperatorTestData {

  val ruleGroup1 = RuleGroupsSequence(
    name = RuleGroupName("group1"),
    matcher = Matcher(
      applications = Some(Vector(ApplicationName("app1"))),
      subsystems = None,
      severities = None
    ),
    andSequence = Vector(
      AndSequence(orGroup =
        Vector(
          OrGroup(
            name = RuleName("rule1"),
            enabled = true,
            sourceField = FieldName("field1"),
            allow = Some(Allow(keepBlockedLogs = false, rule = "rule"))
          )
        )
      )
    )
  )

  val ruleGroup2 = RuleGroupsSequence(
    name = RuleGroupName("group2"),
    matcher = Matcher(
      applications = Some(Vector(ApplicationName("app1"))),
      subsystems = None,
      severities = Some(Vector(Severities.Error, Severities.Critical))
    ),
    andSequence = Vector(
      AndSequence(orGroup =
        Vector(
          OrGroup(
            name = RuleName("rule10"),
            enabled = true,
            sourceField = FieldName("field1"),
            allow = Some(Allow(keepBlockedLogs = false, rule = "rule"))
          ),
          OrGroup(
            name = RuleName("rule11"),
            enabled = true,
            sourceField = FieldName("field1"),
            allow = Some(Allow(keepBlockedLogs = false, rule = "rule"))
          )
        )
      ),
      AndSequence(orGroup =
        Vector(
          OrGroup(
            name = RuleName("rule12"),
            enabled = true,
            sourceField = FieldName("field1"),
            allow = Some(Allow(keepBlockedLogs = false, rule = "rule"))
          )
        )
      )
    )
  )

  val testSet1 = Rulegroupset(
    metadata = Some(ObjectMeta(name = Some("set1"))),
    spec = Rulegroupset.Spec(
      Vector(
        ruleGroup1
      )
    )
  )

  val testSet1Group1Response = CreateRuleGroupResponse(ruleGroup =
    Some(
      RuleGroup(
        id = Some("group1-id"),
        name = Some("group1"),
        ruleMatchers = Seq(
          RuleMatcher(
            Constraint.ApplicationName(ApplicationNameConstraint(Some("app1")))
          )
        ),
        ruleSubgroups = Seq(
          RuleSubgroup(
            id = Some("group1-subgroup1-id"),
            rules = Seq(
              Rule(
                id = Some("rule1-id"),
                name = Some("rule1"),
                enabled = Some(true),
                parameters = Some(
                  RuleParameters(
                    RuleParameters.RuleParameters.AllowParameters(
                      AllowParameters(Some(false), Some("rule"))
                    )
                  )
                )
              )
            )
          )
        )
      )
    )
  )
  val testSet1Group1UpdateResponse = UpdateRuleGroupResponse(ruleGroup =
    Some(
      RuleGroup(
        id = Some("group1-id"),
        name = Some("group1"),
        ruleMatchers = Seq(
          RuleMatcher(
            Constraint.ApplicationName(ApplicationNameConstraint(Some("app1")))
          )
        ),
        ruleSubgroups = Seq(
          RuleSubgroup(
            id = Some("group1-subgroup1-id"),
            rules = Seq(
              Rule(
                id = Some("rule1-id"),
                name = Some("rule1"),
                enabled = Some(true),
                parameters = Some(
                  RuleParameters(
                    RuleParameters.RuleParameters.AllowParameters(
                      AllowParameters(Some(false), Some("rule"))
                    )
                  )
                )
              )
            )
          )
        )
      )
    )
  )

  val testSet2 = Rulegroupset(
    metadata = Some(ObjectMeta(name = Some("set2"))),
    spec = Rulegroupset.Spec(
      Vector(
        ruleGroup1,
        ruleGroup2
      )
    )
  )

  val testSet2UpdateGroup1Response = testSet1Group1UpdateResponse
  val testSet2CreateGroup2Response = CreateRuleGroupResponse(ruleGroup =
    Some(
      RuleGroup(
        id = Some("group2-id"),
        name = Some("group2"),
        ruleMatchers = Seq(
          RuleMatcher(Constraint.ApplicationName(ApplicationNameConstraint(Some("app1")))),
          RuleMatcher(Constraint.Severity(SeverityConstraint(SeverityConstraint.Value.CRITICAL))),
          RuleMatcher(Constraint.Severity(SeverityConstraint(SeverityConstraint.Value.ERROR)))
        ),
        ruleSubgroups = Seq(
          RuleSubgroup(
            id = Some("group2-subgroup1-id"),
            rules = Seq(
              Rule(
                id = Some("rule10-id"),
                name = Some("rule10"),
                enabled = Some(true),
                parameters = Some(
                  RuleParameters(
                    RuleParameters.RuleParameters.AllowParameters(
                      AllowParameters(Some(false), Some("rule"))
                    )
                  )
                )
              ),
              Rule(
                id = Some("rule11-id"),
                name = Some("rule11"),
                enabled = Some(true),
                parameters = Some(
                  RuleParameters(
                    RuleParameters.RuleParameters.AllowParameters(
                      AllowParameters(Some(false), Some("rule"))
                    )
                  )
                )
              )
            )
          ),
          RuleSubgroup(
            id = Some("group2-subgroup2-id"),
            rules = Seq(
              Rule(
                id = Some("rule12-id"),
                name = Some("rule12"),
                enabled = Some(true),
                parameters = Some(
                  RuleParameters(
                    RuleParameters.RuleParameters.AllowParameters(
                      AllowParameters(Some(false), Some("rule"))
                    )
                  )
                )
              )
            )
          )
        )
      )
    )
  )
}
