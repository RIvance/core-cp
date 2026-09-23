/** A position in the exact document text passed to a provider. Both coordinates are nonnegative integers. */
export interface Position {
  /** Zero-based line index. */
  readonly line: number;
  /** Zero-based UTF-16 code-unit offset within the line; tabs occupy one unit. */
  readonly character: number;
}
/** A half-open source interval. The end must not precede the start; an empty interval is valid. */
export interface Range {
  readonly start: Position;
  readonly end: Position;
}
/** A source range in a document identified by URI. Navigation can open only documents owned by the workspace. */
export interface Location {
  readonly uri: string;
  readonly range: Range;
}
/**
 * An immutable document snapshot. Providers must compute positions and results against this text/version pair.
 * URIs identify documents independently of language-level module names or local filesystem paths.
 */
export interface SourceDocument {
  readonly uri: string;
  readonly languageId: string;
  readonly text: string;
  /** Increases after edits during one open URI lifetime. A closed and reopened URI may restart its versions. */
  readonly version: number;
}
/**
 * A reported source issue. Omit location when the language cannot identify a source site; never invent one.
 * Human-readable messages are display text, not an input to fix selection or semantic queries.
 */
export interface Diagnostic {
  readonly message: string;
  readonly severity: 'error' | 'warning' | 'info' | 'hint';
  readonly location?: Location;
  readonly source?: string;
  /** Provider-defined diagnostic identifier. Numeric and string identifiers remain distinct. */
  readonly code?: string | number;
  /** Opaque provider-owned context passed back when requesting code actions. */
  readonly data?: unknown;
}
/** A located outline entry. Children must belong to the same semantic hierarchy as their parent. */
export interface DocumentSymbol {
  readonly name: string;
  readonly detail?: string;
  readonly kind: 'module' | 'type' | 'function' | 'variable' | 'member';
  readonly location: Location;
  readonly children: DocumentSymbol[];
}
/** Hover text and, when known, the exact source range it describes. A missing hover is returned as null. */
export interface Hover {
  readonly text: string;
  readonly range?: Range;
}
/**
 * One completion candidate. Its edits refer to the queried snapshot; the editor discards stale responses.
 * Language-specific visibility, type information, and candidate selection belong to the provider.
 */
export interface Completion {
  readonly label: string;
  readonly insertText: string;
  readonly detail?: string;
  readonly kind: 'keyword' | 'function' | 'type' | 'variable' | 'field' | 'constructor' | 'module' | 'snippet';
  /** Replacement range, or distinct insertion/replacement ranges. Omit to use the editor's current word range. */
  readonly range?: Range | { insert: Range; replace: Range };
  /** When true, insertText uses the editor's snippet syntax, including placeholders and tab stops. */
  readonly snippet?: boolean;
  readonly documentation?: string;
  /** Other edits in the queried document. They must not overlap the main completion edit or one another. */
  readonly additionalEdits?: TextEdit[];
  readonly sortText?: string;
  readonly filterText?: string;
  readonly commitCharacters?: string[];
  /** Fetch additional details for this candidate. Observe cancellation and return the complete resolved candidate. */
  readonly resolve?: (signal: AbortSignal) => Promise<Completion>;
  /** Run after the candidate is accepted. Reject on failure; the owning view displays the error. */
  readonly onAccepted?: () => Promise<void>;
}
/** A completion response; an empty list means the provider has no candidates for this request. */
export interface CompletionBatch {
  readonly items: Completion[];
  /** True asks the editor to query again as the user continues typing instead of only filtering this list. */
  readonly isIncomplete: boolean;
}
/** Replace range with text. An empty range inserts text; empty text deletes the range. */
export interface TextEdit {
  readonly range: Range;
  readonly text: string;
}
/**
 * Edits for one workspace document. The editor validates every group before applying any group.
 * Missing files, stale versions, invalid ranges, or overlaps reject the entire operation.
 */
