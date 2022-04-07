package com.coralogix.operator.logic.operators.rulegroupset

import com.coralogix.zio.k8s.client.com.coralogix.definitions.rulegroupset.v1.RuleGroupSet
import com.coralogix.zio.k8s.client.com.coralogix.definitions.rulegroupset.v1.RuleGroupSet.Status.GroupIds
import com.coralogix.zio.k8s.client.model.primitives.{ RuleGroupId, RuleGroupName }
import com.coralogix.zio.k8s.client.model.{
  Added,
  FieldSelector,
  K8sNamespace,
  LabelSelector,
  ListResourceVersion,
  Modified,
  PropagationPolicy,
  TypedWatchEvent
}
import com.coralogix.zio.k8s.client.{
  K8sFailure,
  NamespacedResource,
  NamespacedResourceStatus,
  NotFound,
  Resource,
  ResourceDelete,
  ResourceDeleteAll,
  ResourceStatus
}
import com.coralogix.operator.grpc.RuleGroupsServiceClientMock
import com.coralogix.operator.logic.CoralogixOperatorFailure
import com.coralogix.rules.v1.ZioRuleGroupsService.RuleGroupsServiceClient
import com.coralogix.rules.v1.{ CreateRuleGroupRequest, UpdateRuleGroupRequest }
import com.coralogix.rules.v1.RuleMatcher
import com.coralogix.zio.k8s.client.com.coralogix.v1.rulegroupsets
import com.coralogix.zio.k8s.client.com.coralogix.v1.rulegroupsets.RuleGroupSets
import zio._
import zio.clock.Clock
import zio.console.Console
import com.coralogix.zio.k8s.model.pkg.apis.meta.v1.{ DeleteOptions, Status }
import com.coralogix.zio.k8s.operator.{ KubernetesFailure, OperatorError, OperatorFailure }
import zio.duration._
import zio.logging.{ LogLevel, Logging }
import zio.stm.TMap
import zio.stream.Stream
import zio.test.Assertion._
import zio.test._
import zio.test.environment.TestEnvironment
import zio.test.mock.Expectation

object RuleGroupSetOperatorSpec extends DefaultRunnableSpec with RuleGroupSetOperatorTestData {

  override def spec: ZSpec[TestEnvironment, Any] =
    suite("RuleGroupSet Operator")(
      testM("adding a set with a single unassigned rule group") {
        testOperator(
          Seq(
            Added[RuleGroupSet](testSet1)
          ),
          RuleGroupsServiceClientMock.CreateRuleGroup(
            hasField[CreateRuleGroupRequest, Option[String]](
              "name",
              _.name,
              isSome(equalTo("group1"))
            ) &&
              hasField[CreateRuleGroupRequest, Seq[RuleMatcher]](
                "ruleMatchers",
                _.ruleMatchers,
                isNonEmpty
              ) &&
              hasField[CreateRuleGroupRequest, Seq[_]](
                "ruleSubgroups",
                _.ruleSubgroups,
                isNonEmpty
              ) &&
              hasField[CreateRuleGroupRequest, Option[Int]]("order", _.order, isSome(equalTo(1))),
            Expectation.value(testSet1Group1Response)
          )
        ) {
          case (result, statusMap) =>
            assertM(statusMap.get("set1").commit)(
              isSome(
                equalTo(
                  RuleGroupSet.Status(
                    Some(Vector(GroupIds(RuleGroupName("group1"), RuleGroupId("group1-id")))),
                    lastUploadedGeneration = Some(0L)
                  )
                )
              )
            )
        }
      },
      testM("modifying a set with with unchanged generation does not do anything") {
        testOperator(
          Seq(
            Modified[RuleGroupSet](testSet1)
          ),
          RuleGroupsServiceClientMock.failing
        ) {
          case (result, statusMap) =>
            assertM(statusMap.isEmpty.commit)(isTrue)
        }
      },
      testM("modifying a set with a single already assigned rule group") {
        testOperator(
          Seq(
            Modified[RuleGroupSet](
              testSet1.copy(
                metadata = testSet1.metadata.map(_.copy(generation = Some(1L))),
                status = Some(
                  RuleGroupSet.Status(
                    Some(
                      Vector(
                        GroupIds(RuleGroupName("group1"), RuleGroupId("group1-id"))
                      )
                    )
                  )
                )
              )
            )
          ),
          RuleGroupsServiceClientMock.UpdateRuleGroup(
            hasField[UpdateRuleGroupRequest, Option[String]](
              "groupId",
              _.groupId,
              isSome(equalTo("group1-id"))
            ) &&
              hasField[UpdateRuleGroupRequest, Option[CreateRuleGroupRequest]](
                "ruleMatchers",
                _.ruleGroup,
                isSome
              ) &&
              hasField[UpdateRuleGroupRequest, Option[Int]](
                "order",
                _.ruleGroup.flatMap(_.order),
                isSome(equalTo(1))
              ),
            Expectation.value(testSet1Group1UpdateResponse)
          )
        ) {
          case (result, statusMap) =>
            assertM(statusMap.get("set1").commit)(
              isSome(
                equalTo(
                  RuleGroupSet.Status(
                    Some(Vector(GroupIds(RuleGroupName("group1"), RuleGroupId("group1-id")))),
                    lastUploadedGeneration = Some(1L)
                  )
                )
              )
            )
        }
      },
      testM("adding a group to a set with an already assigned one") {
        testOperator(
          Seq(
            Modified[RuleGroupSet](
              testSet2.copy(
                metadata = testSet2.metadata.map(_.copy(generation = Some(1L))),
                status = Some(
                  RuleGroupSet.Status(
                    Some(
                      Vector(
                        GroupIds(RuleGroupName("group1"), RuleGroupId("group1-id"))
                      )
                    )
                  )
                )
              )
            )
          ),
          RuleGroupsServiceClientMock.UpdateRuleGroup(
            hasField[UpdateRuleGroupRequest, Option[String]](
              "groupId",
              _.groupId,
              isSome(equalTo("group1-id"))
            ) &&
              hasField[UpdateRuleGroupRequest, Option[CreateRuleGroupRequest]](
                "ruleMatchers",
                _.ruleGroup,
                isSome(hasField("name", _.name, isSome(equalTo("group1"))))
              ),
            Expectation.value(testSet2UpdateGroup1Response)
          ) ++
            RuleGroupsServiceClientMock.CreateRuleGroup(
              hasField[CreateRuleGroupRequest, Option[String]](
                "name",
                _.name,
                isSome(equalTo("group2"))
              ) &&
                hasField[CreateRuleGroupRequest, Seq[RuleMatcher]](
                  "ruleMatchers",
                  _.ruleMatchers,
                  isNonEmpty
                ) &&
                hasField("ruleSubgroups", _.ruleSubgroups, isNonEmpty),
              Expectation.value(testSet2CreateGroup2Response)
            )
        ) {
          case (result, statusMap) =>
            assertM(statusMap.get("set2").commit)(
              isSome(
                equalTo(
                  RuleGroupSet.Status(
                    Some(
                      Vector(
                        GroupIds(
                          RuleGroupName("group1"),
                          RuleGroupId("group1-id")
                        ), // kept from input
                        GroupIds(RuleGroupName("group2"), RuleGroupId("group2-id"))
                      )
                    ), // added newly created
                    lastUploadedGeneration = Some(1L)
                  )
                )
              )
            )
        }
      }
    )

