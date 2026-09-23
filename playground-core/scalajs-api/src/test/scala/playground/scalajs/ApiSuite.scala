package playground.scalajs

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.scalajs.js

class ApiSuite extends munit.FunSuite {
  private given ExecutionContext = ExecutionContext.global

  test("execution accepts plain JavaScript snapshots and exposes cloneable result fields") {
    val input = js.JSON.parse("""{
      "documents":[{"uri":"file:///a.txt","languageId":"text","version":7,"text":"🌍"}],
      "entryDocumentUri":"file:///a.txt","entryPoint":"main","stdin":"input"
    }""").asInstanceOf[ExecutionInput]
    val backend = new ExecutionBackend {
      override def execute(request: ExecutionInput, emit: js.Function1[OutputChunk, Unit]): ExecutionResult = {
        emit(new OutputChunk("stderr", request.stdin))
        new ExecutionResult(
          "success",
          diagnostics = js.Array(new Diagnostic(
            "message", "hint", source = "text", code = 17,
            location = new Location(request.entryDocumentUri, new Range(new Position(0, 0), new Position(0, 2))),
            data = js.Dynamic.literal(revision = request.documents(0).version)
          )),
          artifacts = js.Array(new Artifact("source", "text/plain", request.documents(0).text)),
          value = request.entryPoint
        )
      }
    }
    val output = ArrayBuffer.empty[OutputChunk]
    val result = backend.execute(input, chunk => output += chunk)
    val fields = js.JSON.parse(js.JSON.stringify(result)).asInstanceOf[js.Dynamic]
    assertEquals(fields.status.asInstanceOf[String], "success")
    assertEquals(fields.value.asInstanceOf[String], "main")
    assertEquals(fields.diagnostics.asInstanceOf[js.Array[js.Dynamic]](0).code.asInstanceOf[Int], 17)
    assertEquals(fields.diagnostics.asInstanceOf[js.Array[js.Dynamic]](0).data.revision.asInstanceOf[Int], 7)
    val diagnostic = fields.diagnostics.asInstanceOf[js.Array[js.Dynamic]](0)
    assertEquals(diagnostic.location.range.end.character.asInstanceOf[Int], 2)
    assertEquals(fields.artifacts.asInstanceOf[js.Array[js.Dynamic]](0).content.asInstanceOf[String], "🌍")
    assertEquals(output.head.channel, "stderr")
    assertEquals(output.head.text, "input")
  }

  test("absent optional values remain absent in serialized execution results") {
    val result = new ExecutionResult("error", diagnostics = js.Array(new Diagnostic("failure", "error")))
    val fields = js.JSON.parse(js.JSON.stringify(result)).asInstanceOf[js.Dynamic]
    assert(js.isUndefined(fields.value))
    assert(js.isUndefined(fields.diagnostics.asInstanceOf[js.Array[js.Dynamic]](0).location))
    assert(js.isUndefined(fields.diagnostics.asInstanceOf[js.Array[js.Dynamic]](0).code))
    assert(js.isUndefined(fields.diagnostics.asInstanceOf[js.Array[js.Dynamic]](0).data))
    assertEquals(fields.artifacts.length.asInstanceOf[Int], 0)
  }

  test("text bridge preserves message envelopes, output order, Unicode, and cleanup") {
    val received = ArrayBuffer.empty[String]
    val emitted = ArrayBuffer.empty[js.Object]
    var closed = false
    val backend = JsonRpcBackend.fromJson(
      message => {
        received += message
        Future.successful(Seq(
          """{"jsonrpc":"2.0","method":"progress","params":{"text":"🌍"}}""",
          """{"jsonrpc":"2.0","id":"request","result":null}"""
        ))
      },
      message => emitted += message,
      () => closed = true
    )
    val message = js.Dynamic.literal(jsonrpc = "2.0", id = "request", method = "analyze")
    backend.receive(message).asInstanceOf[js.Promise[Unit]].toFuture.map { _ =>
      assertEquals(received.toVector, Vector(js.JSON.stringify(message)))
      assertEquals(emitted.length, 2)
      assertEquals(emitted.head.asInstanceOf[js.Dynamic].params.text.asInstanceOf[String], "🌍")
      assertEquals(emitted(1).asInstanceOf[js.Dynamic].id.asInstanceOf[String], "request")
      assertEquals(emitted(1).asInstanceOf[js.Dynamic].result, null)
      backend.dispose.foreach(_())
      assert(closed)
    }
  }

  test("text bridge allows independent pending requests without blocking later notifications") {
    val pending = Promise[Seq[String]]()
    val messages = ArrayBuffer.empty[js.Object]
    var calls = 0
    val backend = JsonRpcBackend.fromJson(
      _ => {
        calls += 1
        if calls == 1 then pending.future else Future.successful(Seq.empty)
      },
      message => messages += message
    )
    val first = backend.receive(js.Dynamic.literal(jsonrpc = "2.0", id = 1, method = "slow"))
      .asInstanceOf[js.Promise[Unit]].toFuture
    val second = backend.receive(js.Dynamic.literal(jsonrpc = "2.0", method = "cancel"))
      .asInstanceOf[js.Promise[Unit]].toFuture
    second.flatMap { _ =>
      assertEquals(calls, 2)
      assert(!first.isCompleted)
      pending.success(Seq("""{"jsonrpc":"2.0","id":1,"result":null}"""))
      first.map(_ => assertEquals(messages.length, 1))
    }
  }

  test("text bridge rejects both synchronous exceptions and failed futures") {
    val failure = new IllegalStateException("server unavailable")
    val handlers: Seq[String => Future[Seq[String]]] = Seq(_ => throw failure, _ => Future.failed(failure))
    Future.sequence(handlers.map { handler =>
      val backend = JsonRpcBackend.fromJson(handler, _ => fail("unexpected output"))
      backend.receive(js.Dynamic.literal(jsonrpc = "2.0", id = 1, method = "request"))
        .asInstanceOf[js.Promise[Unit]].toFuture.transform { result =>
          assert(result.isFailure)
          scala.util.Success(())
        }
    })
  }

  test("text bridge rejects malformed output JSON") {
    val backend = JsonRpcBackend.fromJson(_ => Future.successful(Seq("{")), _ => fail("unexpected output"))
    backend.receive(js.Dynamic.literal(jsonrpc = "2.0", id = 1, method = "request"))
      .asInstanceOf[js.Promise[Unit]].toFuture.transform { result =>
        assert(result.isFailure)
        scala.util.Success(())
      }
  }
}