export interface DocumentEdits {
  readonly uri: string;
  /** Required source version, when known. This is SourceDocument.version, not an internal editor model version. */
  readonly version?: number;
  readonly edits: readonly TextEdit[];
}
/** An adapter-owned operation invoked from a hint or action. Do not place compiler command dispatch in the editor. */
export interface LanguageCommand {
  readonly title: string;
  /** Runs when selected. The signal is aborted on service/model disposal; reject to report an execution failure. */
  readonly execute: (signal: AbortSignal) => Promise<void>;
}
/** One nonempty segment of an inlay label, with optional navigation or an executable operation. */
export interface InlayHintLabelPart {
  readonly value: string;
  readonly tooltip?: string;
  readonly location?: Location;
  readonly command?: LanguageCommand;
}
/**
 * Annotation displayed at a source position without changing the document. A label and every label part must be nonempty.
 * Hints may describe inferred types, parameter names, or other language facts already available to the provider.
 */
export interface InlayHint {
  readonly position: Position;
  readonly label: string | readonly InlayHintLabelPart[];
  readonly kind?: 'type' | 'parameter';
  /** Plain text. The editor does not execute HTML or trusted markup from hint tooltips. */
  readonly tooltip?: string;
  readonly paddingLeft?: boolean;
  readonly paddingRight?: boolean;
  /** Source edits used when the user accepts the hint; all ranges refer to the queried document. */
  readonly textEdits?: readonly TextEdit[];
  /** Fetch a complete hint with details or edits. Results for an older workspace revision are discarded. */
  readonly resolve?: (signal: AbortSignal) => Promise<InlayHint>;
}
/**
 * A nonempty, single-line semantic token. Returned tokens must not overlap and must belong to the queried document.
 * Type/modifier names must occur in the provider's legend; protocol-specific numeric indexes are not accepted here.
 */
export interface SemanticToken {
  readonly range: Range;
  readonly type: string;
  readonly modifiers: readonly string[];
}
/** Stable vocabulary for one provider lifetime. Names must be unique; up to 31 modifier names are supported. */
export interface SemanticTokenLegend {
  /** Open identifiers, such as function, type, or a language-specific category. */
  readonly tokenTypes: readonly string[];
  readonly tokenModifiers: readonly string[];
}
/**
 * An available or disabled source action. An enabled action must provide edits, a command, or a resolver supplying them.
 * Edits are applied before the command. File creation, deletion, and renaming are outside this text-edit contract.
 */
export interface CodeAction {
  readonly title: string;
  /** Extensible dot-separated category, for example quickfix or refactor.extract. */
  readonly kind?: string;
  readonly diagnostics?: readonly Diagnostic[];
  readonly isPreferred?: boolean;
  /** Explanation shown instead of allowing execution. An absent reason means the action is enabled. */
  readonly disabled?: string;
  readonly edit?: readonly DocumentEdits[];
  readonly command?: LanguageCommand;
  /** Fetch the complete action when selected. Preserve its meaning and observe the original request's snapshot. */
  readonly resolve?: (signal: AbortSignal) => Promise<CodeAction>;
}
/** Context for actions affecting the selected range or cursor position. */
export interface CodeActionContext {
  /** Original diagnostics intersecting the range, including numeric codes and opaque provider data. */
  readonly diagnostics: readonly Diagnostic[];
  /** Restrict results to these action kinds or their descendants. Undefined means no requested kind restriction. */
  readonly only?: readonly string[];
  readonly trigger: 'automatic' | 'invoked';
}
/** Candidate call signatures. Active indexes are zero-based; a null parameter means no active parameter. */
export interface SignatureHelp {
  readonly signatures: {
    label: string;
    documentation?: string;
    activeParameter?: number | null;
    parameters: { label: string | [number, number]; documentation?: string }[];
  }[];
  readonly activeSignature: number;
  readonly activeParameter: number | null;
}
/** Replaces the diagnostics for one document; an empty list clears previous diagnostics. */
export interface AnalysisUpdate {
  readonly uri: string;
  /** The analyzed source version. Omit only when the service cannot report it; versioned stale updates are discarded. */
  readonly version?: number;
  readonly diagnostics: Diagnostic[];
}
/** A service message that does not require a source location, such as an unavailable dependency or transport status. */
export interface ServiceLog {
  readonly level: 'error' | 'warning' | 'info';
  readonly message: string;
}
/**
 * Operations available after LanguageService.start resolves. Each member is optional and independent of the others.
 * Leave unsupported operations undefined; an empty result means a supported query found no result.
 * Providers must honor cancellation, use the supplied snapshot, and reject infrastructure failures with an Error.
 * Registrations are stable for a service lifetime; restart the service to change its advertised capability set.
 */
