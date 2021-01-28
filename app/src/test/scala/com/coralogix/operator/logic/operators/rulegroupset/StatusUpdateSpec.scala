package com.coralogix.operator.logic.operators.rulegroupset

import com.coralogix.zio.k8s.client.com.coralogix.definitions.rulegroupset.v1.RuleGroupSet
import com.coralogix.zio.k8s.client.model.primitives.{ RuleGroupId, RuleGroupName }
import zio.test.environment.TestEnvironment
import zio.test._
import zio.test.Assertion._

object StatusUpdateSpec extends DefaultRunnableSpec {
  private val emptyStatus = RuleGroupSet.Status()
  private val statusG1G2 = RuleGroupSet.Status(
    groupIds = Some(
      Vector(
        RuleGroupSet.Status.GroupIds(RuleGroupName("g1"), RuleGroupId("id1")),
        RuleGroupSet.Status.GroupIds(RuleGroupName("g2"), RuleGroupId("id2"))
      )
    )
  )

  override def spec: ZSpec[TestEnvironment, Any] =
    suite("RuleGroupSet StatusUpdate")(
      test("can add new name<->id mappings to empty")(
        assert(
          StatusUpdate.runStatusUpdates(
            emptyStatus,
            Vector(
              StatusUpdate.AddRuleGroupMapping(RuleGroupName("g1"), RuleGroupId("id1")),
              StatusUpdate.AddRuleGroupMapping(RuleGroupName("g2"), RuleGroupId("id2"))
            )
          )
        )(equalTo(statusG1G2))
      ),
      test("can add new name<->id mappings and keep existing")(
        assert(
          StatusUpdate.runStatusUpdates(
            statusG1G2,
            Vector(
              StatusUpdate.AddRuleGroupMapping(RuleGroupName("g3"), RuleGroupId("id3"))
            )
          )
        )(
          equalTo(
            RuleGroupSet.Status(
              groupIds = Some(
                Vector(
                  RuleGroupSet.Status.GroupIds(RuleGroupName("g1"), RuleGroupId("id1")),
                  RuleGroupSet.Status.GroupIds(RuleGroupName("g2"), RuleGroupId("id2")),
                  RuleGroupSet.Status.GroupIds(RuleGroupName("g3"), RuleGroupId("id3"))
                )
              )
            )
          )
        )
      ),
      test("can replace existing name<->id mappings")(
        assert(
          StatusUpdate.runStatusUpdates(
            statusG1G2,
            Vector(
              StatusUpdate.AddRuleGroupMapping(RuleGroupName("g1"), RuleGroupId("id3"))
            )
          )
        )(
          equalTo(
            RuleGroupSet.Status(
              groupIds = Some(
                Vector(
                  RuleGroupSet.Status.GroupIds(RuleGroupName("g2"), RuleGroupId("id2")),
                  RuleGroupSet.Status.GroupIds(RuleGroupName("g1"), RuleGroupId("id3"))
                )
              )
            )
          )
        )
      ),
      test("can remove existing name<->id mappings")(
        assert(
          StatusUpdate.runStatusUpdates(
            statusG1G2,
            Vector(
              StatusUpdate.DeleteRuleGroupMapping(RuleGroupName("g2"))
            )
          )
        )(
          equalTo(
            RuleGroupSet.Status(
              groupIds = Some(
                Vector(
                  RuleGroupSet.Status.GroupIds(RuleGroupName("g1"), RuleGroupId("id1"))
                )
              )
            )
          )
        )
      ),
      test("can update last updated generation")(
        assert(
          StatusUpdate.runStatusUpdates(
            statusG1G2,
            Vector(StatusUpdate.UpdateLastUploadedGeneration(10L))
          )
        )(
          equalTo(statusG1G2.copy(lastUploadedGeneration = Some(10L)))
        )
      ),
      test("can add failures")(
        assert(
          StatusUpdate.runStatusUpdates(
            statusG1G2,
            Vector(
              StatusUpdate.ClearFailures,
              StatusUpdate.RecordFailure(RuleGroupName("g1"), "failure 1"),
              StatusUpdate.RecordFailure(RuleGroupName("g3"), "failure 3")
            )
          )
        )(
          equalTo(
            statusG1G2.copy(
              failures = Some(
                Vector(
                  RuleGroupSet.Status.Failures(RuleGroupName("g1"), "failure 1"),
                  RuleGroupSet.Status.Failures(RuleGroupName("g3"), "failure 3")
                )
              )
            )
          )
        )
      )
    )
}
