package cp.languageserver.server

import cp.languageserver.document.{DocumentStore, DocumentUri, TextDocument}
import cp.languageserver.protocol.{CompletionList, Diagnostic}

/** Unlocated compiler issues stay messages; they never acquire a fabricated token range. */
final case class DiagnosticReport(diagnostics: Map[DocumentUri, List[Diagnostic]], messages: List[String])

/**
 * The session sees source snapshots and editor results, never syntax trees, compiler contexts or
 * runtime terms. A language adapter owns the translation to the compiler's public analysis API.
 */
trait LanguageBackend {
  def check(documents: DocumentStore): DiagnosticReport
  def completionTriggers: List[String]
  def complete(documents: DocumentStore, document: TextDocument, offset: Int): CompletionList
}
