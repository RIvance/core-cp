package playground.scalajs

import scala.scalajs.js

/** A document supplied by the worker host. Treat its text and version as one immutable snapshot. */
trait SourceDocument extends js.Object {
  /** Stable URI supplied by the workspace; it is not a language-level module identifier. */
  val uri: String
  /** Open language identifier selected by the adapter. */
  val languageId: String
  /** Complete source text; source coordinates refer to this exact string. */
  val text: String
  /** Integer revision increasing within one open document lifetime. */
  val version: Int
}

/** A validated run request. Documents have distinct URIs and contain the selected entry document. */
trait ExecutionInput extends js.Object {
  /** All workspace documents in the submitted snapshot. Do not mutate the array or its elements. */
  val documents: js.Array[SourceDocument]
  /** URI of the source file selected for execution, independent of entryPoint. */
  val entryDocumentUri: String
  /** Adapter-defined executable name; the compiler binding owns validation and interpretation. */
  val entryPoint: String
  /** Complete standard input for this execution. The runtime owns its line and EOF semantics. */
  val stdin: String
}

/** Closed set of severities understood by the Problems panel. */
type DiagnosticSeverity = "error" | "warning" | "info" | "hint"

/** Zero-based source position. Both coordinates must be nonnegative; character counts UTF-16 code units. */
final class Position(val line: Int, val character: Int) extends js.Object

/** Half-open source interval. The end must not precede the start; an empty interval is valid. */
final class Range(val start: Position, val end: Position) extends js.Object

/** A source interval in the exact document identified by uri. */
final class Location(val uri: String, val range: Range) extends js.Object

/**
 * A source or program failure, warning, or informational diagnostic.
 *
 * @param message Display text; consumers must not parse it to recover semantic information.
 * @param severity Importance of the diagnostic; independent of execution success or failure.
 * @param location Omit when the compiler cannot identify a reliable source site.
 * @param source Display label identifying the reporting component.
 * @param code Provider-owned identifier; string and numeric identifiers remain distinct.
 * @param data Optional provider context. It must contain only structured-cloneable JavaScript values.
 */
final class Diagnostic(
  val message: String,
  val severity: DiagnosticSeverity,
  val location: js.UndefOr[Location] = js.undefined,
  val source: js.UndefOr[String] = js.undefined,
  val code: js.UndefOr[String | Double] = js.undefined,
  val data: js.UndefOr[js.Any] = js.undefined
) extends js.Object

/** Text output offered for viewing and download. mediaType is a MIME type; name is a display/download label. */
final class Artifact(val name: String, val mediaType: String, val content: String) extends js.Object

/** A completed run. Source and program failures use error; infrastructure failures throw or reject. */
type ExecutionStatus = "success" | "error"

/**
 * Completed execution data, directly consumable as a JavaScript object; no JSON encoding is required.
 *
 * @param status Outcome of the run, independent of the severities of individual diagnostics.
 * @param diagnostics All diagnostics from this run. Keep source locations tied to the submitted snapshots.
 * @param artifacts Text artifacts produced by the compiler or runtime; empty when none are available.
 * @param value Optional rendered result. Omit when the language has no value to display.
 */
final class ExecutionResult(
  val status: ExecutionStatus,
  val diagnostics: js.Array[Diagnostic] = js.Array(),
  val artifacts: js.Array[Artifact] = js.Array(),
  val value: js.UndefOr[String] = js.undefined
) extends js.Object

/** Output stream identity, preserved independently of the chunk text. */
type OutputChannel = "stdout" | "stderr"

/** One ordered output fragment. text may include newlines and need not be a complete line. */
final class OutputChunk(val channel: OutputChannel, val text: String) extends js.Object

/**
 * Optional Scala.js implementation of the playground's neutral execution backend.
 * Export a factory returning this interface from the language's own module; the API library exports no global names.
 * The owner creates a fresh worker per run and terminates it on cancellation, including during synchronous execution.
 */
trait ExecutionBackend extends js.Object {
  /**
   * Execute a validated snapshot. Implementations may return a result directly or a JavaScript promise.
   * Emit chunks in program order before settling the result; the host validates them and ignores later output.
   * Return an error result for expected language failures. Throw/reject for infrastructure failures.
   * Scala Future implementations can use JSConverters.toJSPromise with their own ExecutionContext.
   */
  def execute(
    input: ExecutionInput, emit: js.Function1[OutputChunk, Unit]
  ): ExecutionResult | js.Promise[ExecutionResult]
}
