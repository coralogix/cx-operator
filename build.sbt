val ScalaVer = "2.13.4"

enablePlugins(Protodep)
Global / protodepUseHttps := true

ThisBuild / scalaVersion := ScalaVer

val commonSettings = Seq(
  organization := "com.coralogix",
  version      := "0.1"
)

lazy val root = Project("coralogix-kubernetes-operator", file("."))
  .aggregate(
    app
  )

lazy val grpcDeps = Protodep
  .generateProject("grpc-deps")
  .settings(
    Compile / PB.protoSources += file((Compile / sourceDirectory).value + "/protobuf-scala")
  )

lazy val app = Project("coralogix-kubernetes-operator-app", file("app"))
  .settings(commonSettings)
  .settings(
    scalaVersion := ScalaVer,
    libraryDependencies ++= Dependencies.all,
    Compile / PB.targets := Seq(
      scalapb.gen(grpc = true)          -> (Compile / sourceManaged).value,
      scalapb.zio_grpc.ZioCodeGenerator -> (Compile / sourceManaged).value
    ),
    PB.deleteTargetDirectory := false,
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    fork        := true,
    Test / fork := true,
//    run / envVars := Map("CORALOGIX_CONFIG" -> "../charts/config/development.conf"),
    // K8s
    externalCustomResourceDefinitions := Seq(
      file("charts/cx-operator/crds/crd-coralogix-rule-group-set.yaml"),
      file("charts/cx-operator/crds/crd-coralogix-loggers.yaml"),
      file("charts/cx-operator/crds/crd-coralogix-alert-set.yaml")
    ),
    // Native image
    Compile / mainClass := Some("com.coralogix.operator.Main"),
    nativeImageJvm      := "graalvm-java17",
    nativeImageVersion  := "22.1.0",
    nativeImageOptions ++= Seq(
      "--initialize-at-build-time=org.apache.logging",
      "--initialize-at-run-time=com.fasterxml.jackson",
      "--initialize-at-build-time=org.yaml.snakeyaml",
      "--initialize-at-build-time=jdk.management.jfr.SettingDescriptorInfo",
      "--initialize-at-build-time=scala.Predef$",
      "--initialize-at-build-time=scala.Symbol$",
      "--initialize-at-build-time=scala.collection",
      "--initialize-at-build-time=scala.reflect",
      "--initialize-at-build-time=scala.package$",
      "--initialize-at-build-time=scala.math",
      "--initialize-at-run-time=io.netty",
      "--initialize-at-run-time=io.grpc.netty",
      "--initialize-at-run-time=io.grpc.netty.shaded.io.netty",
      "--initialize-at-run-time=javax.xml.parsers.FactoryFinder",
      "--initialize-at-run-time=javax.xml.datatype.DatatypeFactory",
      "--initialize-at-run-time=jdk.xml.internal.SecuritySupport",
      "--initialize-at-run-time=org.slf4j.LoggerFactory",
      "--initialize-at-run-time=com.sun.jmx.mbeanserver.MBeanInstantiator",
      "--initialize-at-run-time=com.sun.jmx.defaults.JmxProperties",
      "--initialize-at-run-time=org.apache.logging.log4j.core.async.AsyncLoggerContext",
      "--initialize-at-run-time=org.apache.logging.log4j.core.pattern.JAnsiTextRenderer",
      "--enable-https",
      "--no-fallback",
      "--allow-incomplete-classpath",
      "--install-exit-handlers",
      "-H:+PrintClassInitialization",
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
