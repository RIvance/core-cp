package cp.languageserver

import cp.languageserver.protocol.*
import cp.util.Result
import ujson.Value

class JsonRpcSuite extends munit.FunSuite {
  private def failure(value: Value): InvalidMessage = JsonRpc.decode(value) match {
    case Result.Err(error) => error
    case Result.Ok(message) => fail(s"Unexpected message: $message")
  }

  test("requests and notifications retain parameter structure and id kind") {
    val params = ujson.Obj("name" -> "🌍")
    assertEquals(
      JsonRpc.decode(ujson.Obj("jsonrpc" -> "2.0", "id" -> "1", "method" -> "test", "params" -> params)),
      Result.Ok(IncomingMessage.Request(RequestId.Text("1"), "test", params))
    )
    assertEquals(
      JsonRpc.decode(JsonRpc.notification("test", params)), Result.Ok(IncomingMessage.Notification("test", params))
    )
    assertEquals(RequestId.decode(ujson.Num(1)), Some(RequestId.Number(1)))
    assertEquals(RequestId.decode(ujson.Num(1.5)), None)
    assertEquals(RequestId.decode(ujson.Num(9007199254740992d)), None)
  }

  test("invalid JSON, batch messages, null request ids and malformed envelopes fail explicitly") {
    JsonRpc.parse("{") match {
      case Result.Err(error) => assertEquals(error.error.code, ErrorCode.ParseError)
      case _ => fail("Expected a parse error")
    }
    assertEquals(failure(ujson.Arr()).error.code, ErrorCode.InvalidRequest)
    assertEquals(failure(ujson.Obj("jsonrpc" -> "2.0", "id" -> ujson.Null, "method" -> "test")).id, None)
    assertEquals(failure(ujson.Obj("jsonrpc" -> "1.0", "id" -> 4, "method" -> "test")).id, Some(RequestId.Number(4)))
    assertEquals(
      failure(ujson.Obj("jsonrpc" -> "2.0", "id" -> 4, "method" -> "test", "params" -> true)).error.code,
      ErrorCode.InvalidRequest
    )
  }

  test("response validation preserves JSON-RPC's open error-code space") {
    val response = ujson.Obj("jsonrpc" -> "2.0", "id" -> 4, "error" -> ujson.Obj("code" -> 123, "message" -> "custom"))
    assertEquals(JsonRpc.decode(response), Result.Ok(IncomingMessage.Response))
    response("result") = ujson.Null
    assertEquals(failure(response).error.code, ErrorCode.InvalidRequest)
    response.obj.remove("result")
    response("error")("code") = ujson.Num(1.5)
    assertEquals(failure(response).error.code, ErrorCode.InvalidRequest)
  }

  test("parameter decoding rejects wrong shapes and fractional positions without exceptions") {
    val input = JsonInput(ujson.Obj("position" -> ujson.Obj("line" -> 1.5, "character" -> 0)))
    val decoded = input.field("position").flatMap(Position.decode)
    decoded match {
      case Result.Err(error) =>
        assertEquals(error.code, ErrorCode.InvalidParams)
        assert(error.message.contains("params.position.line"))
      case _ => fail("Expected invalid parameters")
    }
    assert(JsonInput(ujson.Null).field("position").toOption.isEmpty)
  }
}
