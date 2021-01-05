package com.coralogix.operator.logic.operators.rulegroupset

import com.coralogix.operator.client.definitions.rulegroupset.v1.Rulegroupset
import com.coralogix.operator.client.definitions.rulegroupset.v1.Rulegroupset.Status.GroupIds
import com.coralogix.operator.client.model.generated.apimachinery.v1.{ DeleteOptions, Status }
import com.coralogix.operator.client.model.primitives.{ RuleGroupId, RuleGroupName }
import com.coralogix.operator.client.model.{ Added, K8sNamespace, Modified, TypedWatchEvent }
import com.coralogix.operator.client.{ K8sFailure, NamespacedResource, Resource }
import com.coralogix.operator.grpc.RuleGroupsServiceClientMock
import com.coralogix.rules.grpc.external.v1.RuleGroupsService.ZioRuleGroupsService.RuleGroupsServiceClient
import com.coralogix.rules.grpc.external.v1.RuleGroupsService.{
  CreateRuleGroupRequest,
  UpdateRuleGroupRequest
}
import com.coralogix.rules.grpc.external.v1.RuleMatcher
import zio._
import zio.clock.Clock
import zio.console.Console
import zio.logging.{ LogLevel, Logging }
import zio.stm.TMap
import zio.stream.Stream
import zio.test.Assertion._
import zio.test._
import zio.test.environment.TestEnvironment
import zio.test.mock.Expectation

object RulegroupsetOperatorSpec extends DefaultRunnableSpec with RulegroupsetOperatorTestData {

  override def spec: ZSpec[TestEnvironment, Any] =
    suite("Rulegroupset Operator")(
      testM("adding a set with a single unassigned rule group") {
        testOperator(
          Seq(
            Added[Rulegroupset.Status, Rulegroupset](testSet1)
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
              hasField("ruleSubgroups", _.ruleSubgroups, isNonEmpty),
            Expectation.value(testSet1Group1Response)
          )
        ) {
          case (result, statusMap) =>
            assertM(statusMap.get("set1").commit)(
              isSome(
                equalTo(
                  Rulegroupset.Status(
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
            Modified[Rulegroupset.Status, Rulegroupset](testSet1)
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
            Modified[Rulegroupset.Status, Rulegroupset](
              testSet1.copy(
                metadata = Some(testSet1.metadata.get.copy(generation = Some(1L))),
                status = Some(
                  Rulegroupset.Status(
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
              ),
            Expectation.value(testSet1Group1UpdateResponse)
          )
        ) {
          case (result, statusMap) =>
            assertM(statusMap.get("set1").commit)(
              isSome(
                equalTo(
                  Rulegroupset.Status(
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
            Modified[Rulegroupset.Status, Rulegroupset](
              testSet2.copy(
                metadata = Some(testSet2.metadata.get.copy(generation = Some(1L))),
                status = Some(
                  Rulegroupset.Status(
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
                  Rulegroupset.Status(
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
    events: Seq[TypedWatchEvent[Rulegroupset]],
    grpc: ULayer[RuleGroupsServiceClient]
  )(
    f: (Cause[Nothing], TMap[String, Rulegroupset.Status]) => UIO[TestResult]
  ): ZIO[Console with Clock, Nothing, TestResult] =
    TMap.make[String, Rulegroupset.Status]().commit.flatMap { statusMap =>
      val logging = Logging.console(LogLevel.Debug)
      val client = ZLayer.succeed(
        new NamespacedResource(
          new Resource[Rulegroupset.Status, Rulegroupset] {
            override def watch(
              namespace: Option[K8sNamespace],
              resourceVersion: Option[String]
            ): Stream[K8sFailure, TypedWatchEvent[Rulegroupset]] =
              Stream.fromIterable(events)

            override def getAll(
              namespace: Option[K8sNamespace],
              chunkSize: Int
            ): Stream[K8sFailure, Rulegroupset] = ???

            override def get(
              name: String,
              namespace: Option[K8sNamespace]
            ): IO[K8sFailure, Rulegroupset] = ???

            override def create(
              newResource: Rulegroupset,
              namespace: Option[K8sNamespace],
              dryRun: Boolean
            ): IO[K8sFailure, Rulegroupset] = ???

            override def replace(
              name: String,
              updatedResource: Rulegroupset,
              namespace: Option[K8sNamespace],
              dryRun: Boolean
            ): IO[K8sFailure, Rulegroupset] = ???

            override def replaceStatus(
              of: Rulegroupset,
              updatedStatus: Rulegroupset.Status,
              namespace: Option[K8sNamespace],
              dryRun: Boolean
            ): IO[K8sFailure, Rulegroupset] =
              for {
                name <- of.getName
                _    <- statusMap.put(name, updatedStatus).commit
              } yield of.copy(status = Some(updatedStatus))

            override def delete(
              name: String,
              deleteOptions: DeleteOptions,
              namespace: Option[K8sNamespace],
              dryRun: Boolean
            ): IO[K8sFailure, Status] = ???
          }
        )
      )

      val test = for {
        op         <- RulegroupsetOperator.forTest().provideLayer(logging ++ client)
        fiber      <- op.start()
        result     <- fiber.join.cause
        testResult <- f(result, statusMap)
      } yield testResult

      test.provideSomeLayer[Console with Clock](logging ++ client ++ grpc)
    }
}
