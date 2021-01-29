package com.coralogix.operator.logic.operators.alertset

import com.coralogix.zio.k8s.client.com.coralogix.definitions.alertset.v1.AlertSet
import com.coralogix.zio.k8s.client.model.primitives.{ AlertId, AlertName }
import zio.test.Assertion._
import zio.test._
import zio.test.environment.TestEnvironment

object StatusUpdateSpec extends DefaultRunnableSpec {
  private val emptyStatus = AlertSet.Status()
  private val statusG1G2 = AlertSet.Status(
    alertIds = Some(
      Vector(
        AlertSet.Status.AlertIds(AlertName("g1"), AlertId("id1")),
        AlertSet.Status.AlertIds(AlertName("g2"), AlertId("id2"))
      )
    )
  )

  override def spec: ZSpec[TestEnvironment, Any] =
    suite("AlertSet StatusUpdate")(
      test("can add new name<->id mappings to empty")(
        assert(
          StatusUpdate.runStatusUpdates(
            emptyStatus,
            Vector(
              StatusUpdate.AddRuleGroupMapping(AlertName("g1"), AlertId("id1")),
              StatusUpdate.AddRuleGroupMapping(AlertName("g2"), AlertId("id2"))
            )
          )
        )(equalTo(statusG1G2))
      ),
      test("can add new name<->id mappings and keep existing")(
        assert(
          StatusUpdate.runStatusUpdates(
            statusG1G2,
            Vector(
              StatusUpdate.AddRuleGroupMapping(AlertName("g3"), AlertId("id3"))
            )
          )
        )(
          equalTo(
            AlertSet.Status(
              alertIds = Some(
                Vector(
                  AlertSet.Status.AlertIds(AlertName("g1"), AlertId("id1")),
                  AlertSet.Status.AlertIds(AlertName("g2"), AlertId("id2")),
                  AlertSet.Status.AlertIds(AlertName("g3"), AlertId("id3"))
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
              StatusUpdate.AddRuleGroupMapping(AlertName("g1"), AlertId("id3"))
            )
          )
        )(
          equalTo(
            AlertSet.Status(
              alertIds = Some(
                Vector(
                  AlertSet.Status.AlertIds(AlertName("g2"), AlertId("id2")),
                  AlertSet.Status.AlertIds(AlertName("g1"), AlertId("id3"))
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
              StatusUpdate.DeleteRuleGroupMapping(AlertName("g2"))
            )
          )
        )(
          equalTo(
            AlertSet.Status(
              alertIds = Some(
                Vector(
                  AlertSet.Status.AlertIds(AlertName("g1"), AlertId("id1"))
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
              StatusUpdate.RecordFailure(AlertName("g1"), "failure 1"),
              StatusUpdate.RecordFailure(AlertName("g3"), "failure 3")
            )
          )
        )(
          equalTo(
            statusG1G2.copy(
              failures = Some(
                Vector(
                  AlertSet.Status.Failures(AlertName("g1"), "failure 1"),
                  AlertSet.Status.Failures(AlertName("g3"), "failure 3")
                )
              )
            )
          )
        )
      )
    )
}
