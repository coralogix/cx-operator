package com.coralogix.operator

import com.coralogix.zio.k8s.client.model.K8sNamespace
import org.slf4j.impl.ZioLoggerFactory
import zio.Cause._
import zio.clock.Clock
import zio.console.Console
import zio.internal.stacktracer.Tracer
import zio.internal.stacktracer.ZTraceElement.{ NoLocation, SourceLocation }
import zio.internal.stacktracer.impl.AkkaLineNumbersTracer
import zio.internal.tracing.TracingConfig
import zio.logging.Logging.{ addTimestamp, modifyLoggerM }
import zio.logging._
import zio.internal.{ tracing, Tracing }
import zio.{ Cause, Has, Tag, UIO, ZIO, ZLayer }

import scala.annotation.tailrec

package object logging {
  val filter =
    filterBy(
      LogLevel.Debug,
      "sttp.client3.logging.slf4j.Slf4jLoggingBackend" -> LogLevel.Debug,
      "io.netty"                                       -> LogLevel.Info,
      "io.grpc.netty"                                  -> LogLevel.Info
    )

  // TODO: from zio-logging-slf4j, should not be private there >>>
  private val tracing =
    Tracing(Tracer.globallyCached(new AkkaLineNumbersTracer), TracingConfig.enabled)

  private def classNameForLambda(lambda: => AnyRef) =
    tracing.tracer.traceLocation(() => lambda) match {
      case SourceLocation(_, clazz, _, _) => Some(clazz)
      case NoLocation(_)                  => None
    }

  private def withLoggerNameFromLine[A <: AnyRef: Tag]: ZLayer[Appender[A], Nothing, Appender[A]] =
    ZLayer.fromFunction[Appender[A], LogAppender.Service[A]](appender =>
      new LogAppender.Service[A] {
        override def write(ctx: LogContext, msg: => A): UIO[Unit] = {
          val ctxWithName = ctx.get(LogAnnotation.Name) match {
            case Nil =>
              ctx.annotate(
                LogAnnotation.Name,
                classNameForLambda(msg).getOrElse("ZIO.defaultLogger") :: Nil
              )
            case _ => ctx
          }
          appender.get.write(ctxWithName, msg)
        }
      }
    )
  // TODO: <<<

  val bindSlf4jBridge: ZLayer[Logging, Nothing, Logging] =
    ZIO
      .runtime[Logging]
      .flatMap { runtime =>
        ZIO.effectTotal {
          ZioLoggerFactory.bind(runtime)
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

  // TODO: to zio-logging >>>

  case class LogFilterNode(logLevel: LogLevel, children: Map[String, LogFilterNode])

  @tailrec
  private def findMostSpecificLogLevel(names: List[String], currentNode: LogFilterNode): LogLevel =
    names match {
      case next :: remaining =>
        currentNode.children.get(next) match {
          case Some(nextNode) =>
            findMostSpecificLogLevel(remaining, nextNode)
          case None =>
            currentNode.logLevel
        }
      case Nil =>
        currentNode.logLevel
    }

  def filterByTree(root: LogFilterNode): (LogContext, => String) => Boolean =
    (ctx, _) => {
      val loggerName = ctx.get(LogAnnotation.Name).flatMap(_.split('.'))
      val logLevel = findMostSpecificLogLevel(loggerName, root)
      ctx.get(LogAnnotation.Level) >= logLevel
    }

  def filterBy(
    rootLevel: LogLevel,
    mappings: (String, LogLevel)*
  ): (LogContext, => String) => Boolean =
    filterByTree(buildLogFilterTree(rootLevel, mappings))

  def buildLogFilterTree(rootLevel: LogLevel, mappings: Seq[(String, LogLevel)]): LogFilterNode = {
    def add(tree: LogFilterNode, names: List[String], level: LogLevel): LogFilterNode =
      names match {
        case Nil =>
          tree.copy(logLevel = level)
        case name :: remaining =>
          tree.children.get(name) match {
            case Some(subtree) =>
              tree.copy(
                children = tree.children.updated(name, add(subtree, remaining, level))
              )
            case None =>
              tree.copy(
                children = tree.children + (name -> add(
                  LogFilterNode(tree.logLevel, Map.empty),
                  remaining,
                  level
                ))
              )
          }
      }

    mappings.foldLeft(
      LogFilterNode(rootLevel, Map.empty)
    ) {
      case (tree, (name, logLevel)) =>
        val nameList = name.split('.').toList
        add(tree, nameList, logLevel)
    }
  }
  // TODO: <<<
}
