package cp.languageserver

import cp.languageserver.compiler.CpBackend
import cp.languageserver.protocol.JsonRpc
import cp.languageserver.server.LanguageServer
import ujson.Value

class LanguageServerSuite extends munit.FunSuite {
  private val main = "file:///independent-client/Main.cp"
  private val library = "file:///independent-client/%E8%B3%87%E6%96%99/Library.cp"

  private def request(server: LanguageServer, method: String, params: Value = ujson.Null): Value = {
    val input = ujson.Obj("jsonrpc" -> "2.0", "id" -> "request", "method" -> method, "params" -> params)
    val outputs = server.receive(input)
    assertEquals(outputs.size, 1)
    outputs.head
  }

  private def notify(server: LanguageServer, method: String, params: Value = ujson.Obj()): List[Value] =
    server.receive(JsonRpc.notification(method, params))

  private def start(): LanguageServer = {
    val server = new LanguageServer(new CpBackend)
    val initialized = request(server, "initialize", ujson.Obj("capabilities" -> ujson.Obj()))("result")
    assertEquals(initialized("capabilities")("textDocumentSync"), ujson.Num(1))
    assertEquals(initialized("capabilities")("positionEncoding"), ujson.Str("utf-16"))
    assertEquals(initialized("capabilities")("completionProvider")("triggerCharacters"), ujson.Arr(".", ":"))
    assert(!initialized("capabilities").obj.contains("hoverProvider"))
    notify(server, "initialized")
    server
  }

  private def open(server: LanguageServer, uri: String, text: String, version: Int = 1): List[Value] =
    notify(server, "textDocument/didOpen", ujson.Obj("textDocument" -> ujson.Obj(
      "uri" -> uri, "version" -> version, "languageId" -> "cp", "text" -> text
    )))

  private def change(server: LanguageServer, uri: String, version: Int, text: String): List[Value] =
    notify(server, "textDocument/didChange", ujson.Obj(
      "textDocument" -> ujson.Obj("uri" -> uri, "version" -> version),
      "contentChanges" -> ujson.Arr(ujson.Obj("text" -> text))
    ))

  private def diagnostics(messages: List[Value], uri: String): Value = {
    messages.find(value => value.obj.get("method").contains(ujson.Str("textDocument/publishDiagnostics")) &&
      value("params")("uri") == ujson.Str(uri)).getOrElse(fail(s"Missing diagnostics: $messages"))("params")
  }

  private def complete(server: LanguageServer, text: String, offset: Int): Value = {
    val before = text.take(offset)
    request(server, "textDocument/completion", ujson.Obj(
      "textDocument" -> ujson.Obj("uri" -> main),
      "position" -> ujson.Obj("line" -> before.count(_ == '\n'), "character" -> (offset - before.lastIndexOf('\n') - 1))
    ))("result")
  }

  test("initialization, unknown requests, shutdown and exit follow one lifecycle on both targets") {
    val server = new LanguageServer(new CpBackend)
    assertEquals(request(server, "unknown")("error")("code"), ujson.Num(-32002))
    assert(request(server, "initialize", ujson.Obj("capabilities" -> ujson.Obj())).obj.contains("result"))
    assertEquals(request(server, "unknown")("error")("code"), ujson.Num(-32002))
    notify(server, "initialized")
    assertEquals(request(server, "initialize", ujson.Obj())("error")("code"), ujson.Num(-32600))
    assertEquals(request(server, "unknown")("error")("code"), ujson.Num(-32601))
    assertEquals(notify(server, "unknown"), Nil)
    assertEquals(request(server, "shutdown")("result"), ujson.Null)
    assert(!server.shouldExit)
    assertEquals(request(server, "unknown")("error")("code"), ujson.Num(-32600))
    notify(server, "exit")
    assert(server.shouldExit)
    assertEquals(server.exitCode, 0)
    val premature = new LanguageServer(new CpBackend)
    notify(premature, "exit")
    assertEquals(premature.exitCode, 1)
  }

