val ScalaVer = "2.13.4"

enablePlugins(Protodep)

ThisBuild / scalaVersion := ScalaVer

val commonSettings = Seq(
  organization      := "com.coralogix",
  version           := "0.1",
  scalafmtOnCompile := true
)

// TODO this is hack to have CRD yaml generated before the app which needs it for compilation is compiled
commands += Command.command("compile") { state =>
  "grpc-deps/compile" ::
    "coralogix-kubernetes-operator/compile" ::
    state
}

lazy val root =
  Project("coralogix-kubernetes-operator", file("."))
//    .dependsOn(grpcDeps) // TODO test this
    .aggregate(app)

val sonatypeDomain = "sonatype-nexus.default.svc.cluster.local"
val sonatypeBaseUrl = s"http://$sonatypeDomain:8080/"

val sonatypeUser = sys.env.getOrElse("NEXUS_USER", "")
val sonatypePass = sys.env.getOrElse("NEXUS_PASSWORD", "")

lazy val privateNexus = ("Private Nexus" at sonatypeBaseUrl + "repository/maven-public/")
  .withAllowInsecureProtocol(true)

lazy val grpcDeps = Protodep
  .generateProject("grpc-deps")
  .settings(
    Compile / PB.targets += com.coralogix.crdgen.compiler.CodeGenerator -> (Compile / sourceManaged).value,
    Compile / PB.protoSources += file((Compile / sourceDirectory).value + "/protobuf-scala"),
    libraryDependencies += "com.coralogix" %% "zio-k8s-client" % "0.3.0"
  )

lazy val app = Project("coralogix-kubernetes-operator-app", file("app"))
  .settings(commonSettings)
  .settings(
    scalaVersion := ScalaVer,
    resolvers += Resolver.jcenterRepo,
    libraryDependencies ++= Seq(
      "com.coralogix"                 %% "zio-k8s-client"           % "0.3.0",
      "com.coralogix"                 %% "zio-k8s-operator"         % "0.3.0",
      "com.coralogix"                 %% "zio-k8s-client-quicklens" % "0.3.0",
      "com.softwaremill.quicklens"    %% "quicklens"                % "1.6.1",
      "nl.vroste"                     %% "rezilience"               % "0.5.1",
      "com.softwaremill.sttp.client3" %% "httpclient-backend-zio"   % "3.1.1",
      "com.softwaremill.sttp.client3" %% "slf4j-backend"            % "3.1.1",
      // Config
      "dev.zio" %% "zio-config"          % "1.0.0",
      "dev.zio" %% "zio-config-magnolia" % "1.0.0",
      "dev.zio" %% "zio-config-typesafe" % "1.0.0",
      // Logging
      "dev.zio" %% "zio-logging"              % "0.5.6",
      "dev.zio" %% "zio-logging-slf4j-bridge" % "0.5.6",
      // gRPC
      "com.thesamet.scalapb"               %% "scalapb-runtime-grpc"                    % scalapb.compiler.Version.scalapbVersion,
      "io.grpc"                             % "grpc-netty"                              % scalapb.compiler.Version.grpcJavaVersion,
      "com.thesamet.scalapb.common-protos" %% "proto-google-common-protos-scalapb_0.10" % "1.17.0-0" % "protobuf",
      "com.thesamet.scalapb.common-protos" %% "proto-google-common-protos-scalapb_0.10" % "1.17.0-0",
      "io.github.scalapb-json"             %% "scalapb-circe"                           % "0.7.1",
      // Metrics
      "dev.zio" %% "zio-metrics-prometheus" % "1.0.1",
      // Tests
      "dev.zio" %% "zio-test"          % "1.0.4-2" % Test,
      "dev.zio" %% "zio-test-sbt"      % "1.0.4-2" % Test,
      "dev.zio" %% "zio-test-magnolia" % "1.0.4-2" % Test
      //"com.oracle.substratevm" % "svm"               % "19.2.1" % Provided
    ),
    PB.targets in Compile := Seq(
      scalapb.gen(grpc = true)          -> (Compile / sourceManaged).value,
      scalapb.zio_grpc.ZioCodeGenerator -> (Compile / sourceManaged).value
    ),
//    resolvers += privateNexus,
    PB.deleteTargetDirectory := false,
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    fork          := true,
    Test / fork   := true,
    run / envVars := Map("CORALOGIX_CONFIG" -> "./.chart/config/development.conf"),
    // K8s
    externalCustomResourceDefinitions := Seq(
      file("crds/crd-coralogix-rule-group-set.yaml"),
      file("crds/crd-coralogix-loggers.yaml"),
      file("crds/crd-coralogix-alert-set.yaml"),
      file(
        "grpc-deps/target/scala-2.13/src_managed/main/com/coralogix/users/v2beta1/ApiKeyCRDSchema.yaml"
      )
    ),
    // Native image
    Compile / mainClass := Some("com.coralogix.operator.Main"),
    nativeImageVersion  := "20.3.0",
    nativeImageOptions ++= Seq(
      "--initialize-at-build-time=org.slf4j",
      "--initialize-at-build-time=scala.Predef$",
      "--initialize-at-build-time=scala.collection",
      "--initialize-at-build-time=scala.reflect",
      "--initialize-at-build-time=scala.package$",
      "--initialize-at-build-time=scala.math",
      "--enable-https",
      "--no-fallback",
      "--allow-incomplete-classpath",
      "--report-unsupported-elements-at-runtime",
      "--install-exit-handlers",
      "-H:+ReportExceptionStackTraces",
      "-H:+AllowVMInspection",
      "-H:JNIConfigurationFiles=../../src/graalvm/jni-config.json",
      "-H:ReflectionConfigurationFiles=../../src/graalvm/reflect-config.json",
      "-H:DynamicProxyConfigurationFiles=../../src/graalvm/proxy-config.json",
      "-H:ResourceConfigurationFiles=../../src/graalvm/resource-config.json"
    )
  )
  .dependsOn(grpcDeps)
  .enablePlugins(
    UniversalPlugin,
    JavaAppPackaging,
    K8sCustomResourceCodegenPlugin,
    NativeImagePlugin
  )
