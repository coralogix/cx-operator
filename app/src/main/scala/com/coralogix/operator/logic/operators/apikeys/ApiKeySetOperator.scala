package com.coralogix.operator.logic.operators.apikeys

import com.coralogix.operator.logic._
import com.coralogix.operator.logic.aspects.metered
import com.coralogix.operator.monitoring.OperatorMetrics
import com.coralogix.users.v2beta1.ZioApiKeysService.ApiKeysServiceClient
import com.coralogix.users.v2beta1.{ ApiKeyType, CreateApiKeyRequest }
import com.coralogix.zio.k8s.client.com.coralogix.definitions.apikeyset.v1.ApiKeySet
import com.coralogix.zio.k8s.client.com.coralogix.v1.apikeysets.ApiKeySets
import com.coralogix.zio.k8s.client.model._
import com.coralogix.zio.k8s.operator.Operator.{ EventProcessor, OperatorContext }
import com.coralogix.zio.k8s.operator.OperatorLogging.logFailure
import com.coralogix.zio.k8s.operator.aspects.logEvents
import com.coralogix.zio.k8s.operator.{ KubernetesFailure, Operator }
import zio.clock.Clock
import zio.logging.{ log, Logging }
import zio.{ Cause, ZIO }

object ApiKeySetOperator {
  private def eventProcessor(): EventProcessor[
    Logging with ApiKeySets with ApiKeysServiceClient,
    CoralogixOperatorFailure,
    ApiKeySet
  ] =
    (ctx, event) =>
      event match {
        case Reseted => ZIO.unit
        case Added(item) => // TODO copy paste from alerts
          if (
            item.generation == 0L || // new item
            !item.status
              .flatMap(_.lastUploadedGeneration)
              .contains(item.generation) // already synchronized
          ) for {
            name    <- item.getName.mapError(KubernetesFailure.apply)
            updates <- upsertApiKeys(ctx, name, item.spec.apikeys.toSet)
            _ <- applyStatusUpdates(
                   ctx,
                   item,
                   StatusUpdate.ClearFailures +:
                     StatusUpdate.UpdateLastUploadedGeneration(item.generation) +:
                     updates
                 )
          } yield ()
          else
            log.debug(
              s"Alert set '${item.metadata.flatMap(_.name).getOrElse("")}' with generation ${item.generation} is already added"
            )
        case Modified(item) =>
          withExpectedStatus(item) { status =>
            if (status.lastUploadedGeneration.getOrElse(0L) < item.generation) {
              // TODO not sure how we are able to distinguish already generated keys for generation/mark for "regeneration"
              //   it might need some changes in protobuf, like addition of "version" which would just track if you have changes it or not
              val apiKeys: Set[ApiKeySet.Spec.Apikeys] = ???
              for {
                name   <- item.getName.mapError(KubernetesFailure.apply)
                upsert <- upsertApiKeys(ctx, name, apiKeys)
                _ <- applyStatusUpdates(
                       ctx,
                       item,
                       StatusUpdate.ClearFailures +:
                         StatusUpdate.UpdateLastUploadedGeneration(item.generation) +:
                         upsert
                     )
              } yield ()
            } else
              log.debug(
                s"Skipping modification of apikey set '${item.metadata.flatMap(_.name).getOrElse("")}' with generation ${item.generation}"
              )
          }
        case Deleted(item) =>
          log
            .debug(
              "There was try to delete api_key, skipping it over as we have no support for api key deletion"
            )
            .unit // TODO
      }

