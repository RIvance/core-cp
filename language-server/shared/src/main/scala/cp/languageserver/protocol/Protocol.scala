package cp.languageserver.protocol

import cp.util.Result
import ujson.Value

/** Zero-based LSP coordinates, measured in UTF-16 code units. */
final case class Position(line: Int, character: Int) {
  def json: Value = ujson.Obj("line" -> line, "character" -> character)
}

object Position {
  def decode(input: JsonInput): Result[Position, RpcError] = for {
    line <- input.field("line").flatMap(_.integer(0))
    character <- input.field("character").flatMap(_.integer(0))
  } yield Position(line, character)
}

final case class Range(start: Position, end: Position) {
  def json: Value = ujson.Obj("start" -> start.json, "end" -> end.json)
}

final case class Diagnostic(range: Range, message: String, source: String) {
  def json: Value = ujson.Obj("range" -> range.json, "severity" -> 1, "message" -> message, "source" -> source)
}

enum CompletionItemKind(val number: Int) {
  case Function extends CompletionItemKind(3)
  case Field extends CompletionItemKind(5)
  case Variable extends CompletionItemKind(6)
  case Class extends CompletionItemKind(7)
  case TypeParameter extends CompletionItemKind(25)
}

final case class CompletionItem(label: String, kind: CompletionItemKind, detail: String, replacement: Range) {
  def json: Value = ujson.Obj(
    "label" -> label,
    "kind" -> kind.number,
    "detail" -> detail,
    "textEdit" -> ujson.Obj("range" -> replacement.json, "newText" -> label)
  )
}

final case class CompletionList(items: List[CompletionItem]) {
  // Recompute after another keystroke: a longer prefix may denote a different typed receiver.
  def json: Value = ujson.Obj("isIncomplete" -> true, "items" -> ujson.Arr.from(items.map(_.json)))
}
