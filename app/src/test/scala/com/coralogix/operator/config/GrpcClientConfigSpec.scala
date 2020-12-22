package com.coralogix.operator.config

import zio.ZIO
import zio.config._
import zio.config.magnolia.DeriveConfigDescriptor.descriptor
import zio.config.typesafe.TypesafeConfigSource
import zio.duration._
import zio.test.environment.TestEnvironment
import zio.test._
import zio.test.Assertion._

object GrpcClientConfigSpec extends DefaultRunnableSpec {
  override def spec: ZSpec[TestEnvironment, Any] =
    suite("GrpcClientConfig")(
      testM("reads header config properly") {
        val desc = descriptor[GrpcClientConfig]
        val hocon =
          """name = "test.host.name"
            |port = 9090
            |tls = true
            |token = "token"
            |headers {
            |  X-Custom-Header-1: rules
            |  X-Custom-Header-2: "test value"
            |}
            |deadline = 1 minute
            |parallelism = 4
            |queueSize = 8192""".stripMargin

        for {
          source <- ZIO.fromEither(TypesafeConfigSource.fromHoconString(hocon))
          result <- ZIO.fromEither(read(desc from source))
        } yield assert(result)(
          equalTo(
            GrpcClientConfig(
              name = "test.host.name",
              port = 9090,
              tls = true,
              token = "token",
              headers = Map(
                "X-Custom-Header-1" -> "rules",
                "X-Custom-Header-2" -> "test value"
              ),
              deadline = 1.minute,
              parallelism = 4,
              queueSize = 8192
            )
          )
        )
      }
    )
}
