package com.coralogix.operator.logging

import io.circe.{ Encoder, Json }
import org.apache.logging.log4j.message.MultiformatMessage
import org.apache.logging.log4j.{ Level, LogManager, ThreadContext }
import zio.logging.LogLevel._
import zio.logging._
import zio.{ FiberRef, Has, UIO, ULayer, ZIO }

object Log4j2Appender extends LogAppender.Service[String] {

  object ZioMessage {
    val SupportedFormats = Array("JSON")
  }

  final case class ZioMessage(
    message: String,
    event: Json,
    annotations: Map[String, String],
    throwable: Option[Throwable]
  ) extends MultiformatMessage {

    override def getFormattedMessage(): String = event.noSpaces

    override def getFormat(): String = ""

    override def getParameters(): Array[Object] = Array.empty

    override def getThrowable(): Throwable = throwable.orNull

    override def getFormattedMessage(formats: Array[String]): String = event.noSpaces

    override def getFormats: Array[String] = ZioMessage.SupportedFormats
  }

  private def logLevel(ctx: LogContext): Level =
    ctx.get(LogAnnotation.Level) match {
      case Fatal          => Level.FATAL
      case LogLevel.Error => Level.ERROR
      case Warn           => Level.WARN
      case Info           => Level.INFO
      case Debug          => Level.DEBUG
      case Trace          => Level.TRACE
      case Off            => Level.OFF
    }

  override def write(ctx: LogContext, msgData: => String): UIO[Unit] = {
    val loggerName = ctx.get(LogAnnotation.Name).mkString(".")
    import scala.jdk.CollectionConverters._
    ZIO.effectTotal {
      val logger = LogManager.getLogger(loggerName)
      ThreadContext.putAll(ctx.renderContext.asJava)
      val message = ZioMessage(
        msgData,
        ctx.get(Log.Event).mapObject(_.add("_message", Json.fromString(msgData))),
        ctx.renderContext,
        ctx.get(LogAnnotation.Throwable)
      )
      logger.log(logLevel(ctx), message)
    }
  }
}

object LogSyntax {

  implicit class FieldBuilder(name: String) {
    def :=[A](value: A)(implicit enc: Encoder[A]): (String, Json) =
      name -> enc(value)
  }

  implicit class CirceSyntax(logger: Logger[String]) {

    def info(message: String, fields: (String, Json)*): UIO[Unit] =
      Log.log(LogLevel.Info, message, None, fields).provide(Has(logger))

    def debug(message: String, fields: (String, Json)*): UIO[Unit] =
      Log.log(LogLevel.Debug, message, None, fields).provide(Has(logger))

    def debug(throwable: Throwable, message: String, fields: (String, Json)*): UIO[Unit] =
      Log.log(LogLevel.Debug, message, Some(throwable), fields).provide(Has(logger))

    def warn(message: String, fields: (String, Json)*): UIO[Unit] =
      Log.log(LogLevel.Warn, message, None, fields).provide(Has(logger))

    def warn(throwable: Throwable, message: String, fields: (String, Json)*): UIO[Unit] =
      Log.log(LogLevel.Warn, message, Some(throwable), fields).provide(Has(logger))

    def error(message: String, fields: (String, Json)*): UIO[Unit] =
      Log.log(LogLevel.Error, message, None, fields).provide(Has(logger))

    def error(throwable: Throwable, message: String, fields: (String, Json)*): UIO[Unit] =
      Log.log(LogLevel.Error, message, Some(throwable), fields).provide(Has(logger))

  }
}

object Log {
  def logger(name: String): ULayer[Logging] =
    FiberRef
      .make(LogContext.empty)
      .map(ref => Logger.LoggerWithFormat(ref, Log4j2Appender).named(name))
      .toLayer

  def log(
    level: LogLevel,
    message: String,
    throwable: Option[Throwable],
    fields: Seq[(String, Json)]
  ): ZIO[Logging, Nothing, Unit] =
    ZIO.serviceWith[Logger[String]] { log =>
      log.locally(
        _.annotate(Event, Json.obj(fields: _*)).annotate(
          LogAnnotation.Throwable,
          throwable
        )
      )(log.log(level)(message))
    }

  def annotate[R, E, A](
    fields: (String, Json)*
  )(effect: ZIO[R, E, A]): ZIO[R with Logging, E, A] =
    ZIO
      .service[Logger[String]]
      .flatMap(log => log.locally(_.annotate(Event, Json.obj(fields: _*)))(effect))

  def info(message: String, fields: (String, Json)*): ZIO[Logging, Nothing, Unit] =
    log(LogLevel.Info, message, None, fields)

  def debug(message: String, fields: (String, Json)*): ZIO[Logging, Nothing, Unit] =
    log(LogLevel.Debug, message, None, fields)

  def debug(
    throwable: Throwable,
    message: String,
    fields: (String, Json)*
  ): ZIO[Logging, Nothing, Unit] =
    log(LogLevel.Debug, message, Some(throwable), fields)

  def warn(message: String, fields: (String, Json)*): ZIO[Logging, Nothing, Unit] =
    log(LogLevel.Warn, message, None, fields)

  def warn(
    throwable: Throwable,
    message: String,
    fields: (String, Json)*
  ): ZIO[Logging, Nothing, Unit] =
    log(LogLevel.Warn, message, Some(throwable), fields)

  def error(message: String, fields: (String, Json)*): ZIO[Logging, Nothing, Unit] =
    log(LogLevel.Error, message, None, fields)

  def error(
    throwable: Throwable,
    message: String,
    fields: (String, Json)*
  ): ZIO[Logging, Nothing, Unit] =
    log(LogLevel.Error, message, Some(throwable), fields)

  def trace(message: String, fields: (String, Json)*): ZIO[Logging, Nothing, Unit] =
    log(LogLevel.Trace, message, None, fields)

  val Event: LogAnnotation[Json] =
    LogAnnotation[Json]("event", Json.obj(), (first, second) => first.deepMerge(second), _ => "")

}