export interface LanguageCapabilities {
  /** Explain the symbol/expression at position, or return null when no information is available. */
  hover?(document: SourceDocument, position: Position, signal: AbortSignal): Promise<Hover | null>;
  /** Return the actual definition locations for the occurrence; never guess a location from its name. */
  definitions?(document: SourceDocument, position: Position, signal: AbortSignal): Promise<Location[]>;
  /** Return known occurrences of the same semantic entity. Empty means none are available. */
  references?(document: SourceDocument, position: Position, signal: AbortSignal): Promise<Location[]>;
  /** Return the queried document's symbol hierarchy for the outline and symbol picker. */
  symbols?(document: SourceDocument, signal: AbortSignal): Promise<DocumentSymbol[]>;
  /** Completion trigger policy and candidate lookup. Explicit completion remains available without trigger characters. */
  readonly completions?: {
    /** Characters that should request completion in addition to normal typing and explicit invocation. */
    triggerCharacters: readonly string[];
    /** Compute candidates at the cursor, preserving insertion/replacement ranges in this snapshot. */
    provide(document: SourceDocument, position: Position, signal: AbortSignal): Promise<CompletionBatch>;
  };
  /** Display annotations from existing language analysis; the editor does not infer types or parameter names. */
  readonly inlayHints?: {
    /** Return annotations in the requested visible range. No matches is an empty array. */
    provide(document: SourceDocument, range: Range, signal: AbortSignal): Promise<readonly InlayHint[]>;
    /** Subscribe to analysis changes requiring a fresh query. Return a function that removes this listener. */
    onDidChange?(listener: () => void): () => void;
  };
  /** Complete semantic coloring for one document. Lexical highlighting remains active without this capability. */
  readonly semanticTokens?: {
    readonly legend: SemanticTokenLegend;
    /** Return the complete token set, not a delta. Ordering is unrestricted; ranges and legend names are validated. */
    provide(document: SourceDocument, signal: AbortSignal): Promise<readonly SemanticToken[]>;
    /** Subscribe to analysis changes requiring recoloring. Return a function that removes this listener. */
    onDidChange?(listener: () => void): () => void;
  };
  /** Quick fixes, refactorings, and other text actions. Supplying this object does not require any particular action kind. */
  readonly codeActions?: {
    /** Known categories this provider can return. Undefined means the provider has not declared a category list. */
    readonly kinds?: readonly string[];
    /** Compute actions for range and context. Return disabled reasons when a known action cannot currently run. */
    provide(
      document: SourceDocument,
      range: Range,
      context: CodeActionContext,
      signal: AbortSignal,
    ): Promise<readonly CodeAction[]>;
  };
  /** Format the complete document with non-overlapping edits, using the requested indentation policy. */
  format?(
    document: SourceDocument,
    options: { tabSize: number; insertSpaces: boolean },
    signal: AbortSignal,
  ): Promise<TextEdit[]>;
  /** Rename the entity at position across owned documents. Reject if the new name or operation is invalid. */
  rename?(
    document: SourceDocument,
    position: Position,
    name: string,
    signal: AbortSignal,
  ): Promise<readonly DocumentEdits[]>;
  /** Call signature lookup and characters that open or refresh the signature widget. */
  readonly signature?: {
    triggerCharacters: readonly string[];
    retriggerCharacters: readonly string[];
    /** Return current call signatures, or null outside a supported call context. */
    provide(document: SourceDocument, position: Position, signal: AbortSignal): Promise<SignatureHelp | null>;
  };
  /** Return foldable line spans with zero-based start/end indexes and endLine greater than startLine. */
  folding?(document: SourceDocument, signal: AbortSignal): Promise<{ startLine: number; endLine: number }[]>;
}
/**
 * One owned language-service session. The controller creates a fresh instance on restart and disposes the old one.
 * It subscribes before starting, then synchronizes open documents and invokes supported capabilities.
 */
