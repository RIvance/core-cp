package cp.languageserver.browser

import cp.languageserver.compiler.CpBackend
import cp.languageserver.protocol.JsonRpc
import cp.languageserver.server.LanguageServer
import ujson.Value

import scala.collection.mutable.ListBuffer
import scala.scalajs.js

class WorkerSuite extends munit.FunSuite {
  private final class Host {
    val messages = ListBuffer.empty[Value]
    var listener = Option.empty[js.Function1[WorkerMessage, Unit]]
    var closed = false

    val port: WorkerPort = js.Dynamic.literal(
      addEventListener = ((_: String, callback: js.Function1[WorkerMessage, Unit]) => {
        listener = Some(callback)
      }): js.Function2[String, js.Function1[WorkerMessage, Unit], Unit],
      removeEventListener = ((_: String, callback: js.Function1[WorkerMessage, Unit]) => {
        if (listener.contains(callback)) listener = None
      }): js.Function2[String, js.Function1[WorkerMessage, Unit], Unit],
      postMessage = ((message: js.Any) => {
        messages += ujson.read(js.JSON.stringify(message))
      }): js.Function1[js.Any, Unit],
      close = (() => { closed = true }): js.Function0[Unit]
    ).asInstanceOf[WorkerPort]

    def send(value: Value): Unit = sendRaw(js.JSON.parse(ujson.write(value)))
    def sendRaw(value: js.Any): Unit = {
      listener.foreach(_(js.Dynamic.literal(data = value).asInstanceOf[WorkerMessage]))
    }
  }

  test("the browser adapter handles protocol objects and semantic completion entirely inside the worker") {
    val host = new Host
    val server = new WorkerServer(host.port, new LanguageServer(new CpBackend))
    host.send(ujson.Obj("jsonrpc" -> "2.0", "id" -> 1, "method" -> "initialize",
      "params" -> ujson.Obj("capabilities" -> ujson.Obj())))
    assertEquals(host.messages.last("result")("capabilities")("positionEncoding"), ujson.Str("utf-16"))
    host.send(JsonRpc.notification("initialized", ujson.Obj()))
    val uri = "untitled:///Main.cp"
    val text = "def main = { field = 42 }.fi"
    host.send(JsonRpc.notification("textDocument/didOpen", ujson.Obj("textDocument" -> ujson.Obj(
      "uri" -> uri, "version" -> 1, "languageId" -> "cp", "text" -> text
    ))))
    host.send(ujson.Obj("jsonrpc" -> "2.0", "id" -> 2, "method" -> "textDocument/completion", "params" -> ujson.Obj(
      "textDocument" -> ujson.Obj("uri" -> uri), "position" -> ujson.Obj("line" -> 0, "character" -> text.length)
    )))
    assertEquals(host.messages.last("result")("items")(0)("label"), ujson.Str("field"))
    host.send(ujson.Obj("jsonrpc" -> "2.0", "id" -> 3, "method" -> "shutdown"))
    host.send(JsonRpc.notification("exit", ujson.Obj()))
    assert(host.closed)
    assertEquals(host.listener, None)
    server.stop()
  }

  test("non-JSON worker messages fail without corrupting the session") {
    val host = new Host
    val server = new WorkerServer(host.port, new LanguageServer(new CpBackend))
    val cyclic = js.Dynamic.literal()
    cyclic.updateDynamic("self")(cyclic)
    host.sendRaw(cyclic)
    assertEquals(host.messages.last("error")("code"), ujson.Num(-32600))
    host.sendRaw(js.undefined)
    assertEquals(host.messages.last("error")("code"), ujson.Num(-32600))
    host.send(ujson.Obj("jsonrpc" -> "2.0", "id" -> "ready", "method" -> "initialize",
      "params" -> ujson.Obj("capabilities" -> ujson.Obj())))
    assert(host.messages.last.obj.contains("result"))
    server.stop()
    assertEquals(host.listener, None)
  }
}
