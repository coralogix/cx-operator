package com.coralogix.operator.client.internal

import io.circe._

object CircePrettyFailure {

  private def prettyPrintDecodeFailure(message: String, history: List[CursorOp]): String = {
    val customMessage =
      if (message == "Attempt to decode value on failed cursor") None else Some(message)
    def describeTail: String = describePosition(history.tail).reverse.mkString("/")
    def describeFull: String = describePosition(history).reverse.mkString("/")
    def prettyFailure(message: => String): String =
      customMessage match {
        case Some(msg) => s"$msg in $describeFull"
        case None      => message
      }
    history.head match {
      case CursorOp.DownField(name) if predefinedDecoderFailureNames.contains(message) =>
        s"$describeFull is not a $message"
      case CursorOp.DownField(name) =>
        prettyFailure(s"Could not find field: '$name' in $describeTail")
      case CursorOp.DownN(n) =>
        prettyFailure(s"Could not find the $n${postfix(n)} element in $describeTail")
      case _ =>
        s"$message: $describeFull"
    }
  }
  private def describePosition(ops: List[CursorOp]): List[String] =
    ops match {
      case Nil                                   => List("root")
      case CursorOp.DownField(name) :: remaining => s"$name" :: describePosition(remaining)
      case CursorOp.DownN(n) :: remaining        => s"[$n]" :: describePosition(remaining)
      case _                                     => List(ops.toString()) // TODO: implement for more
    }
  private def postfix(n: Int): String =
    n match {
      case 1 => "st"
      case 2 => "nd"
      case 3 => "rd"
      case _ => "th"
    }
  private val predefinedDecoderFailureNames: Set[String] =
    Set(
      "String",
      "Int",
      "Long",
      "Short",
      "Byte",
      "BigInt",
      "BigDecimal",
      "Boolean",
      "Char",
      "java.lang.Integer",
      "java.lang.Long",
      "java.lang.Short",
      "java.lang.Byte",
      "java.lang.Boolean",
      "java.lang.Character"
    )

  def prettyPrint(error: Error): String =
    error match {
      case DecodingFailure(message, history) => prettyPrintDecodeFailure(message, history)
      case _                                 => error.toString
    }
}