  private def testOperator(
    events: Seq[TypedWatchEvent[RuleGroupSet]],
    grpc: ULayer[RuleGroupsServiceClient]
  )(
    f: (Cause[Nothing], TMap[String, RuleGroupSet.Status]) => UIO[TestResult]
  ): ZIO[Console with Clock, Nothing, TestResult] =
    TMap.make[String, RuleGroupSet.Status]().commit.flatMap { statusMap =>
      val logging = Logging.console(LogLevel.Debug)
      val client =
        new Resource[RuleGroupSet]
          with ResourceDelete[RuleGroupSet, Status] with ResourceDeleteAll[RuleGroupSet] {
          override def getAll(
            namespace: Option[K8sNamespace],
            chunkSize: Int,
            fieldSelector: Option[FieldSelector],
            labelSelector: Option[LabelSelector],
            resourceVersion: ListResourceVersion
          ): Stream[K8sFailure, RuleGroupSet] = ???

          override def watch(
            namespace: Option[K8sNamespace],
            resourceVersion: Option[String],
            fieldSelector: Option[FieldSelector],
            labelSelector: Option[LabelSelector]
          ): Stream[K8sFailure, TypedWatchEvent[RuleGroupSet]] =
            Stream.fromIterable(events)

          override def get(
            name: String,
            namespace: Option[K8sNamespace]
          ): IO[K8sFailure, RuleGroupSet] = ???

          override def create(
            newResource: RuleGroupSet,
            namespace: Option[K8sNamespace],
            dryRun: Boolean
          ): IO[K8sFailure, RuleGroupSet] = ???

          override def replace(
            name: String,
            updatedResource: RuleGroupSet,
            namespace: Option[K8sNamespace],
            dryRun: Boolean
          ): IO[K8sFailure, RuleGroupSet] = ???

          override def delete(
            name: String,
            deleteOptions: DeleteOptions,
            namespace: Option[K8sNamespace],
            dryRun: Boolean,
            gracePeriod: Option[Duration],
            propagationPolicy: Option[PropagationPolicy]
          ): IO[K8sFailure, Status] = ???

          override def deleteAll(
            deleteOptions: DeleteOptions,
            namespace: Option[K8sNamespace],
            dryRun: Boolean,
            gracePeriod: Option[Duration],
            propagationPolicy: Option[PropagationPolicy],
            fieldSelector: Option[FieldSelector],
            labelSelector: Option[LabelSelector]
          ): IO[K8sFailure, Status] = ???
        }

      val statusClient =
        new ResourceStatus[RuleGroupSet.Status, RuleGroupSet] {

          override def getStatus(
            name: String,
            namespace: Option[K8sNamespace]
          ): IO[K8sFailure, RuleGroupSet] =
            ???

          override def replaceStatus(
            of: RuleGroupSet,
            updatedStatus: RuleGroupSet.Status,
            namespace: Option[K8sNamespace],
            dryRun: Boolean
          ): IO[K8sFailure, RuleGroupSet] =
            for {
              name <- of.getName
              _    <- statusMap.put(name, updatedStatus).commit
            } yield of.copy(status = Some(updatedStatus))

        }

      val ruleGroupSets =
        ZLayer.succeed[RuleGroupSets.Service](new RuleGroupSets.Live(client, statusClient))

      val test = for {
        op         <- RuleGroupSetOperator.forTest().provideLayer(logging ++ ruleGroupSets)
        fiber      <- op.start()
        result     <- fiber.join.cause
        testResult <- f(result, statusMap)
      } yield testResult

      test.provideSomeLayer[Console with Clock](logging ++ ruleGroupSets ++ grpc)
    }
}