  private def upsertApiKeys(
    ctx: OperatorContext,
    name: String,
    apiKeys: Set[ApiKeySet.Spec.Apikeys]
  ): ZIO[Logging with ApiKeysServiceClient, Nothing, Vector[StatusUpdate]] =
    ZIO
      .foreachPar(apiKeys.toVector) { apiKey =>
        {
          // TODO this should not be here
          val request: CreateApiKeyRequest = ???
          val apiKeyType
            : ApiKeyType = // TODO move down and tak customerApiKey into account (even thought it is not implemented yet)
            request.apiKeyData
              .flatMap(_.apiKey.userApiKey.map(_.keyType))
              .getOrElse(ApiKeyType.API_KEY_TYPE_UNSPECIFIED)

          for {
            // TODO tweak this log
            _ <- log.info(s"Upserting api key '${request.apiKeyData}'")
            // TODO cal use deserializer here: grpc-deps/target/scala-2.13/src_managed/main/com/coralogix/users/v2beta1/ApiKeyCRDDeserializer.scala
            //    it might need some changes on crdgen side of things as I am not sure what exact json comes here
            //          request <-
            //            ZIO.fromEither(toCreateAlert(apiKey, ctx, name)).mapError(CustomResourceError.apply)
            response <- ApiKeysServiceClient
                          .createApiKey(request)
                          .mapError(GrpcFailure.apply)
            // TODO tweak this log
            _ <-
              log.trace(
                s"ApiKeys API response for creating api key '${apiKey.apiKey.map(_.name)}': $response"
              )
            tokenValue <-
              ZIO.fromEither(
                response.apiKey
                  .flatMap(_.serverGenerated.map(_.tokenValue))
                  .toRight(UndefinedGrpcField("CreateApiKeyResponse.server_generated.token_value"))
              )
          } yield StatusUpdate.CreateUserApiKey(apiKeyType, "TODO - userid ?", tokenValue)
        }
          .catchAll { (failure: CoralogixOperatorFailure) =>
            logFailure(
              s"Failed to create alert '${apiKey.apiKey.map(_.name)}'",
              Cause.fail(failure)
            ).as(
              StatusUpdate.RecordFailure(
                tokenValue = None, // TODO
                CoralogixOperatorFailure.toFailureString(failure)
              )
            )
          }
      }

  private def withExpectedStatus[R <: Logging, E](
    apiKeySet: ApiKeySet
  )(f: ApiKeySet.Status => ZIO[R, E, Unit]): ZIO[R, E, Unit] =
    apiKeySet.status match {
      case Optional.Present(status) =>
        f(status)
      case Optional.Absent =>
        log.warn(
          s"Api key set '${apiKeySet.metadata.flatMap(_.name).getOrElse("")}' has no status information"
        )
    }

  private def applyStatusUpdates(
    ctx: OperatorContext,
    resource: ApiKeySet,
    updates: Vector[StatusUpdate]
  ): ZIO[Logging with ApiKeySets, KubernetesFailure, Unit] = ???

  def forNamespace(
    namespace: K8sNamespace,
    buffer: Int,
    metrics: OperatorMetrics
  ): ZIO[ApiKeySets, Nothing, Operator[
    Clock with Logging with ApiKeySets with ApiKeysServiceClient,
    CoralogixOperatorFailure,
    ApiKeySet
  ]] =
    ZIO.service[ApiKeySets.Service].flatMap { apiKeySets =>
      Operator
        .namespaced(
          eventProcessor() @@ logEvents @@ metered(metrics)
        )(Some(namespace), buffer)
        .provide(apiKeySets.asGeneric)
    }

  def forAllNamespaces(
    buffer: Int,
    metrics: OperatorMetrics
  ): ZIO[ApiKeySets, Nothing, Operator[
    Clock with Logging with ApiKeySets with ApiKeysServiceClient,
    CoralogixOperatorFailure,
    ApiKeySet
  ]] =
    ZIO.service[ApiKeySets.Service].flatMap { apiKeySets =>
      Operator
        .namespaced(
          eventProcessor() @@ logEvents @@ metered(metrics)
        )(None, buffer)
        .provide(apiKeySets.asGeneric)
    }

  def forTest(): ZIO[ApiKeySets, Nothing, Operator[
    Logging with ApiKeySets with ApiKeysServiceClient,
    CoralogixOperatorFailure,
    ApiKeySet
  ]] =
    ZIO.service[ApiKeySets.Service].flatMap { apiKeySets =>
      Operator
        .namespaced(eventProcessor())(Some(K8sNamespace("default")), 256)
        .provide(apiKeySets.asGeneric)
    }
}
