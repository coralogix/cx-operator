package com.coralogix.operator.opentelemetry

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.semconv.resource.attributes.ResourceAttributes
import zio._

object OtelTracer {

  val live: ZLayer[system.System, Throwable, Has[Tracer]] =
    (for {
      port <- getFromEnv("OTEL_EXPORTER_AGENT_PORT")
      host <- getFromEnv("OTEL_EXPORTER_AGENT_HOST")
      name <- getFromEnv("OTEL_SERVICE_NAME")
      spanExporter <-
        ZIO.effect(OtlpGrpcSpanExporter.builder().setEndpoint(s"http://$host:$port").build())
      spanProcessor <- ZIO.succeed(SimpleSpanProcessor.create(spanExporter))
      tracerProvider <-
        ZIO.succeed(
          SdkTracerProvider
            .builder()
            .setResource(Resource.create(Attributes.of(ResourceAttributes.SERVICE_NAME, name)))
            .addSpanProcessor(spanProcessor)
            .build()
        )
      openTelemetry <-
        ZIO.succeed(OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build())
      tracer <- ZIO.succeed(openTelemetry.getTracer("com.coralogix.krolling.platform.OtelTracer"))
    } yield tracer).toLayer

  private def getFromEnv(variable: String): ZIO[system.System, Throwable, String] =
    for {
      maybeValue <- system.env(variable)
      value      <- ZIO.getOrFail(maybeValue)
    } yield value

}
