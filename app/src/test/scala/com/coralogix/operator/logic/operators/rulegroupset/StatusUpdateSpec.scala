package com.coralogix.operator.logic.operators.rulegroupset

import zio.k8s.client.com.coralogix.definitions.rulegroupset.v1.Rulegroupset
import zio.k8s.client.model.primitives.{ RuleGroupId, RuleGroupName }
import zio.test.environment.TestEnvironment
import zio.test._
import zio.test.Assertion._

object StatusUpdateSpec extends DefaultRunnableSpec {
  private val emptyStatus = Rulegroupset.Status()
  private val statusG1G2 = Rulegroupset.Status(
    groupIds = Some(
      Vector(
        Rulegroupset.Status.GroupIds(RuleGroupName("g1"), RuleGroupId("id1")),
        Rulegroupset.Status.GroupIds(RuleGroupName("g2"), RuleGroupId("id2"))
      )
    )
  )

  override def spec: ZSpec[TestEnvironment, Any] =
    suite("StatusUpdate")(
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
            Rulegroupset.Status(
              groupIds = Some(
                Vector(
                  Rulegroupset.Status.GroupIds(RuleGroupName("g1"), RuleGroupId("id1")),
                  Rulegroupset.Status.GroupIds(RuleGroupName("g2"), RuleGroupId("id2")),
                  Rulegroupset.Status.GroupIds(RuleGroupName("g3"), RuleGroupId("id3"))
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
            Rulegroupset.Status(
              groupIds = Some(
                Vector(
                  Rulegroupset.Status.GroupIds(RuleGroupName("g2"), RuleGroupId("id2")),
                  Rulegroupset.Status.GroupIds(RuleGroupName("g1"), RuleGroupId("id3"))
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
            Rulegroupset.Status(
              groupIds = Some(
                Vector(
                  Rulegroupset.Status.GroupIds(RuleGroupName("g1"), RuleGroupId("id1"))
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
      )
    )
}
