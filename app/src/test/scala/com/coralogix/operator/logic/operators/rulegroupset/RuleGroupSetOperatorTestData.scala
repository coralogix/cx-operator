package com.coralogix.operator.logic.operators.rulegroupset

import com.coralogix.rules.v1.RuleMatcher.Constraint
import com.coralogix.rules.v1._
import com.coralogix.zio.k8s.client.com.coralogix.definitions.rulegroupset.v1.RuleGroupSet
import com.coralogix.zio.k8s.client.com.coralogix.definitions.rulegroupset.v1.RuleGroupSet.Spec.RuleGroupsSequence
import com.coralogix.zio.k8s.client.com.coralogix.definitions.rulegroupset.v1.RuleGroupSet.Spec.RuleGroupsSequence.AndSequence.OrGroup
import com.coralogix.zio.k8s.client.com.coralogix.definitions.rulegroupset.v1.RuleGroupSet.Spec.RuleGroupsSequence.AndSequence.OrGroup.Allow
import com.coralogix.zio.k8s.client.com.coralogix.definitions.rulegroupset.v1.RuleGroupSet.Spec.RuleGroupsSequence.Matcher.Severities
import com.coralogix.zio.k8s.client.com.coralogix.definitions.rulegroupset.v1.RuleGroupSet.Spec.RuleGroupsSequence.{
  AndSequence,
  Matcher
}
import com.coralogix.zio.k8s.client.model.primitives.{
  ApplicationName,
  FieldName,
  RuleGroupName,
  RuleName
}
import com.coralogix.zio.k8s.model.pkg.apis.meta.v1.ObjectMeta

trait RuleGroupSetOperatorTestData {

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

  val testSet1 = RuleGroupSet(
    metadata = Some(ObjectMeta(name = Some("set1"))),
    spec = RuleGroupSet.Spec(
      startOrder = None,
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

  val testSet2 = RuleGroupSet(
    metadata = Some(ObjectMeta(name = Some("set2"))),
    spec = RuleGroupSet.Spec(
      startOrder = None,
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
          RuleMatcher(
            Constraint.Severity(SeverityConstraint(SeverityConstraint.Value.VALUE_CRITICAL))
          ),
          RuleMatcher(Constraint.Severity(SeverityConstraint(SeverityConstraint.Value.VALUE_ERROR)))
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
