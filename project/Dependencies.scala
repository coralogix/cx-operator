import sbt._

object Dependencies {
  val Versions = new {
    val zioK8s = "1.4.8"
    val quickLens = "1.8.10"
    val sttp = "3.3.18"
    val zioConfig = "1.0.10"
    val zioLogging = "0.5.14"
    val log4j = "2.17.2"
    val jackson = "2.10.0"
    val zio = "1.0.17"
    val protos = "2.5.0-3"
    val scalapbCirce = "0.12.0"
    val zioMagic = "0.3.12"
    val zioMetrics = "1.0.14"
    val rezilience = "0.8.4"
    val opentelemetry = "1.18.0"
    val grpcNetty = "1.47.0"
    val zioOpenTelemetry = "1.0.0"
  }

  val zioK8s = Seq(
    "com.coralogix"                 %% "zio-k8s-client"           % Versions.zioK8s,
    "com.coralogix"                 %% "zio-k8s-operator"         % Versions.zioK8s,
    "com.coralogix"                 %% "zio-k8s-client-quicklens" % Versions.zioK8s,
    "com.softwaremill.quicklens"    %% "quicklens"                % Versions.quickLens,
    "com.softwaremill.sttp.client3" %% "httpclient-backend-zio"   % Versions.sttp,
    "com.softwaremill.sttp.client3" %% "slf4j-backend"            % Versions.sttp
  )

  val zioConfig = Seq(
    "dev.zio" %% "zio-config"          % Versions.zioConfig,
    "dev.zio" %% "zio-config-magnolia" % Versions.zioConfig,
    "dev.zio" %% "zio-config-typesafe" % Versions.zioConfig
  )

  val logging = Seq(
    "dev.zio"                         %% "zio-logging"                % Versions.zioLogging,
    "org.apache.logging.log4j"         % "log4j-api"                  % Versions.log4j,
    "org.apache.logging.log4j"         % "log4j-core"                 % Versions.log4j,
    "org.apache.logging.log4j"         % "log4j-slf4j-impl"           % Versions.log4j,
    "org.apache.logging.log4j"         % "log4j-layout-template-json" % Versions.log4j,
    "com.fasterxml.jackson.core"       % "jackson-databind"           % Versions.jackson,
    "com.fasterxml.jackson.dataformat" % "jackson-dataformat-yaml"    % Versions.jackson
  )

  val proto = Seq(
    "com.thesamet.scalapb"               %% "scalapb-runtime-grpc"                    % scalapb.compiler.Version.scalapbVersion,
    "io.grpc"                             % "grpc-netty"                              % scalapb.compiler.Version.grpcJavaVersion,
    "com.thesamet.scalapb.common-protos" %% "proto-google-common-protos-scalapb_0.11" % Versions.protos % "protobuf",
    "com.thesamet.scalapb.common-protos" %% "proto-google-common-protos-scalapb_0.11" % Versions.protos,
    "io.github.scalapb-json"             %% "scalapb-circe"                           % Versions.scalapbCirce
  )

  val metrics = "dev.zio"               %% "zio-metrics-prometheus" % Versions.zioMetrics
  val zioMagic = "io.github.kitlangton" %% "zio-magic"              % Versions.zioMagic
  val rezilience = "nl.vroste"          %% "rezilience"             % Versions.rezilience

  val opentelemetry = Seq(
    "io.opentelemetry" % "opentelemetry-exporter-otlp" % Versions.opentelemetry,
    "io.opentelemetry" % "opentelemetry-sdk" % Versions.opentelemetry,
    "io.grpc" % "grpc-netty-shaded" % Versions.grpcNetty,
    "dev.zio" %% "zio-opentelemetry" % Versions.zioOpenTelemetry
  )

  val zioTest = Seq(
    "dev.zio" %% "zio-test",
    "dev.zio" %% "zio-test-sbt",
    "dev.zio" %% "zio-test-magnolia"
  ).map(_ % Versions.zio % Test)

  val all = Seq(metrics, zioMagic, rezilience) ++ zioK8s ++ zioConfig ++ logging ++ proto ++ zioTest ++ opentelemetry
}
