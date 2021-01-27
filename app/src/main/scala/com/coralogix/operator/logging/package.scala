package com.coralogix.operator

import org.slf4j.impl.ZioLoggerFactory
import zio.clock.Clock
import zio.console.Console
import zio.logging.LogAppender.withLoggerNameFromLine
import zio.logging.LogFiltering.filterBy
import zio.logging.Logging.{ addTimestamp, modifyLoggerM }
import zio.logging._
import zio.{ Has, ZIO, ZLayer }

package object logging {
  val filter =
    filterBy(
      LogLevel.Debug,
      "sttp.client3.logging.slf4j.Slf4jLoggingBackend" -> LogLevel.Info,
      "io.netty"                                       -> LogLevel.Info,
      "io.grpc.netty"                                  -> LogLevel.Info
    )

  val bindSlf4jBridge: ZLayer[Logging, Nothing, Logging] =
    ZIO
      .runtime[Logging]
      .flatMap { runtime =>
        ZIO.effectTotal {
          ZioLoggerFactory.initialize(runtime)
          runtime.environment.get
        }
      }
      .toLayer

  val live: ZLayer[Clock with Console, Nothing, Logging] = {
    val console = LogAppender.console[String](
      logLevel = LogLevel.Debug,
      format = new OperatorLogFormat
    )
    val async = (console >>>
      LogAppender.async(32) >>>
      withLoggerNameFromLine[String]).map(a => Has(a.get.filter(filter)))
    val logging = async >>> Logging.make

    (Clock.any ++ logging) >>> modifyLoggerM(addTimestamp[String]) >>> bindSlf4jBridge
  }
}
