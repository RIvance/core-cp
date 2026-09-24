package cp.languageserver.jvm

import cp.languageserver.compiler.CpBackend
import cp.languageserver.protocol.JsonRpc
import cp.languageserver.server.LanguageServer
import ujson.Value

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, IOException}
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import scala.collection.mutable.ListBuffer
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*

class StdioSuite extends munit.FunSuite {
  test("framing counts UTF-8 bytes and handles fragmented and concatenated input") {
    val output = new ByteArrayOutputStream
    val writer = new FramedConnection(new ByteArrayInputStream(Array.emptyByteArray), output)
    val messages = List(
      JsonRpc.notification("test", ujson.Obj("text" -> "λ🌍資料")),
      JsonRpc.notification("another", ujson.Obj())
    )
    messages.foreach(writer.write)
    val input = new ByteArrayInputStream(output.toByteArray) {
      override def read(buffer: Array[Byte], offset: Int, length: Int): Int =
        super.read(buffer, offset, math.min(length, 1))
    }
    val reader = new FramedConnection(input, new ByteArrayOutputStream)
    messages.foreach(message => assertEquals(ujson.read(reader.read().get), message))
    assertEquals(reader.read(), None)
  }

  test("ambiguous, oversized and truncated frames fail at the transport boundary") {
    val inputs = List(
      "Content-Length: 0\r\nContent-Length: 0\r\n\r\n",
      "Content-Length: -1\r\n\r\n",
      "Content-Length: 999999999999999999999\r\n\r\n",
      "Content-Length: 3\r\n\r\n{}",
      "Content-Length: 0\r\n",
      "Content-Length: 0\r\nContent-Type: application/vscode-jsonrpc; charset=latin1\r\n\r\n"
    )
    inputs.foreach { text =>
      intercept[IOException] {
        new FramedConnection(
          new ByteArrayInputStream(text.getBytes(StandardCharsets.US_ASCII)), new ByteArrayOutputStream
        ).read()
      }
    }
    intercept[IOException] {
      new FramedConnection(new ByteArrayInputStream("Content-Length: 0".getBytes(StandardCharsets.US_ASCII)),
        new ByteArrayOutputStream, FramingLimits(maximumHeaderBytes = 4)).read()
    }
  }

  test("invalid UTF-8 yields a parse error and the following framed message is still processed") {
    val input = new ByteArrayOutputStream
    input.write("Content-Length: 1\r\n\r\n".getBytes(StandardCharsets.US_ASCII))
    input.write(0xff)
    val writer = new FramedConnection(new ByteArrayInputStream(Array.emptyByteArray), input)
    writer.write(ujson.Obj("jsonrpc" -> "2.0", "id" -> 1, "method" -> "initialize",
      "params" -> ujson.Obj("capabilities" -> ujson.Obj())))
    writer.write(JsonRpc.notification("exit", ujson.Obj()))
    val output = new ByteArrayOutputStream
    val status = new StdioServer(new LanguageServer(new CpBackend),
      new ByteArrayInputStream(input.toByteArray), output).run()
    assertEquals(status, 1)
    val reader = new FramedConnection(new ByteArrayInputStream(output.toByteArray), new ByteArrayOutputStream)
    assertEquals(ujson.read(reader.read().get)("error")("code"), ujson.Num(-32700))
    assert(ujson.read(reader.read().get).obj.contains("result"))
    assertEquals(reader.read(), None)
  }

  test("the standalone JVM process completes semantic LSP requests and exits with stdin still open") {
    val java = Path.of(System.getProperty("java.home"), "bin", "java").toString
    val process = new ProcessBuilder(
      java, "-cp", System.getProperty("cp.languageServer.classpath"), "cp.languageserver.jvm.Main", "--stdio"
    ).start()
    try {
      val connection = new FramedConnection(process.getInputStream, process.getOutputStream)
      val notifications = ListBuffer.empty[Value]
      def request(id: Int, method: String, params: Value): Value = {
        connection.write(ujson.Obj("jsonrpc" -> "2.0", "id" -> id, "method" -> method, "params" -> params))
        Await.result(Future {
          var response = Option.empty[Value]
          while (response.isEmpty) {
            val message = ujson.read(connection.read().getOrElse {
              val error = new String(process.getErrorStream.readAllBytes(), StandardCharsets.UTF_8)
              fail("Server closed stdout before responding: " + error)
            })
            if (message.obj.get("id").contains(ujson.Num(id))) response = Some(message)
            else notifications += message
          }
          response.get
        }(using ExecutionContext.global), 10.seconds)
      }
      val initialized = request(1, "initialize", ujson.Obj("capabilities" -> ujson.Obj()))
      assertEquals(initialized("result")("capabilities")("positionEncoding"), ujson.Str("utf-16"))
      connection.write(JsonRpc.notification("initialized", ujson.Obj()))
      val uri = "file:///standalone/Main.cp"
      val text = """def main = let text = "🌍" in { field = 42 }.fi"""
      connection.write(JsonRpc.notification("textDocument/didOpen", ujson.Obj("textDocument" -> ujson.Obj(
        "uri" -> uri, "version" -> 1, "languageId" -> "cp", "text" -> text
      ))))
      val completion = request(2, "textDocument/completion", ujson.Obj(
        "textDocument" -> ujson.Obj("uri" -> uri),
        "position" -> ujson.Obj("line" -> 0, "character" -> text.length)
      ))
      assertEquals(completion("result")("items")(0)("label"), ujson.Str("field"))
      assert(notifications.exists(_("method") == ujson.Str("textDocument/publishDiagnostics")))
      assertEquals(request(3, "shutdown", ujson.Null)("result"), ujson.Null)
      connection.write(JsonRpc.notification("exit", ujson.Obj()))
      assert(process.waitFor(10, TimeUnit.SECONDS), "exit must not wait for stdin to close")
      assertEquals(process.exitValue(), 0, new String(process.getErrorStream.readAllBytes(), StandardCharsets.UTF_8))
    } finally {
      process.destroyForcibly()
      process.getInputStream.close()
      process.getOutputStream.close()
      process.getErrorStream.close()
    }
  }
}
