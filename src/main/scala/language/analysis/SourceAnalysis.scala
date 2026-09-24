package cp.language.analysis

import cp.language.compilation.SourcePath
import cp.source.SourceSpan

/** Roles of established source names, independent of any editor protocol. */
enum CompletionKind {
  case Variable, Function, Field, Type, TypeParameter
}

/** The description is presentation text; lookup and type computation use the compiler's structured values. */
final case class CompletionCandidate(name: String, kind: CompletionKind, description: String)

/**
 * Alternatives for one source name. The span covers its final component, including the part after
 * the cursor. Candidates are neither prefix-filtered nor filtered by an enclosing expected type:
 * the completed name may begin a longer expression.
 */
final case class CompletionSite(span: SourceSpan, candidates: List[CompletionCandidate])

enum DiagnosticPhase(val label: String) {
  case Parsing extends DiagnosticPhase("parse")
  case Checking extends DiagnosticPhase("compile")
}

/** A missing span means a file-level issue, not an invented location at its first token. */
final case class DiagnosticSource(path: SourcePath, span: Option[SourceSpan])

/** Stable, compiler-owned diagnostic data, independent of internal error variants and editor protocols. */
final case class SourceDiagnostic(phase: DiagnosticPhase, message: String, source: Option[DiagnosticSource])

/** Facts refer to exactly the supplied source snapshots, even when a definition fails to elaborate. */
final case class SourceAnalysis(
  diagnostics: List[SourceDiagnostic],
  completions: Map[SourcePath, List[CompletionSite]]
)