export interface LanguageService {
  /** The supported operations after start resolves. An empty object is valid. */
  readonly capabilities: Readonly<LanguageCapabilities>;
  /** Initialize the service and its capabilities. Reject on startup failure; no document requests precede success. */
  start(): Promise<void>;
  /** Open a new URI or replace its source snapshot. Repeated and older versions must not overwrite newer text. */
  synchronize(document: SourceDocument): void;
  /** End a URI's lifetime and release its analysis state. The URI may be opened again later. */
  close(uri: string): void;
  /** Subscribe to per-document diagnostic replacements; return a function that removes this listener. */
  onDiagnostics(listener: (update: AnalysisUpdate) => void): () => void;
  /** Subscribe to source-independent messages; return a function that removes this listener. */
  onLog(listener: (log: ServiceLog) => void): () => void;
  /** Subscribe to terminal service/transport failures; the host can restart the service. */
  onFailure(listener: (error: Error) => void): () => void;
  /** Release workers, connections, listeners, and pending requests. Late results must not update the workspace. */
  dispose(): void;
}

/** Complete immutable input for one execution. The entry URI must identify one of documents. */
export interface ExecutionInput {
  readonly documents: readonly SourceDocument[];
  readonly entryDocumentUri: string;
  /** Entry name or convention interpreted by the runtime; an empty value is allowed for script-style execution. */
  readonly entryPoint: string;
  readonly stdin: string;
}
/** A chunk of program output. Preserve text exactly, including newlines; the controller combines chunks in arrival order. */
export interface OutputChunk {
  readonly channel: 'stdout' | 'stderr';
  readonly text: string;
}
/** A named text result, such as generated code or a compiler stage. Binary data and live compiler objects are not supported. */
export interface Artifact {
  readonly name: string;
  readonly mediaType: string;
  readonly content: string;
}
/** A completed run, including expected source or runtime errors. diagnostics and artifacts may be empty but are required. */
export interface ExecutionResult {
  readonly status: 'success' | 'error';
  /** A rendered result for display. Do not return a live language value. */
  readonly value?: string;
  readonly diagnostics: Diagnostic[];
  readonly artifacts: Artifact[];
}
/** Executes workspace snapshots independently of editor state. The adapter owns isolation and execution resources. */
export interface LanguageRuntime {
  /**
   * Run the supplied entry and stream output through emit. Expected program errors return status: 'error'.
   * Infrastructure failures reject. Abort must stop underlying work, including synchronous or nonterminating programs;
   * use a terminable worker when cooperative cancellation is insufficient. Release resources when the run ends.
   */
  execute(input: ExecutionInput, emit: (chunk: OutputChunk) => void, signal: AbortSignal): Promise<ExecutionResult>;
}
/** A complete initial workspace. File paths are relative, case-sensitive POSIX paths; entryPath identifies one file. */
export interface Example {
  readonly id: string;
  readonly title: string;
  readonly description: string;
  readonly files: readonly { readonly path: string; readonly text: string }[];
  /** Optional empty folders. Parents of files and folders are inferred. */
  readonly directories?: readonly string[];
  /** Relative path of the initial execution entry and editor tab. */
  readonly entryPath: string;
  readonly entryPoint?: string;
  readonly stdin?: string;
}
/** Language metadata used by the workspace. Names, extensions, entry conventions, and examples belong to the adapter. */
export interface LanguageDefinition {
  /** Stable open identifier for source documents and persistence namespacing. Changing it selects a different saved session. */
  readonly id: string;
  readonly name: string;
  /** Preferred file extension including its leading dot, for example .lang. */
  readonly extension: string;
  /** Relative path of the blank file created when no examples or saved workspace exist. */
  readonly defaultFilePath: string;
  readonly defaultEntryPoint: string;
  /** Display order. The first example is the initial workspace when no saved/shared session exists; empty opens defaultFilePath. */
  readonly examples: readonly Example[];
}
/** Composition contract for one language. Supplying runtime execution does not require a language service. */
export interface LanguagePlugin {
  readonly definition: LanguageDefinition;
  /** Create an independently owned runtime. Do not share mutable run state between factory results. */
  createRuntime(): LanguageRuntime;
  /** Create a fresh optional service scoped to the supplied workspace URI; initialization happens through start(). */
  createLanguageService?(context: { workspaceUri: string }): LanguageService;
}
