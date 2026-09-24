package cp.languageserver.protocol

import cp.util.Result
import ujson.Value

import scala.util.control.NonFatal

enum RequestId {
  case Number(value: Long)
  case Text(value: String)

  def json: Value = this match {
    case Number(value) => ujson.Num(value.toDouble)
    case Text(value) => ujson.Str(value)
  }
}

object RequestId {
  def decode(value: Value): Option[RequestId] = value match {
    case ujson.Str(text) => Some(RequestId.Text(text))
    case ujson.Num(number) if number.isWhole && math.abs(number) <= 9007199254740991d =>
      Some(RequestId.Number(number.toLong))
    case _ => None
  }
}

enum ErrorCode(val number: Int) {
  case ParseError extends ErrorCode(-32700)
  case InvalidRequest extends ErrorCode(-32600)
  case MethodNotFound extends ErrorCode(-32601)
  case InvalidParams extends ErrorCode(-32602)
  case InternalError extends ErrorCode(-32603)
  case ServerNotInitialized extends ErrorCode(-32002)
}

final case class RpcError(code: ErrorCode, message: String) {
  def json: Value = ujson.Obj("code" -> code.number, "message" -> message)
}

final case class InvalidMessage(id: Option[RequestId], error: RpcError)

enum IncomingMessage {
  case Request(id: RequestId, method: String, params: Value)
  case Notification(method: String, params: Value)
  case Response
}

/** JSON-RPC envelopes only; parameter contracts and LSP lifecycle belong to the session. */
object JsonRpc {
  def parse(text: String): Result[IncomingMessage, InvalidMessage] = {
    val value = try Result.Ok(ujson.read(text)) catch {
      case NonFatal(error) =>
        Result.Err(InvalidMessage(None, RpcError(ErrorCode.ParseError, error.getMessage)))
    }
    value.flatMap(decode)
  }

  def decode(value: Value): Result[IncomingMessage, InvalidMessage] = value match {
    case objectValue: ujson.Obj =>
      val fields = objectValue.value
      val id = fields.get("id").flatMap(RequestId.decode)
      def invalid(message: String) = Result.Err(InvalidMessage(id, RpcError(ErrorCode.InvalidRequest, message)))
      if (!fields.get("jsonrpc").contains(ujson.Str("2.0"))) {
        invalid("Expected JSON-RPC version 2.0.")
      } else {
        fields.get("method") match {
          case Some(ujson.Str(method)) =>
            val params = fields.getOrElse("params", ujson.Null)
            params match {
              case _: ujson.Obj | _: ujson.Arr | ujson.Null =>
                id match {
                  case Some(requestId) => Result.Ok(IncomingMessage.Request(requestId, method, params))
                  case None if fields.contains("id") => invalid("A request id must be a string or integer.")
                  case None => Result.Ok(IncomingMessage.Notification(method, params))
                }
              case _ => invalid("Parameters must be an object or array.")
            }
          case Some(_) => invalid("The method must be a string.")
          case None =>
            val hasResult = fields.contains("result")
            val hasError = fields.contains("error")
            val validId = id.nonEmpty || fields.get("id").contains(ujson.Null)
            if (validId && hasResult != hasError && (!hasError || validError(fields("error")))) {
              Result.Ok(IncomingMessage.Response)
            } else {
              invalid("A response needs an id and exactly one of result or error.")
            }
        }
      }
    case _ => Result.Err(InvalidMessage(None, RpcError(ErrorCode.InvalidRequest, "Expected a JSON-RPC object.")))
  }

  private def validError(value: Value): Boolean = value match {
    case fields: ujson.Obj => (fields.value.get("code"), fields.value.get("message")) match {
      case (Some(ujson.Num(code)), Some(_: ujson.Str)) => code.isWhole && code >= Int.MinValue && code <= Int.MaxValue
      case _ => false
    }
    case _ => false
  }

  def response(id: RequestId, result: Value): Value =
    ujson.Obj("jsonrpc" -> "2.0", "id" -> id.json, "result" -> result)

  def failure(id: Option[RequestId], error: RpcError): Value =
    ujson.Obj("jsonrpc" -> "2.0", "id" -> id.fold[Value](ujson.Null)(_.json), "error" -> error.json)

  def notification(method: String, params: Value): Value =
    ujson.Obj("jsonrpc" -> "2.0", "method" -> method, "params" -> params)
}

/** A structural decoder that reports the offending parameter path without throwing on client input. */
final case class JsonInput(value: Value, path: String = "params") {
  private def invalid(expected: String): Result[Nothing, RpcError] =
    Result.Err(RpcError(ErrorCode.InvalidParams, s"Expected $expected at $path."))

  def objectValue: Result[Unit, RpcError] = value match {
    case _: ujson.Obj => Result.Ok(())
    case _ => invalid("an object")
  }

  def field(name: String): Result[JsonInput, RpcError] = objectValue.flatMap { _ =>
    value.obj.get(name) match {
      case Some(found) => Result.Ok(JsonInput(found, s"$path.$name"))
      case None => Result.Err(RpcError(ErrorCode.InvalidParams, s"Missing $path.$name."))
    }
  }

  def optional(name: String): Option[JsonInput] =
    value.objOpt.flatMap(_.get(name)).map(JsonInput(_, s"$path.$name"))

  def string: Result[String, RpcError] = value match {
    case ujson.Str(text) => Result.Ok(text)
    case _ => invalid("a string")
  }

  def integer(minimum: Int = Int.MinValue): Result[Int, RpcError] = value match {
    case ujson.Num(number) if number.isWhole && number >= minimum && number <= Int.MaxValue =>
      Result.Ok(number.toInt)
    case _ => invalid(s"a 32-bit integer at least $minimum")
  }

  def elements: Result[List[JsonInput], RpcError] = value match {
    case values: ujson.Arr => Result.Ok(values.value.toList.zipWithIndex.map { case (element, index) =>
      JsonInput(element, s"$path[$index]")
    })
    case _ => invalid("an array")
  }
}
