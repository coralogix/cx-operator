package com.coralogix.operator

import com.coralogix.alerts.v1.alert_service.ZioAlertService.AlertServiceClient
import com.coralogix.operator.config.{ GrpcClientConfig, GrpcConfig }
import com.coralogix.operator.monitoring.ClientMetrics
import com.coralogix.rules.v1.rule_groups_service.ZioRuleGroupsService.RuleGroupsServiceClient
import io.grpc.protobuf.services.ProtoReflectionService
import io.grpc._
import izumi.reflect.Tag
import nl.vroste.rezilience.Bulkhead
import nl.vroste.rezilience.Bulkhead.BulkheadError
import scalapb.zio_grpc.client.ZClientCall
import scalapb.zio_grpc._
import zio.clock.Clock
import zio.duration._
import zio.logging.{ log, LogAnnotation }
import zio.{ Has, Managed, Schedule, UIO, ZIO, ZLayer, ZManaged }

import java.util.concurrent.TimeUnit

package object grpc {
  val server =
    ZLayer.fromManaged(for {
      config <- ZManaged.service[GrpcConfig]
      _ <- ManagedServer
             .fromServiceList(
               ServerBuilder
                 .forPort(config.port)
                 .addService(ProtoReflectionService.newInstance()),
               ServiceList
                 .addM(Health.make)
             )
      _ <- log
             .locally(LogAnnotation.Name("com" :: "coralogix" :: "operator" :: "grpc" :: Nil)) {
               log.info(s"gRPC listening on ${config.port}")
             }
             .toManaged_
    } yield ())

  object clients {
    private val AuthorizationKey: Metadata.Key[String] =
      Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER)

    private def authorizationHeaders(token: String): UIO[SafeMetadata] =
      for {
        metadata <- SafeMetadata.make
        _        <- metadata.put(AuthorizationKey, s"Bearer $token")
      } yield metadata

    private def callOptions(config: GrpcClientConfig): UIO[CallOptions] =
      ZIO.effectTotal {
        CallOptions.DEFAULT
          .withDeadlineAfter(config.deadline.toMillis, TimeUnit.MILLISECONDS)
      }

    private def handleRejection(error: BulkheadError[Status]): Status =
      error.fold(Status.RESOURCE_EXHAUSTED, identity)

    private def bulkheadInterceptor(bulkhead: Bulkhead): ZClientInterceptor[Any] =
      new ZClientInterceptor[Any] {
        override def interceptCall[Req, Res](
          methodDescriptor: MethodDescriptor[Req, Res],
          call: CallOptions,
          clientCall: ZClientCall[Any, Req, Res]
        ): ZClientCall[Any, Req, Res] =
          new ZClientCall.ForwardingZClientCall[Any, Req, Res, Any](clientCall) {
            override def sendMessage(message: Req): ZIO[Any, Status, Unit] =
              bulkhead(super.sendMessage(message))
                .mapError(handleRejection)
          }
      }

    private def monitoringInterceptor(
      clock: Clock.Service,
      clientMetrics: ClientMetrics
    ): ZClientInterceptor[Any] =
      new ZClientInterceptor[Any] {
        override def interceptCall[Req, Res](
          methodDescriptor: MethodDescriptor[Req, Res],
          call: CallOptions,
          clientCall: ZClientCall[Any, Req, Res]
        ): ZClientCall[Any, Req, Res] =
          new ZClientCall.ForwardingZClientCall[Clock, Req, Res, Any](clientCall) {
            override def sendMessage(message: Req): ZIO[Clock, Status, Unit] =
              super
                .sendMessage(message)
                .either
                .timed
                .tap {
                  case (duration, result) =>
                    sendMetrics(duration, result.left.getOrElse(Status.OK))
                }
                .map(_._2)
                .absolve

            private def sendMetrics(duration: Duration, result: Status): ZIO[Any, Nothing, Unit] = {
              val labels = ClientMetrics.labels(
                methodDescriptor.getServiceName,
                methodDescriptor.getBareMethodName,
                result
              )

              clientMetrics.callsCount.inc(labels).ignore *>
                clientMetrics.callTime
                  .observe(
                    duration.toMillis.toDouble / 1000.0,
                    labels
                  )
                  .ignore
            }
          }.provide(Has(clock))
      }

    private def customHeadersInterceptor(
      customHeaders: Map[Metadata.Key[String], String]
    ): Seq[ZClientInterceptor[Any]] =
      if (customHeaders.isEmpty)
        Seq.empty
      else
        Seq(
          ZClientInterceptor.headersUpdater((_, _, metadata) =>
            ZIO.foreach_(customHeaders) {
              case (name, value) =>
                metadata.put(name, value)
            }
          )
        )

    private def setupTLS[T <: ManagedChannelBuilder[T]](
      useTLS: Boolean,
      builder: ManagedChannelBuilder[T]
    ): ManagedChannelBuilder[T] =
      if (useTLS)
        builder.useTransportSecurity()
      else
        builder.usePlaintext()

    private def bulkheadMonitoring(
      serviceName: String,
      bulkhead: Bulkhead,
      clientMetrics: ClientMetrics
    ): ZIO[Clock, Nothing, Unit] = {
      val poll =
        for {
          metrics <- bulkhead.metrics
          _       <- clientMetrics.inFlight.set(metrics.inFlight.toDouble, Array(serviceName)).ignore
          _       <- clientMetrics.inQueue.set(metrics.inQueue.toDouble, Array(serviceName)).ignore
        } yield ()

      poll.repeat(Schedule.fixed(5.seconds)).unit
    }

    private def grpcClientLayer[Client: Tag](
      serviceName: String,
      create: (
        ZManagedChannel[Any],
        UIO[CallOptions],
        zio.UIO[SafeMetadata]
      ) => Managed[Throwable, Client]
    ): ZLayer[Has[GrpcClientConfig] with Has[ClientMetrics] with Clock, Throwable, Has[
      Client
    ]] =
      ZLayer.fromServicesManaged[
        GrpcClientConfig,
        ClientMetrics,
        Clock.Service,
        Any,
        Throwable,
        Client
      ] {
        case (config, clientMetrics, clock) =>
          for {
            bulkhead <- Bulkhead.make(config.parallelism, config.queueSize)
            _ <- bulkheadMonitoring(serviceName, bulkhead, clientMetrics)
                   .provide(Has(clock))
                   .forkManaged
            headers = config.headers.map {
                        case (name, value) =>
                          Metadata.Key.of(name, Metadata.ASCII_STRING_MARSHALLER) -> value
                      }
            client <- create(
                        ZManagedChannel(
                          setupTLS(
                            config.tls,
                            ManagedChannelBuilder
                              .forAddress(config.name, config.port)
                          ),
                          bulkheadInterceptor(bulkhead) +:
                            monitoringInterceptor(clock, clientMetrics) +:
                            customHeadersInterceptor(headers)
                        ),
                        callOptions(config),
                        authorizationHeaders(config.token)
                      )
          } yield client
      }

    object rulegroups {
      val live: ZLayer[Has[GrpcClientConfig] with Has[
        ClientMetrics
      ] with Clock, Throwable, RuleGroupsServiceClient] =
        grpcClientLayer("com.coralogix.rules.v1.RuleGroupsService", RuleGroupsServiceClient.managed)
    }

    object alerts {
      val live: ZLayer[Has[GrpcClientConfig] with Has[
        ClientMetrics
      ] with Clock, Throwable, AlertServiceClient] =
        grpcClientLayer("com.coralogix.alerts.v1.AlertService", AlertServiceClient.managed)
    }
  }
}