  test("imports, UTF-16 diagnostics and versioned clearing do not evaluate recursive definitions") {
    val server = start()
    open(server, main, "module App\nimport Shared::Library::*\ndef main: Int = value\n")
    val invalid = """def value = let text = "🌍" in 1 ,, 2;"""
    val failure = diagnostics(open(server, library, s"module Shared::Library\n$invalid\n"), library)
    assertEquals(failure("version"), ujson.Num(1))
    val diagnostic = failure("diagnostics")(0)
    assert(diagnostic("message").str.contains("not disjoint"))
    assertEquals(diagnostic("range"), ujson.Obj(
      "start" -> ujson.Obj("line" -> 1, "character" -> invalid.indexOf("1 ,, 2")),
      "end" -> ujson.Obj("line" -> 1, "character" -> (invalid.indexOf("1 ,, 2") + 6))
    ))
    val corrected = "module Shared::Library\ndef value: Int = 42\ndef loop: Int = loop\n"
    assertEquals(diagnostics(change(server, library, 2, corrected), library)("diagnostics"), ujson.Arr())
    assertEquals(change(server, library, 1, "def broken ="), Nil)
    assertEquals(
      diagnostics(change(server, main, 2, "module App\nimport Shared::Library::*\ndef main: Int = value + 1"), main)
        ("diagnostics"), ujson.Arr()
    )
    val closed = notify(server, "textDocument/didClose", ujson.Obj("textDocument" -> ujson.Obj("uri" -> library)))
    assertEquals(diagnostics(closed, library)("diagnostics"), ujson.Arr())
    assert(closed.exists(value => value("params").obj.get("message").exists(_.str.contains("not available"))))
  }

  test("semantic completion replaces the entire token and preserves fields of different types") {
    val server = start()
    val text = """def result = let text = "🌍" in { field = 42; file = "text" }.fix"""
    open(server, main, text)
    val items = complete(server, text, text.length - 1)("items").arr.toList
    assertEquals(items.map(item => item("label").str -> item("detail").str), List("field" -> "Int", "file" -> "String"))
    items.foreach { item =>
      assertEquals(item("kind"), ujson.Num(5))
      assertEquals(item("textEdit"), ujson.Obj(
        "newText" -> item("label"),
        "range" -> ujson.Obj(
          "start" -> ujson.Obj("line" -> 0, "character" -> (text.length - 3)),
          "end" -> ujson.Obj("line" -> 0, "character" -> text.length)
        )
      ))
    }
  }

  test("completion sees dependency edits and lexical shadowing in the current snapshot") {
    val server = start()
    val text = "import Library::*\ndef result = value.fi"
    open(server, library, "def value = { first = 42 }")
    open(server, main, text)
    assertEquals(complete(server, text, text.length)("items")(0)("label"), ujson.Str("first"))
    change(server, library, 2, """def value = { final = "updated" }""")
    val changed = complete(server, text, text.length)("items")
    assertEquals(changed.arr.map(item => item("label").str -> item("detail").str).toList, List("final" -> "String"))
    val shadowed = "import Library::*\ndef result(value: String) = let value = 42 in va"
    change(server, main, 2, shadowed)
    val locals = complete(server, shadowed, shadowed.length)("items")
    assertEquals(locals.arr.map(item => item("label").str -> item("detail").str).toList, List("value" -> "Int"))
  }

  test("parse diagnostics preserve their URI and a full replacement clears them") {
    val server = start()
    val uri = "untitled:///editor/Main.cp"
    val failed = diagnostics(open(server, uri, """def main = "\q";"""), uri)("diagnostics")(0)
    assert(failed("message").str.contains("invalid string escape"))
    assertEquals(failed("range")("start"), ujson.Obj("line" -> 0, "character" -> 12))
    assertEquals(diagnostics(change(server, uri, 2, "def main: Int = 42"), uri)("diagnostics"), ujson.Arr())
  }

  test("type completion uses the compiler's source naming rules") {
    val server = start()
    val text = "type Int = String; type Decimal = Bool; def result[Int] = (42 : I)"
    open(server, main, text)
    val items = complete(server, text, text.length - 1)("items").arr.toList
    assertEquals(items.map(item => (item("label").str, item("detail").str, item("kind").num.toInt)),
      List(("Int", "Int", 7)))
  }

  test("malformed change batches are atomic and invalid completion positions return protocol errors") {
    val server = start()
    val text = "def value = 42; def main = va"
    open(server, main, text)
    val outputs = notify(server, "textDocument/didChange", ujson.Obj(
      "textDocument" -> ujson.Obj("uri" -> main, "version" -> 2),
      "contentChanges" -> ujson.Arr(ujson.Obj("text" -> "def main = true"), ujson.Obj("text" -> 42))
    ))
    assertEquals(outputs.head("method"), ujson.Str("window/logMessage"))
    assertEquals(complete(server, text, text.length)("items")(0)("label"), ujson.Str("value"))
    val invalid = request(server, "textDocument/completion", ujson.Obj(
      "textDocument" -> ujson.Obj("uri" -> main), "position" -> ujson.Obj("line" -> -1, "character" -> 0)
    ))
    assertEquals(invalid("error")("code"), ujson.Num(-32602))
  }
}
