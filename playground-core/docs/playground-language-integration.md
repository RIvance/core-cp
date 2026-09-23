# Playground language integration API

This document specifies the adapter API. **Must** states a requirement, **should** states a recommendation, and **may** states an optional behavior. Unsupported capabilities must be absent; no adapter is required to implement hints, semantic tokens, code actions, or any other language-service operation.

## Package entry points and mounting

The reusable package is `@language-playground/ide`, defined in [package.json](../package.json). It ships ESM modules, TypeScript declarations, a stylesheet, and the Monaco editor worker. It does not contain a compiler, a default language, or example programs. React and React DOM 19.2 are peer dependencies. Use an ESM bundler that handles module workers and asset URLs; Vite is covered by the package-consumer test.

| Import                                | Public API                                                                                                                   |
| ------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------- |
| `@language-playground/ide`            | `mountPlayground`, `PlaygroundOptions`, `PlaygroundHandle`, and the types from `/api`                                        |
| `@language-playground/ide/embedded`   | `createEmbeddedPlayground`, fixed-file host API, events and options                                                          |
| `@language-playground/ide/core`       | Shared controller, state, session/settings types, and React components                                                       |
| `@language-playground/ide/api`        | Document, diagnostic, execution, language-service, plugin, example, and editor-installation types; no runtime initialization |
| `@language-playground/ide/editor`     | The configured `monaco` instance, `installThemes`, and `EditorLanguage`                                                      |
| `@language-playground/ide/transport`  | `MessageConnection`, `RpcError`, `WorkerRuntime`, worker hosts, protocol validators, and their types                         |
| `@language-playground/ide/lsp`        | `LspLanguageService`                                                                                                         |
| `@language-playground/ide/examples`   | `loadExampleCatalog`, `ExampleCatalogError`, and manifest/error types                                                        |
| `@language-playground/ide/themes`     | `getBuiltinThemes`, `PlaygroundTheme`, `ThemeColors`, `ThemeSyntax`, and `ThemeColor`                                        |
| `@language-playground/ide/styles.css` | Workbench, Mantine, and Monaco styles                                                                                        |

Applications must use these exports instead of importing the package's source or internal output files. The optional [Scala.js API](playground-scalajs-api.md) is a separate source library. Neither the IDE package nor its build depends on it.

```ts
import { mountPlayground } from '@language-playground/ide';
import type { LanguagePlugin, EditorLanguage } from '@language-playground/ide/api';
import '@language-playground/ide/styles.css';

export function openEditor(container: HTMLElement, plugin: LanguagePlugin, editor: EditorLanguage) {
  return mountPlayground(container, { plugin, editor });
}
```

The host must provide an empty, connected element in the current document, give it a height, and retain the returned handle. The workbench fills that element; dialogs can overlay the viewport. Call `handle.dispose()` before removing the host or replacing its language. Disposal saves source, stops execution, releases the language service and editor, removes browser listeners and portals, and leaves saved data intact. Repeated disposal has no effect. Cleanup attempts every step and reports failures with `AggregateError`.

The full application permits one mount per window. Embedded editors support independent instances; see the [embedded API](embedding.md). A second mount throws before changing the existing instance. Workspace keyboard shortcuts are registered on the window while the IDE is mounted. The stylesheet includes the underlying UI libraries' base styles; it is not a Shadow DOM stylesheet. Importing the main entry point does not mount an editor or create a language service. `/editor` configures Monaco's browser-wide worker hook when loaded; use type-only imports from `/api` when only declaring an adapter.

The optional `storage` object implements `getItem` and `setItem`; it defaults to browser local storage. Session/history keys include the language ID, while editor settings are shared. Storage failures remain visible in the IDE and do not discard its in-memory source. Startup selects a share fragment, saved source, the first configured example, or a blank file. A language mismatch falls back to the configured initial example or blank file and reports the mismatch. A consumed share fragment is removed from the current URL. Unsupported language features remain absent.

## Shared core and embedded API

The core's controller owns session revisions, analysis, service lifetime, and execution. Shared React components own the editor/tabs, Run/Stop, and result rendering. The full application and embedded wrapper each depend on those components; neither wrapper depends on the other. Persistence and file management belong to the full application. Fixed tabs, protected files, and host events belong to the embed.

See [embedding.md](embedding.md) for create-call usage, defaults, events, lifetime, and native Monaco access. The complete signatures are included below. All existing language plugin factories work with either presentation. Embedded workspace roots are unique per instance; adapters must use the supplied `workspaceUri` and document URIs instead of assuming `file:///workspace`.

## Color themes

Themes belong to the host application, independently of its language plugin. `/themes` exports `getTheme`, `setTheme`, and `onDidChangeTheme` for page-wide selection and observation. `setTheme` accepts a built-in ID or full custom palette; it changes mounted editors and controls without persisting a preference. Embedded instances inherit that palette when no theme option is given. The full application's theme selector persists its selected preference. `getBuiltinThemes()` returns an immutable, nonempty collection in settings order. It has no browser side effects. `PlaygroundOptions.themes` appends complete custom definitions in the supplied order; `defaultTheme` selects the initial palette when no preference is saved. A saved theme ID takes precedence over that default.

```ts
import { mountPlayground } from '@language-playground/ide';
import { getBuiltinThemes, type PlaygroundTheme } from '@language-playground/ide/themes';
import type { LanguagePlugin, EditorLanguage } from '@language-playground/ide/api';

export function openThemedEditor(container: HTMLElement, plugin: LanguagePlugin, editor: EditorLanguage) {
  const base = getBuiltinThemes()[0];
  const custom: PlaygroundTheme = {
    ...base,
    id: 'studio',
    label: 'Studio',
    colors: { ...base.colors, accent: '#a8c7ff', onAccent: '#12213a' },
    syntax: { ...base.syntax, keyword: '#c8b5ff' },
  };
  return mountPlayground(container, { plugin, editor, themes: [custom], defaultTheme: custom.id });
}
```

Each definition must supply every `ThemeColors` and `ThemeSyntax` role. Colors must be opaque six-digit hexadecimal sRGB strings (`#RRGGBB`); CSS functions, variables, alpha, and named colors are rejected. IDs must match `[a-z][a-z0-9-]*` and contain at most 80 characters. IDs must be unique across built-in and custom themes. Labels must be nonblank and contain at most 80 characters. Unknown fields, invalid definitions, and duplicate IDs throw `TypeError` before mounting or calling plugin factories; validation errors retain their cause. An unknown `defaultTheme` throws `RangeError`. The mount snapshots definitions, so later caller mutations have no effect.

The workbench derives settings choices, commands, CSS variables, control colors, and editor palettes from these definitions. `colorScheme` selects the corresponding UI and Monaco base. `highContrast: true` uses the matching Monaco high-contrast base and visible, opaque control highlights. The flag does not certify accessibility: theme authors must check foreground/background contrast, including muted labels, source selections, and primary button text. `colors.onAccent` is the foreground on filled accent controls; it must contrast with `colors.accent`.

Theme changes preserve editor models, source, undo history, and service instances. The browser saves only the theme ID, using the existing shared settings key. If that ID is unavailable on a later mount, the IDE reports it, selects `defaultTheme`, and keeps the other saved settings. Theme settings are not included in source exports or share links. Existing `dark` and `light` preferences identify Midnight and Daylight.

`installThemes(themes?)` is the lower-level editor-only entry point. It validates the entire supplied, nonempty collection before registering Monaco themes named `playground-${id}`; omitting the argument installs all built-in palettes. It does not select a theme or style a workbench. Repeated calls replace definitions with the same Monaco names. Monaco does not expose theme removal; these registrations remain browser-wide after disposal. The shared editor installs the selected palette before lexical installation and reapplies palette definitions when the page theme changes. Lexical token categories should use the shared `syntax` roles. Theme definitions belong to the host presentation layer.

To add a bundled palette, add one complete definition to [palettes.json](../src/appearance/palettes.json). Settings, commands, editor registration, and tests enumerate the catalog; there is no second registration list.

## Virtual workspaces and archives

Import the public model and archive formats from `@language-playground/ide/workspace`. `Workspace` owns immutable flat `files` and `directories` collections. Files contain stable IDs, relative paths, and text; folder membership is derived from path segments. `readDirectory('')` lists root children, folders first. There is no disk access or hidden filesystem registry.

`addFile`, `addDirectory`, `edit`, `move`, `remove`, and `merge` return validated new workspaces. `move` preserves file IDs throughout a subtree and rejects existing destinations or a move into itself. `merge` replaces matching file contents while retaining the destination IDs; file/folder collisions reject the whole merge. Removing the last child leaves its parent folder present. Snapshots own frozen copies of their inputs.

Paths are case-sensitive and preserve Unicode spelling. Each path has at most 1,024 UTF-16 units and each segment 1–120. Absolute paths, drive prefixes, backslashes, empty segments, `.`/`..`, control characters, and unpaired surrogates are invalid. A file cannot also be a directory or an ancestor of another file. The empty string denotes the implicit root only for `readDirectory` and `parentPath` results.

The workbench stores open tabs separately from workspace contents. Closing a tab preserves source, its editor model, undo history, and entry selection. Deleting an entry saves a checkpoint and removes its descendant files. Moving a path retains file IDs and entry selection; Monaco requires new models for the new URIs, so moves reset the affected editor undo stacks. The preceding workspace remains in local history.

Edits must satisfy the same source size limits as imports. If an editor change exceeds a limit, the workbench reports the failure and restores the accepted source. This resets that file's undo stack because Monaco has already applied the rejected change.

Language services receive all workspace text documents, including files without open tabs. Folder moves close old document URIs before the new URIs are synchronized. The generic LSP adapter sends the same hierarchical URIs in `didOpen`/`didChange`/`didClose` and reports the workspace root during initialization. This supplies virtual documents, not a server-side disk directory: a server that reads imports from disk still needs an adapter that makes synchronized documents available to its resolver. The playground does not infer module names or change import semantics.

`getWorkspaceArchiveFormats()` returns ZIP, TAR, and TAR.GZ format objects. Each owns its ID, label, suffixes, media type, `read(Blob, signal?)`, and `write(Workspace, signal?)`. Import preserves folder prefixes, empty folders, file contents, BOMs, and line endings. TAR accepts USTAR with PAX/GNU extended names. ZIP integrity and TAR header checks run before an import completes. Leading `./` is accepted at this archive boundary; other invalid paths and duplicate normalized entries reject the import.

Archives contain source files and directories, without session metadata. Permissions and timestamps are fixed export defaults. Links, devices, encrypted ZIP entries, malformed UTF-8, and NUL-containing binary files are unsupported and reject the entire import. Inputs are bounded by `maximumArchiveBytes` (16,000,000 bytes); decompressed text must also satisfy `workspaceLimits`. Cancellation rejects without returning partial contents. `WorkspaceArchiveError` identifies codec, text, entry, or size failures and retains the underlying cause. Path and workspace invariant errors retain their own types.

The UI opens an archive as a replacement workspace, shows its file/folder counts and entry selection, and preserves the previous workspace in history before applying it. Plain text file selections merge into the current workspace; replacing existing paths requires confirmation. Project JSON preserves tabs, entry, stdin, file identities, and empty folders. Current writers emit project version 3. Version 1 and 2 readers remain only in [session-store.ts](../src/persistence/session-store.ts) to accept existing browser saves, checkpoints, exports, and share links; they immediately convert to the current model. Removing those readers would require an explicit break in saved-project compatibility.

Adapter API changes are explicit: replace `LanguageDefinition.fileName` with `defaultFilePath`, example file `fileName` with `path`, and example/manifest `entryFileName` with `entryPath`. These authored APIs have no aliases for the old fields.

## API definitions

The following declarations are generated from the versioned TypeScript API, including the plugin contracts, editor installation contract, transport, and provided LSP/runtime implementations. Private implementation members and method bodies are omitted. Doc comments in those source files describe each operation's inputs, results, and failure rules. Run `npm run docs:api` from the project root after changing the API; `npm run check` checks that these declarations remain current.

<!-- api-definitions:start -->

```ts
import type { ReactNode, RefObject } from 'react';
import type * as monaco from 'monaco-editor/editor/editor.api';
import type { RpcMessage } from '@language-playground/ide/transport';

export interface PlaygroundOptions {
  readonly plugin: LanguagePlugin;
  readonly title?: string;
  readonly documentTitle?: string;
  readonly editor: EditorLanguage;
  readonly themes?: readonly PlaygroundTheme[];
  readonly defaultTheme?: string;
  readonly storage?: Pick<Storage, 'getItem' | 'setItem'>;
}

export interface PlaygroundHandle {
  dispose(): void;
}

export declare function mountPlayground(container: HTMLElement, options: PlaygroundOptions): PlaygroundHandle;

export declare function createEmbeddedPlayground(
  container: HTMLElement,
  options: EmbeddedPlaygroundOptions,
): EmbeddedPlaygroundHandle;

export interface EmbeddedFile {
  readonly path: string;
  readonly value?: string;
  readonly readOnly?: boolean;
}

export interface EmbeddedFileSnapshot {
  readonly path: string;
  readonly uri: string;
  readonly value: string;
  readonly readOnly: boolean;
}

export interface EmbeddedPlaygroundUpdate extends Partial<PlaygroundSettings> {
  readonly title?: string;
  readonly ariaLabel?: string;
  readonly breadcrumbs?: boolean;
  readonly panels?: readonly ResultPanelId[];
  readonly panel?: ResultPanelId;
  readonly entryFile?: string;
  readonly entryPoint?: string;
  readonly stdin?: string;
  readonly theme?: string | PlaygroundTheme;
}

export interface EmbeddedPlaygroundOptions extends EmbeddedPlaygroundUpdate {
  readonly plugin: LanguagePlugin;
  readonly editor?: EditorLanguage;
  readonly value?: string;
  readonly files?: readonly EmbeddedFile[];
  readonly activeFile?: string;
  readonly onError?: (error: unknown) => void;
}

export interface EmbeddedContentChange {
  readonly changes: readonly {
    readonly path: string;
    readonly previousValue: string;
    readonly value: string;
  }[];
}

export interface EmbeddedPlaygroundHandle {
  readonly ready: Promise<void>;
  readonly workspaceUri: string;
  getSnapshot(): PlaygroundState;
  getFiles(): readonly EmbeddedFileSnapshot[];
  getValue(path?: string): string;
  setValue(value: string, path?: string): void;
  setValues(
    values: readonly {
      readonly path: string;
      readonly value: string;
    }[],
  ): void;
  getActiveFile(): string;
  setActiveFile(path: string): void;
  setReadOnly(readOnly: boolean, path?: string): void;
  updateOptions(options: EmbeddedPlaygroundUpdate): void;
  getOptions(): Readonly<Required<EmbeddedPlaygroundUpdate>>;
  run(): Promise<void>;
  stop(): void;
  clearOutput(): void;
  clearLogs(): void;
  getDiagnostics(): readonly Diagnostic[];
  restartLanguageService(): Promise<void>;
  focus(): void;
  layout(): void;
  reveal(location: Location): Promise<void>;
  action(identifier: string): Promise<void>;
  getEditor(): monaco.editor.IStandaloneCodeEditor | null;
  onDidChangeContent(listener: (event: EmbeddedContentChange) => void): monaco.IDisposable;
  onDidChangeState(listener: (state: PlaygroundState) => void): monaco.IDisposable;
  onDidError(listener: (error: unknown) => void): monaco.IDisposable;
  onDidDispose(listener: () => void): monaco.IDisposable;
  isDisposed(): boolean;
  dispose(): void;
}

export declare function getTheme(): PlaygroundTheme;

export declare function setTheme(theme: string | PlaygroundTheme): void;

export declare function onDidChangeTheme(listener: () => void): () => void;

export interface ThemeProviderProps {
  readonly host: HTMLElement;
  readonly children: ReactNode;
}

export declare function ThemeProvider({ host, children }: ThemeProviderProps): ReactNode;

export type ServiceState =
  | {
      readonly kind: 'starting';
    }
  | {
      readonly kind: 'ready';
      readonly service: LanguageService;
    }
  | {
      readonly kind: 'unavailable';
    }
  | {
      readonly kind: 'failed';
      readonly message: string;
    };

export type ExecutionState =
  | {
      readonly kind: 'idle';
    }
  | {
      readonly kind: 'running';
      readonly revision: number;
      readonly startedAt: number;
      readonly output: readonly OutputChunk[];
    }
  | {
      readonly kind: 'finished';
      readonly revision: number;
      readonly duration: number;
      readonly output: readonly OutputChunk[];
      readonly result: ExecutionResult;
    }
  | {
      readonly kind: 'stopped';
      readonly revision: number;
      readonly duration: number;
      readonly output: readonly OutputChunk[];
      readonly reason: string;
    };

export interface WorkspaceDocument {
  readonly fileId: string;
  readonly source: SourceDocument;
  readonly diagnostics: readonly Diagnostic[];
  readonly symbols: readonly DocumentSymbol[];
  readonly readOnly: boolean;
}

export interface PlaygroundState {
  readonly session: Session;
  readonly settings: PlaygroundSettings;
  readonly revision: number;
  readonly workspaceVersion: number;
  readonly documents: readonly WorkspaceDocument[];
  readonly service: ServiceState;
  readonly logs: readonly ServiceLog[];
  readonly execution: ExecutionState;
}

export interface PlaygroundControllerOptions {
  readonly plugin: LanguagePlugin;
  readonly session: Session;
  readonly workspaceUri: string;
  readonly settings?: Partial<PlaygroundSettings>;
  readonly readOnlyFileIds?: readonly string[];
}

export declare class ReadOnlyDocumentError extends Error {
  readonly path: string;
  constructor(path: string);
}

export declare class PlaygroundController {
  readonly plugin: LanguagePlugin;
  readonly workspaceUri: string;
  constructor(options: PlaygroundControllerOptions);
  readonly getSnapshot: () => PlaygroundState;
  readonly subscribe: (listener: () => void) => () => void;
  isReadOnly(fileId: string): boolean;
  setReadOnly(fileId: string, readOnly: boolean): void;
  connect(): Promise<void>;
  document(fileId: string): SourceDocument;
  selectFile(fileId: string): void;
  edit(fileId: string, text: string): void;
  updateSession(input: Session, change?: Partial<PlaygroundSettings>): void;
  configure(change: Partial<PlaygroundSettings>): void;
  synchronizeDocuments(): void;
  run(): Promise<void>;
  stop(reason?: string): void;
  clearOutput(): void;
  clearLogs(): void;
  dispose(): void;
}

export declare const maximumInputBytes: number;

export interface Session {
  readonly languageId: string;
  readonly workspace: Workspace;
  readonly openFileIds: readonly string[];
  readonly activeFileId: string | null;
  readonly entryFileId: string | null;
  readonly entryPoint: string;
  readonly stdin: string;
}

export declare function readSession(input: Session): Session;

export interface PlaygroundSettings {
  readonly fontSize: number;
  readonly tabSize: number;
  readonly wordWrap: boolean;
  readonly minimap: boolean;
  readonly lineNumbers: boolean;
  readonly inlayHints: boolean;
  readonly semanticHighlighting: boolean;
  readonly autoRun: boolean;
  readonly timeout: number;
}

export declare const defaultPlaygroundSettings: PlaygroundSettings;

export declare function currentDiagnostics(state: PlaygroundState): Diagnostic[];

export interface PlaygroundSurfaceProps {
  readonly controller: PlaygroundController;
  readonly editorLanguage: EditorLanguage;
  readonly editorRef?: RefObject<EditorHandle | null>;
  readonly panel: ResultPanelId;
  readonly onPanelChange: (panel: ResultPanelId) => void;
  readonly onError: (error: unknown) => void;
  readonly onPosition?: (line: number, column: number, selected: number) => void;
  readonly onEditorReady?: (editor: EditorHandle) => void;
  readonly tabActions?: ReactNode;
  readonly onCloseFile?: (fileId: string) => void;
  readonly emptyContent?: ReactNode;
  readonly breadcrumbs?: boolean;
  readonly panels?: readonly ResultPanelId[];
  readonly copy?: (text: string) => void;
  readonly download?: (name: string, text: string, mediaType?: string) => void;
}

export declare function PlaygroundSurface({
  controller,
  editorLanguage,
  editorRef: suppliedEditorRef,
  panel,
  onPanelChange,
  onError,
  onPosition,
  onEditorReady,
  tabActions,
  onCloseFile,
  emptyContent,
  breadcrumbs = true,
  panels,
  copy,
  download,
}: PlaygroundSurfaceProps): ReactNode;

export interface RunButtonProps {
  readonly state: PlaygroundState;
  readonly run: () => void;
  readonly stop: () => void;
}

export declare function RunButton({ state, run, stop }: RunButtonProps): ReactNode;

export interface WorkspaceSearchPanelProps {
  readonly workspace: Workspace;
  readonly onSelect: (fileId: string, match: WorkspaceSearchMatch) => void;
  readonly active?: boolean;
  readonly autoFocus?: boolean;
  readonly inputRef?: Ref<HTMLInputElement>;
  readonly limit?: number;
  readonly timeout?: number;
}

export declare function WorkspaceSearchPanel({
  workspace,
  onSelect,
  active = true,
  autoFocus = false,
  inputRef,
  limit = 1000,
  timeout = 2000,
}: WorkspaceSearchPanelProps): ReactNode;

export type ResultPanelId = 'output' | 'problems' | 'input' | 'inspector' | 'logs';

export interface EditorHandle {
  action(identifier: string): Promise<void>;
  reveal(location: Location): void;
  focus(): void;
  getEditor(): monaco.editor.IStandaloneCodeEditor | null;
}

export type ThemeColor = `#${string}`;

export interface ThemeColors {
  readonly background: ThemeColor;
  readonly surface: ThemeColor;
  readonly raised: ThemeColor;
  readonly hover: ThemeColor;
  readonly border: ThemeColor;
  readonly text: ThemeColor;
  readonly muted: ThemeColor;
  readonly accent: ThemeColor;
  readonly onAccent: ThemeColor;
  readonly selection: ThemeColor;
  readonly error: ThemeColor;
  readonly warning: ThemeColor;
  readonly success: ThemeColor;
  readonly info: ThemeColor;
}

export interface ThemeSyntax {
  readonly comment: ThemeColor;
  readonly keyword: ThemeColor;
  readonly type: ThemeColor;
  readonly function: ThemeColor;
  readonly string: ThemeColor;
  readonly number: ThemeColor;
  readonly variable: ThemeColor;
  readonly parameter: ThemeColor;
  readonly property: ThemeColor;
  readonly constant: ThemeColor;
  readonly namespace: ThemeColor;
}

export interface PlaygroundTheme {
  readonly id: string;
  readonly label: string;
  readonly colorScheme: 'dark' | 'light';
  readonly highContrast?: boolean;
  readonly colors: ThemeColors;
  readonly syntax: ThemeSyntax;
}

export declare function getBuiltinThemes(): readonly [PlaygroundTheme, ...PlaygroundTheme[]];

export interface Position {
  readonly line: number;
  readonly character: number;
}

export interface Range {
  readonly start: Position;
  readonly end: Position;
}

export interface Location {
  readonly uri: string;
  readonly range: Range;
}

export interface SourceDocument {
  readonly uri: string;
  readonly languageId: string;
  readonly text: string;
  readonly version: number;
}

export interface Diagnostic {
  readonly message: string;
  readonly severity: 'error' | 'warning' | 'info' | 'hint';
  readonly location?: Location;
  readonly source?: string;
  readonly code?: string | number;
  readonly data?: unknown;
}

export interface DocumentSymbol {
  readonly name: string;
  readonly detail?: string;
  readonly kind: 'module' | 'type' | 'function' | 'variable' | 'member';
  readonly location: Location;
  readonly children: DocumentSymbol[];
}

export interface Hover {
  readonly text: string;
  readonly range?: Range;
}

export interface Completion {
  readonly label: string;
  readonly insertText: string;
  readonly detail?: string;
  readonly kind: 'keyword' | 'function' | 'type' | 'variable' | 'field' | 'constructor' | 'module' | 'snippet';
  readonly range?:
    | Range
    | {
        insert: Range;
        replace: Range;
      };
  readonly snippet?: boolean;
  readonly documentation?: string;
  readonly additionalEdits?: TextEdit[];
  readonly sortText?: string;
  readonly filterText?: string;
  readonly commitCharacters?: string[];
  readonly resolve?: (signal: AbortSignal) => Promise<Completion>;
  readonly onAccepted?: () => Promise<void>;
}

export interface CompletionBatch {
  readonly items: Completion[];
  readonly isIncomplete: boolean;
}

export interface TextEdit {
  readonly range: Range;
  readonly text: string;
}

export interface DocumentEdits {
  readonly uri: string;
  readonly version?: number;
  readonly edits: readonly TextEdit[];
}

export interface LanguageCommand {
  readonly title: string;
  readonly execute: (signal: AbortSignal) => Promise<void>;
}

export interface InlayHintLabelPart {
  readonly value: string;
  readonly tooltip?: string;
  readonly location?: Location;
  readonly command?: LanguageCommand;
}

export interface InlayHint {
  readonly position: Position;
  readonly label: string | readonly InlayHintLabelPart[];
  readonly kind?: 'type' | 'parameter';
  readonly tooltip?: string;
  readonly paddingLeft?: boolean;
  readonly paddingRight?: boolean;
  readonly textEdits?: readonly TextEdit[];
  readonly resolve?: (signal: AbortSignal) => Promise<InlayHint>;
}

export interface SemanticToken {
  readonly range: Range;
  readonly type: string;
  readonly modifiers: readonly string[];
}

export interface SemanticTokenLegend {
  readonly tokenTypes: readonly string[];
  readonly tokenModifiers: readonly string[];
}

export interface CodeAction {
  readonly title: string;
  readonly kind?: string;
  readonly diagnostics?: readonly Diagnostic[];
  readonly isPreferred?: boolean;
  readonly disabled?: string;
  readonly edit?: readonly DocumentEdits[];
  readonly command?: LanguageCommand;
  readonly resolve?: (signal: AbortSignal) => Promise<CodeAction>;
}

export interface CodeActionContext {
  readonly diagnostics: readonly Diagnostic[];
  readonly only?: readonly string[];
  readonly trigger: 'automatic' | 'invoked';
}

export interface SignatureHelp {
  readonly signatures: {
    label: string;
    documentation?: string;
    activeParameter?: number | null;
    parameters: {
      label: string | [number, number];
      documentation?: string;
    }[];
  }[];
  readonly activeSignature: number;
  readonly activeParameter: number | null;
}

export interface AnalysisUpdate {
  readonly uri: string;
  readonly version?: number;
  readonly diagnostics: Diagnostic[];
}

export interface ServiceLog {
  readonly level: 'error' | 'warning' | 'info';
  readonly message: string;
}

export interface LanguageCapabilities {
  hover?(document: SourceDocument, position: Position, signal: AbortSignal): Promise<Hover | null>;
  definitions?(document: SourceDocument, position: Position, signal: AbortSignal): Promise<Location[]>;
  references?(document: SourceDocument, position: Position, signal: AbortSignal): Promise<Location[]>;
  symbols?(document: SourceDocument, signal: AbortSignal): Promise<DocumentSymbol[]>;
  readonly completions?: {
    triggerCharacters: readonly string[];
    provide(document: SourceDocument, position: Position, signal: AbortSignal): Promise<CompletionBatch>;
  };
  readonly inlayHints?: {
    provide(document: SourceDocument, range: Range, signal: AbortSignal): Promise<readonly InlayHint[]>;
    onDidChange?(listener: () => void): () => void;
  };
  readonly semanticTokens?: {
    readonly legend: SemanticTokenLegend;
    provide(document: SourceDocument, signal: AbortSignal): Promise<readonly SemanticToken[]>;
    onDidChange?(listener: () => void): () => void;
  };
  readonly codeActions?: {
    readonly kinds?: readonly string[];
    provide(
      document: SourceDocument,
      range: Range,
      context: CodeActionContext,
      signal: AbortSignal,
    ): Promise<readonly CodeAction[]>;
  };
  format?(
    document: SourceDocument,
    options: {
      tabSize: number;
      insertSpaces: boolean;
    },
    signal: AbortSignal,
  ): Promise<TextEdit[]>;
  rename?(
    document: SourceDocument,
    position: Position,
    name: string,
    signal: AbortSignal,
  ): Promise<readonly DocumentEdits[]>;
  readonly signature?: {
    triggerCharacters: readonly string[];
    retriggerCharacters: readonly string[];
    provide(document: SourceDocument, position: Position, signal: AbortSignal): Promise<SignatureHelp | null>;
  };
  folding?(
    document: SourceDocument,
    signal: AbortSignal,
  ): Promise<
    {
      startLine: number;
      endLine: number;
    }[]
  >;
}

export interface LanguageService {
  readonly capabilities: Readonly<LanguageCapabilities>;
  start(): Promise<void>;
  synchronize(document: SourceDocument): void;
  close(uri: string): void;
  onDiagnostics(listener: (update: AnalysisUpdate) => void): () => void;
  onLog(listener: (log: ServiceLog) => void): () => void;
  onFailure(listener: (error: Error) => void): () => void;
  dispose(): void;
}

export interface ExecutionInput {
  readonly documents: readonly SourceDocument[];
  readonly entryDocumentUri: string;
  readonly entryPoint: string;
  readonly stdin: string;
}

export interface OutputChunk {
  readonly channel: 'stdout' | 'stderr';
  readonly text: string;
}

export interface Artifact {
  readonly name: string;
  readonly mediaType: string;
  readonly content: string;
}

export interface ExecutionResult {
  readonly status: 'success' | 'error';
  readonly value?: string;
  readonly diagnostics: Diagnostic[];
  readonly artifacts: Artifact[];
}

export interface LanguageRuntime {
  execute(input: ExecutionInput, emit: (chunk: OutputChunk) => void, signal: AbortSignal): Promise<ExecutionResult>;
}

export interface Example {
  readonly id: string;
  readonly title: string;
  readonly description: string;
  readonly files: readonly {
    readonly path: string;
    readonly text: string;
  }[];
  readonly directories?: readonly string[];
  readonly entryPath: string;
  readonly entryPoint?: string;
  readonly stdin?: string;
}

export interface LanguageDefinition {
  readonly id: string;
  readonly name: string;
  readonly extension: string;
  readonly defaultFilePath: string;
  readonly defaultEntryPoint: string;
  readonly examples: readonly Example[];
}

export interface LanguagePlugin {
  readonly definition: LanguageDefinition;
  createRuntime(): LanguageRuntime;
  createLanguageService?(context: { workspaceUri: string }): LanguageService;
}

export type WorkspacePath = string & {
  readonly __workspacePath: unique symbol;
};

export declare class WorkspacePathError extends Error {
  readonly path: string;
  constructor(path: string);
}

export declare function parseWorkspacePath(value: string): WorkspacePath;

export declare function pathName(path: WorkspacePath): string;

export declare function parentPath(path: WorkspacePath): WorkspacePath | '';

export declare function withinPath(path: WorkspacePath, directory: WorkspacePath): boolean;

export declare function workspaceDocumentUri(rootUri: string, path: WorkspacePath): string;

export declare const workspaceLimits: Readonly<{
  files: number;
  directories: number;
  fileBytes: number;
  totalBytes: number;
}>;

export interface WorkspaceFile {
  readonly id: string;
  readonly path: WorkspacePath;
  readonly text: string;
}

export interface WorkspaceSnapshot {
  readonly files: readonly WorkspaceFile[];
  readonly directories: readonly WorkspacePath[];
}

export interface WorkspaceInput {
  readonly files: readonly {
    readonly id: string;
    readonly path: string;
    readonly text: string;
  }[];
  readonly directories?: readonly string[];
}

export type WorkspaceEntry =
  | {
      readonly kind: 'file';
      readonly file: WorkspaceFile;
      readonly path: WorkspacePath;
    }
  | {
      readonly kind: 'directory';
      readonly path: WorkspacePath;
    };

export type WorkspaceErrorCode =
  'invalid-data' | 'duplicate-id' | 'path-conflict' | 'missing-entry' | 'invalid-move' | 'limit';

export declare class WorkspaceError extends Error {
  readonly code: WorkspaceErrorCode;
  readonly path?: string;
  constructor(code: WorkspaceErrorCode, message: string, path?: string, options?: ErrorOptions);
}

export declare class Workspace {
  constructor(input: WorkspaceInput);
  static parse(value: unknown): Workspace;
  get snapshot(): WorkspaceSnapshot;
  get files(): readonly WorkspaceFile[];
  get directories(): readonly WorkspacePath[];
  file(id: string): WorkspaceFile | undefined;
  entry(path: string): WorkspaceEntry | undefined;
  readDirectory(path?: string): readonly WorkspaceEntry[];
  addFile(file: WorkspaceInput['files'][number]): Workspace;
  addDirectory(path: string): Workspace;
  merge(incoming: Workspace): Workspace;
  edit(id: string, text: string): Workspace;
  move(source: string, destination: string): Workspace;
  remove(path: string): Workspace;
}

export interface WorkspaceSearchOptions {
  readonly caseSensitive?: boolean;
  readonly wholeWord?: boolean;
  readonly regularExpression?: boolean;
  readonly limit?: number;
}

export interface WorkspaceSearchMatch {
  readonly range: Range;
  readonly preview: {
    readonly before: string;
    readonly match: string;
    readonly after: string;
  };
}

export interface WorkspaceFileMatches {
  readonly fileId: string;
  readonly path: WorkspaceFile['path'];
  readonly matches: readonly WorkspaceSearchMatch[];
}

export interface WorkspaceSearchResult {
  readonly files: readonly WorkspaceFileMatches[];
  readonly count: number;
  readonly truncated: boolean;
}

export declare class WorkspaceSearchError extends Error {
  readonly code: 'invalid-pattern' | 'invalid-limit';
  constructor(code: 'invalid-pattern' | 'invalid-limit', message: string, options?: ErrorOptions);
}

export declare function searchWorkspace(
  workspace: Workspace,
  query: string,
  options?: WorkspaceSearchOptions,
): WorkspaceSearchResult;

export declare const maximumArchiveBytes: number;

export type WorkspaceArchiveErrorCode =
  'invalid-archive' | 'unsupported-entry' | 'invalid-text' | 'limit' | 'duplicate-entry';

export declare class WorkspaceArchiveError extends Error {
  readonly code: WorkspaceArchiveErrorCode;
  readonly entry?: string;
  constructor(code: WorkspaceArchiveErrorCode, message: string, entry?: string, options?: ErrorOptions);
}

export interface WorkspaceArchiveFormat {
  readonly id: string;
  readonly label: string;
  readonly extensions: readonly string[];
  readonly mediaType: string;
  read(archive: Blob, signal?: AbortSignal): Promise<Workspace>;
  write(workspace: Workspace, signal?: AbortSignal): Promise<Blob>;
}

export declare function getWorkspaceArchiveFormats(): readonly WorkspaceArchiveFormat[];

export interface ExampleManifest extends Omit<Example, 'files'> {
  readonly order?: number;
}

export type ExampleCatalogErrorCode =
  | 'invalid-path'
  | 'duplicate-asset'
  | 'missing-manifest'
  | 'invalid-manifest'
  | 'duplicate-id'
  | 'missing-entry'
  | 'invalid-workspace';

export declare class ExampleCatalogError extends Error {
  readonly code: ExampleCatalogErrorCode;
  readonly assetPath: string;
  constructor(code: ExampleCatalogErrorCode, assetPath: string, message: string, options?: ErrorOptions);
}

export declare function loadExampleCatalog(input: Readonly<Record<string, string>>): readonly Example[];

export interface EditorLanguageRegistration {
  readonly id: string;
  readonly definition: LanguageDefinition;
}

export interface EditorLanguage {
  install(editor: typeof monaco, registration: EditorLanguageRegistration): monaco.IDisposable;
}

export declare function installThemes(themes?: readonly PlaygroundTheme[]): void;

export interface MessageEndpoint {
  postMessage(message: unknown): void;
  addEventListener(type: 'message', listener: (event: MessageEvent) => void): void;
  addEventListener(type: 'error', listener: (event: ErrorEvent) => void): void;
  removeEventListener(type: 'message', listener: (event: MessageEvent) => void): void;
  removeEventListener(type: 'error', listener: (event: ErrorEvent) => void): void;
  terminate(): void;
}

export declare class RpcError extends Error {
  readonly code: number;
  readonly data?: unknown;
  constructor(message: string, code: number, data?: unknown);
}

export declare class MessageConnection {
  constructor(endpoint: MessageEndpoint, timeout?: number);
  onNotification(listener: (method: string, params: unknown) => void): () => void;
  onFailure(listener: (error: Error) => void): () => void;
  notify(method: string, params: unknown): void;
  request<T>(method: string, params: unknown, signal?: AbortSignal): Promise<T>;
  dispose(reason?: Error): void;
}

export declare class WorkerRuntime implements LanguageRuntime {
  constructor(createWorker: () => MessageEndpoint);
  execute(input: ExecutionInput, emit: (chunk: OutputChunk) => void, signal: AbortSignal): Promise<ExecutionResult>;
}

export interface WorkerScope {
  postMessage(message: unknown): void;
  addEventListener(type: 'message', listener: (event: MessageEvent<unknown>) => void): void;
  removeEventListener(type: 'message', listener: (event: MessageEvent<unknown>) => void): void;
  reportError(error: unknown): void;
}

export interface ExecutionBackend {
  execute(input: ExecutionInput, emit: (chunk: OutputChunk) => void): ExecutionResult | Promise<ExecutionResult>;
}

export interface JsonRpcBackend {
  receive(message: RpcMessage): void | Promise<void>;
  dispose?(): void;
}

export declare function serveExecution(scope: WorkerScope, backend: ExecutionBackend): () => void;

export declare function serveJsonRpc(
  scope: WorkerScope,
  createBackend: (emit: (message: RpcMessage) => void) => JsonRpcBackend,
): () => void;

export declare class LspLanguageService implements LanguageService {
  get capabilities(): Readonly<LanguageCapabilities>;
  constructor(connection: MessageConnection, workspaceUri: string);
  start(): Promise<void>;
  synchronize(document: SourceDocument): void;
  close(uri: string): void;
  onDiagnostics(listener: (update: AnalysisUpdate) => void): () => void;
  onLog(listener: (log: ServiceLog) => void): () => void;
  onFailure(listener: (error: Error) => void): () => void;
  dispose(): void;
}
```

<!-- api-definitions:end -->

## Contract and integration requirements

This guide is for language developers connecting an existing compiler, interpreter or language service to the universal playground. Implement the language at the adapter boundary. The compiler's internal syntax trees, type representations, source model and execution engine remain its own design choices.

The playground provides a virtual filesystem backed by flat file and folder records, Monaco editing, commands, diagnostics, execution controls, persistence and sharing. One language is selected for an application instance. A service capability can be absent without removing the rest of the IDE. Multi-language documents in one workspace and runtime language switching are not current contracts.

## 1. Identify the integration boundaries

The authoritative types are in [core/contracts.ts](../src/core/contracts.ts). The editor-specific installation contract is in [editor/monaco.ts](../src/editor/monaco.ts). Read those definitions when implementing an adapter; this guide explains their behavior and ownership.

| Contract               | Your implementation owns                                                                        | Required?                                   |
| ---------------------- | ----------------------------------------------------------------------------------------------- | ------------------------------------------- |
| `LanguageDefinition`   | Stable language ID, display name, extension, initial filename, entry-point default and examples | Yes                                         |
| `LanguagePlugin`       | Factories for independent runtime/service instances and the language definition                 | Yes                                         |
| `LanguageRuntime`      | Execution of a supplied workspace snapshot, output, cancellation and results                    | Yes                                         |
| `LanguageService`      | Document synchronization, semantic requests, diagnostics and service lifetime                   | Optional                                    |
| `LanguageCapabilities` | Only the semantic operations the service actually implements                                    | Each operation is optional                  |
| `EditorLanguage`       | Lexical highlighting, comment/bracket configuration and optional syntax snippets                | Yes; registrations depend on language needs |

Your application owns composition. Call `mountPlayground(container, { plugin, editor })` with your implementations. Keep the adapter, examples, workers, and compiler build in your application, outside the IDE package. Shared workspace, persistence, transport and editor service code depend on neutral contracts. [Package lint rules](../eslint.config.js) enforce those import boundaries.

The compiler need not implement the same phases or APIs as any supplied language. Translate its existing APIs into the neutral contracts. If it lacks a capability, leave that capability absent. Integrating the playground does not require changing a compiler's design or adding language features.

## 2. Define metadata and examples coherently

`LanguageDefinition.id` is an open, stable identifier used for document language identity and persistence namespacing. Choose it deliberately; changing it changes where saved sessions are found. Names, file extensions, entry conventions and example programs belong in this definition or the adapter, never in shared workspace dispatch branches.

`extension` includes its leading dot. `defaultFilePath` is the relative path of the initial blank file. `defaultEntryPoint` expresses your runtime's entry convention. A script language without a named entry can use an empty default and let its runtime execute the selected document; explain that convention in the language's documentation. Do not infer entry identity from which tab happens to be active.

Each `Example` contains a complete `files` collection and an `entryPath` belonging to it, with optional entry-point/stdin overrides. Use valid programs executed by the real runtime. Give each file a unique relative `path`; use `/` between folder segments. Optional `directories` retain empty folders. Examples, loaded files and new files use the same execution and analysis APIs.

The examples array defines display order. Its first item is the initial workspace when no saved or shared session is available. An empty array starts with the language's default file path and empty source.

### File-based example catalogs

[loadExampleCatalog](../src/workspace/examples.ts) constructs `Example[]` from a path-to-text asset map. Each example starts at a directory containing `example.json`. Descendant files belong to their nearest ancestor manifest, regardless of extension; source subfolders need no manifest. A nested manifest starts a separate example. Put documentation outside the example directory unless it is intended to open as a workspace file.

```text
examples/
  recursion/
    example.json
    main.lang
    helpers.lang
```

The manifest implements the generated `ExampleManifest` definition above:

```json
{
  "id": "recursion",
  "title": "Recursion",
  "description": "Calls between functions in two files.",
  "entryPath": "main.lang",
  "order": 10
}
```

`id`, `title`, `description`, and `entryPath` are required. Optional `entryPoint` and `stdin` override the language defaults. Optional `directories` lists empty folders. Optional integer `order` defaults to zero. Results sort by ascending order, then ID using code-unit order; directory names and filesystem enumeration do not select an example's identity or priority. The entry file comes first, followed by other files in path order. Source and standard input are preserved exactly.

An adapter using Vite can discover assets in one place:

```ts
import { loadExampleCatalog } from '@language-playground/ide/examples';

const examples = loadExampleCatalog(
  import.meta.glob<string>('./examples/**/*', { query: '?raw', import: 'default', eager: true }),
);
```

Supply this array as `LanguageDefinition.examples`. Adding an example requires its manifest and source files; plugin code and a central registration list need no edit. Test your catalog by running every discovered example through your own runtime.

Asset paths must be relative POSIX paths, optionally starting with `./`. The loader rejects duplicate paths, malformed manifests, duplicate IDs, missing entry files, orphaned assets, and invalid workspaces. Source subdirectories preserve their relative paths. Failures throw `ExampleCatalogError` with a structured `code`, original `assetPath`, and underlying cause. The catalog fails as a whole. Editing, examples, and persistence share [path validation](../src/workspace/path.ts) and the [workspace model](../src/workspace/model.ts).

This loader is optional and has no filesystem, bundler, Scala.js, or compiler dependency. Other adapters can supply `Example[]` through another content source.

The workspace model applies `workspaceLimits` to every constructor and edit: 50 files, 200 folders (including inferred parents), 512,000 UTF-8 bytes per file, and 2,000,000 source bytes in total. Standard input is limited to 64,000 UTF-8 bytes by [session.ts](../src/core/session.ts). These are playground resource limits, independent of compiler semantics.

## 3. Honor document identity and source coordinates

A `SourceDocument` contains `uri`, `languageId`, immutable `text` and `version`. Treat the supplied URI as document identity and the text/version pair as a snapshot. Workspace URIs encode each relative path segment independently under the supplied root. For example, `src/my file.lang` becomes `file:///workspace/src/my%20file.lang`; `/` remains a directory separator. Use `workspaceDocumentUri` when constructing a URI from a validated path. Do not split arbitrary URI strings to recover filesystem paths; translate them through the appropriate URI/path API at your boundary when needed.

All neutral positions are **zero-based UTF-16** line/character positions. Ranges have an exclusive end. Convert from byte offsets, Unicode scalar indexes, one-based lines or compiler-specific spans before returning a result. Use the exact analyzed text, including its line endings, for conversion. `🌍` occupies two UTF-16 code units; tabs count as source characters rather than visual indentation width.

Preserve these invariants:

- A location refers to the same snapshot used to compute its semantic result.
- A range belongs to its document, with end at or after start; zero-width EOF locations are valid.
- File URI identity is separate from language-level module identity. The compiler owns module/import rules.
- Closing a tab leaves the file and its synchronized document available. Moving or deleting a file closes the old URI; folder operations apply to every descendant. A recreated URI can begin a new document lifetime; discard state from the old lifetime.
- Active-file selection does not change entry-file identity or compiler semantics.

Cross-file definitions, references, rename edits and diagnostics use the original workspace URIs. The workspace can open only files it owns. Package/library sources must not be represented as a guessed workspace file just to create a navigable location.

## 4. Implement execution and isolation

`LanguageRuntime.execute(input, emit, signal)` consumes an immutable `ExecutionInput`:

| Input              | Meaning                                                   |
| ------------------ | --------------------------------------------------------- |
| `documents`        | Complete source snapshot for this run, with distinct URIs |
| `entryDocumentUri` | The selected entry document; it belongs to `documents`    |
| `entryPoint`       | Entry name or convention interpreted by the runtime       |
| `stdin`            | Standard input supplied to this run                       |

Supply all relevant documents to your normal module compiler or interpreter. Resolve dependencies under the language's existing rules. Do not reduce execution to the active document or make array order define entry identity. Package discovery, native bindings and source loading belong to the language adapter/compiler boundary, not the workspace.

Stream output with `emit({ channel: 'stdout' | 'stderr', text })`. Return an `ExecutionResult` with `status`, diagnostics, an optional displayed value and optional artifacts. Artifacts are named text products with a media type; the contract does not carry arbitrary live compiler objects or binary object graphs. Print/render a value only at this boundary.

The `diagnostics` and `artifacts` arrays are required even when empty; the displayed `value` is optional.

Expected source, compile or runtime failures should return an error result with useful diagnostics. Infrastructure failures may reject with an `Error`; the workbench reports them as failed execution. Preserve structured causes until the boundary where they are converted, and do not parse error-message strings to recover locations or categories.

The abort signal means **stop the underlying computation and release its resources**. Handling only the eventual promise result is insufficient for synchronous JavaScript or Wasm loops. Use an execution context that can be terminated, normally a fresh worker per run. The workbench aborts on Stop, replacement runs and configured limits; the default run limit is 10 seconds, configurable from 1–60 seconds, with an output limit of 256,000 UTF-16 code units.

[WorkerRuntime](../src/transport/worker-runtime.ts) provides a fresh owned worker connection per execution and terminates it on completion, cancellation or failure. It is optional: another runtime implementation can provide equivalent isolation and cancellation. Keep compiler work off the main thread. The existing application has no native-process launcher or hosted compilation server; a transport requiring one must supply its own integration and lifecycle.

### Optional worker execution protocol

The `/transport` entry point also exports Zod validators with these output contracts. `parse(value: unknown)` returns the validated value or throws `ZodError`; `safeParse` returns the corresponding Zod success/failure result. Unknown fields are removed.

```ts
import type { ZodType } from 'zod';
import type { ExecutionInput, ExecutionResult, OutputChunk } from '@language-playground/ide/api';
import type { RpcMessage } from '@language-playground/ide/transport';

export declare const rpcMessageSchema: ZodType<RpcMessage>;
export declare const executionInputSchema: ZodType<ExecutionInput>;
export declare const executionResultSchema: ZodType<ExecutionResult>;
export declare const outputChunkSchema: ZodType<OutputChunk>;
```

`rpcMessageSchema` accepts one JSON-RPC object and rejects batches or conflicting envelope fields. The execution-input validator requires distinct document URIs and an entry URI belonging to that snapshot. Result validation checks diagnostic positions and ordered ranges. These validators do not type-check source or infer language semantics.

Reuse [protocol.ts](../src/transport/protocol.ts) and [MessageConnection](../src/transport/rpc.ts) when using `WorkerRuntime`:

| Message          | Required shape/behavior                                                                                                |
| ---------------- | ---------------------------------------------------------------------------------------------------------------------- |
| Request          | JSON-RPC 2.0 `runtime/execute`, request ID and `params` containing the neutral `ExecutionInput`                        |
| Output           | JSON-RPC notification `runtime/output` with an `OutputChunk` in `params`                                               |
| Success response | Same request ID and `result` containing an `ExecutionResult`; a language error is represented by its `status: 'error'` |
| Protocol failure | Same request ID and a JSON-RPC `error` with numeric code and message                                                   |

The endpoint exchanges structured message objects. If a compiled API accepts or returns JSON strings, parse/serialize those strings inside its adapter before crossing the message endpoint. Validate inputs and outputs with the provided schemas or an equivalent boundary validator. Keep request IDs and exactly-one-response semantics intact.

[serveExecution](../src/transport/worker-host.ts) implements the worker side of this protocol. Supply an `ExecutionBackend` with a synchronous or asynchronous `execute` method. The host validates input, streamed chunks and results, correlates request failures, and rejects overlapping executions. Its returned disposer detaches the host and suppresses late output; the owning `WorkerRuntime` terminates the worker to stop computation.

`serveJsonRpc` connects a message-server backend to the same worker transport. The factory receives an outbound sink for replies and unsolicited notifications. Incoming calls begin in arrival order and may complete asynchronously; the backend owns document-state ordering and cancellation. Unsupported editor features remain the backend's responsibility to omit. Invalid envelopes and notification-processing failures close the host through a worker error. Explicit disposal releases backend subscriptions once; worker termination does not guarantee a disposal callback.

Scala.js implementations can use the [optional Scala.js API](playground-scalajs-api.md) to construct execution results and export backends directly. This is a dependency of the language binding. The shared playground and the neutral host functions do not import it; other language implementations can use the same worker contracts or their own transports.

`MessageConnection` owns listeners, correlation, timeouts, request cancellation and endpoint termination. A worker endpoint implements its `MessageEndpoint` contract. Disposing a connection rejects pending requests and terminates that endpoint; do not share an endpoint across owners whose disposal lifetimes differ.

## 5. Implement language-service lifetime

Omit `createLanguageService` when no semantic service is available. The IDE reports that state explicitly. If supplied, the factory receives a `workspaceUri` and must produce a service whose resources belong to that instance.

The workbench uses this lifecycle:

1. Create the service and register diagnostic, log and failure listeners.
2. Await `start()` while initialization and capability discovery complete.
3. Read the negotiated capabilities and synchronize the current documents.
4. Send edits/removals and issue interactive requests against current snapshots.
5. Dispose the old service on restart or workspace teardown; a restart creates a fresh instance.

`synchronize(document)` returns synchronously. Record or enqueue the update immediately, preserving ordering so a following request sees the latest supplied document collection. Background semantic analysis may complete asynchronously. Repeated synchronization of the same version should be harmless. `close(uri)` must remove the corresponding document state and invalidate analysis that depended on it.

Capabilities may be established during `start()`, then remain fixed for that service lifetime. Advertise only implemented operations. An unavailable operation is absent; a valid request with no semantic matches returns its contract's empty or null answer. Do not use a fabricated empty implementation to advertise a feature the compiler does not have.

Every operation receives an `AbortSignal`. Reject canceled work or otherwise settle it according to the adapter's request contract; avoid reporting cancellation as a new compiler diagnostic. Release subscriptions, listeners, pending operations and owned workers in `dispose()`. A failure callback reports a broken service, while ordinary invalid source produces diagnostics and leaves the service usable.

The editor checks both model and workspace revisions before accepting asynchronous results. The adapter still owns snapshot consistency, ordering and cancellation; stale-result rejection is not permission to return ranges computed from a different input.

## 6. Supply diagnostics and semantic operations

### Diagnostics

Publish `AnalysisUpdate` values with a target document URI, its analyzed version when available, and a complete replacement diagnostic array. Publish an empty array to clear prior diagnostics for that document. Include versions whenever the backend supports them: an unversioned publication cannot be checked against the current source version.

Each diagnostic carries a message, severity, optional source/code and optional location. Use the compiler's structured errors and actual source provenance to construct it. Share the projection with execution so equivalent failures have consistent message, category and location. Failures without trustworthy locations remain unlocated rather than being painted across a guessed file. Service-wide failures or unlocated background analysis messages can be reported through `onLog`.

The editor and Problems panel combine matching live/run diagnostics. They do not interpret a language-specific code to reconstruct compiler semantics. Location conversion and any expansion from one compiler error to several owned source sites happen in the adapter.

### Completion

Provide semantic completion through `capabilities.completions`, including the language's actual trigger characters. The `provide` operation receives a document snapshot, position and abort signal and returns a `CompletionBatch`.

Preserve:

- Candidate label, insertion text and semantic kind.
- The exact replacement range, or separate insert/replace ranges, especially for a cursor inside a partial identifier.
- Useful type/signature detail and documentation, rendered from real compiler information.
- Snippet status, additional edits, sorting/filtering hints and commit characters when supported.
- `isIncomplete` according to whether the result needs requerying as typing continues; it is not a generic failure flag.
- Optional resolution and acceptance callbacks only when implemented by the service.

Additional completion edits apply to the queried document. Workspace-wide text edits belong to operations with explicit target URIs. Ensure ranges are compatible, valid and do not encode compiler-private offsets.

Typed member completion belongs in semantic analysis: query the receiver's established type and ordinary visibility/scope rules. The editor must not guess fields from source regexes, reconstructed type strings or a static table of known records. If analysis cannot answer a context, preserve that limitation explicitly rather than supplying invented semantic candidates.

### Inlay hints and semantic highlighting

These are independent optional provider objects. An adapter may implement either, both, or neither. Their display settings are enabled by default and persisted separately; changing a setting never changes what the adapter advertises. Lexical highlighting continues when semantic tokens are unavailable.

`inlayHints.provide(document, range, signal)` supplies hints for the requested visible range. Each hint has a valid UTF-16 position and a nonempty label: either plain text or nonempty label parts. Optional fields include `kind` (`type` or `parameter`), plain-text tooltips, padding, insertion edits, and a cancellable `resolve` callback for additional details. Label parts can carry locations or a `LanguageCommand`. Supply type or parameter information only when the language's existing API establishes it; an empty result or absent capability is preferable to guessed information.

`semanticTokens` owns its `legend` and a `provide(document, signal)` function returning absolute ranges with type and modifier names. Names are extensible strings, independent of Monaco/LSP numeric indexes. Tokens must be nonempty, single-line, within the queried document and non-overlapping; all names must occur in the legend. The editor sorts and encodes tokens at its boundary. A legend is stable for the provider's lifetime, with distinct types and at most 31 distinct modifiers. Themes define colors for standard semantic categories; custom categories may add theme rules at editor installation.

Both providers can expose `onDidChange(listener)`, returning an unsubscribe function. Emit when analysis changes without a document edit. The editor re-queries and cancels requests with their model/service lifetime. Results and lazy resolutions for an older document/workspace revision are discarded; providers should still stop their work when the signal is aborted. No semantic caches or compiler objects are required in the shared editor.

### Code actions and quick fixes

`codeActions.provide(document, range, context, signal)` is optional. Context includes the original diagnostic objects intersecting the requested range, a requested kind filter when present, and an automatic/invoked trigger. Diagnostics preserve numeric or string codes and opaque `data` for the originating provider. They are not reconstructed from rendered editor markers. Advertise supported action `kinds` when known; kinds are extensible, dot-separated identifiers such as `quickfix` and `refactor.extract`.

An action contains a title and may include its kind, related diagnostics, preferred status, a disabled reason, document edits, a command, or a cancellable lazy `resolve` callback. A `LanguageCommand` has a title and an `execute(signal)` callback. Implement the operation at the adapter boundary; the playground does not infer fixes from diagnostic messages. Quick Fix (`Ctrl/Cmd+.`), Refactor, the native lightbulb, and editor context menus use the same provider.

`DocumentEdits` identifies a URI and may constrain the source document version. That version is the neutral `SourceDocument.version`, not Monaco's internal model version. The editor validates the whole transaction before handing it to Monaco: every target must belong to the workspace, source versions must match when supplied, ranges must be valid, and edits must not overlap. Invalid actions are disabled with a reason. Accepted edits use Monaco's current model version and undo mechanism. Lazy actions and command-only actions retain the request revision; when an action combines edits and a command, the command runs after its own edits.

The current contract supports text edits, not file creation/deletion, snippet workspace edits, or edits requiring confirmation annotations. An adapter must reject an unsupported transaction as a whole. Do not apply its supported subset.

### Other operations

| Capability             | Contract detail                                                                                                                         |
| ---------------------- | --------------------------------------------------------------------------------------------------------------------------------------- |
| Hover                  | Text plus an optional source range; preserve the service's actual semantic knowledge                                                    |
| Definitions/references | Locations with workspace-compatible URIs and converted ranges                                                                           |
| Symbols                | Located hierarchical symbols; the outline requests each workspace document                                                              |
| Signature help         | Signatures, parameter labels and active indexes using the neutral nullable conventions                                                  |
| Formatting             | Text edits for the queried document, respecting supplied tab size/indentation options                                                   |
| Rename                 | Groups of text edits with explicit document URIs; the current editor rejects an entire result if it targets files outside the workspace |
| Folding                | Zero-based start/end line indexes; the bridge converts them for Monaco                                                                  |

The current contracts do not expose file-creation edits or binary artifacts. Add a neutral capability deliberately if an integration requires new behavior; do not smuggle it through diagnostic messages, completion text or language-ID conditionals.

## 7. Install lexical editor support

`EditorLanguage.install(monaco, registration)` owns language registration, lexical tokens, language configuration and any syntax snippets. Return an `IDisposable` releasing the registrations owned by that installation. Register against the supplied `registration.id`: it is a private Monaco namespace for this editor instance. The original language metadata is `registration.definition`; service documents retain its original language ID. This separates lexical and semantic registrations when several instances use the same language. Dispose all providers owned by that installation; Monaco itself retains language-ID descriptors because its public API has no unregister operation.

Lexical support is distinct from semantic service support. Highlighting and editing behavior should remain useful when semantic analysis is absent or restarting. Static keywords/snippets may come from the language's syntax definition. Types, bindings, field membership and visibility must come from semantic analysis.

The shared [editor bridge](../src/editor/language-bridge.ts) installs service-backed providers. Do not register a second competing implementation of the same semantic operation inside the lexical installer. Monaco setup and editor model ownership remain in the editor layer; compiler objects remain in the adapter.

## 8. Use LSP when appropriate

A language can implement `LanguageService` directly. An existing LSP server can instead use [LspLanguageService](../src/adapters/lsp/service.ts) with a `MessageConnection` and workspace URI. The adapter handles initialization, capability negotiation, document notifications and neutral result conversion. Supply a browser-compatible worker or an explicitly owned endpoint bridging the actual transport; raw process stdio framing is not a `MessageEndpoint` implementation.

Current LSP support includes diagnostics, logs, hover, definitions, references, document symbols, completion/resolution/accepted commands, signature help, folding, formatting, text rename edits, inlay hints/resolution, semantic tokens, and code actions/resolution. Each feature is exposed only when the server advertises the corresponding capability. The client negotiates UTF-16 and rejects another position encoding. Commands are checked against commands advertised by the server.

For semantic tokens, the adapter requests full results when offered, otherwise a range covering the document. It validates relative integer tuples and decodes them into the neutral representation. Delta requests, overlapping tokens and multiline tokens are not negotiated. Hint/action resolution sends the original server object, preserving opaque data. Versioned `documentChanges` take precedence over legacy `changes`; unsupported file operations, snippet edits and annotated edits disable the entire action. Current diagnostic publication triggers a refresh of supported hint/token providers so asynchronous analysis can catch up with an edit.

Limitations to account for:

- Capabilities are established at initialization; dynamic registration is not implemented.
- Server-initiated inlay-hint/semantic-token refresh requests are not negotiated; the adapter refreshes on document edits and current diagnostic publication.
- Server-initiated workspace edits and file operations are not implemented. Unsupported server-to-client requests receive an explicit method-not-found response.
- Completion item defaults are not negotiated; the server must send complete items.
- Text synchronization supplies full document text, even when the server accepts incremental changes.
- Structured errors and ownership still need to originate in the language implementation; LSP translation cannot recover information that was discarded earlier.

These are transport-adapter limits. They do not require changing the language core to match a protocol representation.

## 9. Keep completion latency at the owning boundary

The workbench debounces background synchronization/outline refresh by 350 ms, but flushes all pending document snapshots immediately before interactive semantic requests. Completion does not wait for that timer. The current Monaco configuration retains its ordinary 10 ms quick-suggestion delay.

Preserve update-before-request ordering without requiring unrelated expensive analysis to finish before every interactive answer. If the backend can safely reuse a semantic snapshot or dependency analysis, own that reuse and invalidation inside the backend. Avoid repeated whole-library compilation per request when the compiler already provides a reusable analysis API.

Measure request scheduling, transport, backend analysis and rendering separately before optimizing. Verify cancellation and source-version correctness under rapid typing and cross-file edits. Do not compensate for slow analysis by inserting language-specific delays, type guessing or stale semantic caches into the shared editor.

## 10. Build and validate an integration

Build the IDE package with `npm run build`. Build and install it in your host application, then build that application's compiler/runtime assets and frontend using its own toolchain. The IDE build has no language compiler dependency. `npm run test:package` verifies the public package from a separate consumer; `npm run test:standalone` verifies a fresh repository containing only this package.

Keep generated modules, maps, dependency directories and bundles ignored. The host application owns ignore rules for its generated language assets. Track authored TypeScript, worker entry points, build configuration and lockfiles.

Run frontend type checking, lint and formatting checks. Reuse the generic behavioral tests as appropriate:

- [Controller tests](../tests/controller.test.ts) cover workspace/runtime/service interaction through a separate test language.
- [RPC tests](../tests/rpc.test.ts) cover correlation, cancellation, failures and disposal.
- [LSP adapter tests](../tests/lsp-adapter.test.ts) cover negotiation and neutral conversion.
- [Persistence tests](../tests/persistence.test.ts) cover complete workspace snapshots and migration.

Add integration tests that use your real compiler/runtime, and browser cases with your language's actual source/examples. Existing bundled-language integration and browser fixtures are not automatically valid programs for a replacement language. Keep test fakes outside production code.

Validate the following observable behavior:

1. Multi-file dependency execution uses the selected entry file, even when another tab is active. Removing/renaming a dependency changes subsequent analysis and execution.
2. Completion, navigation and diagnostics retain correct ranges after Unicode text and cross-file edits; outdated publications/results cannot replace current ones.
3. Stop and time/output limits terminate a deliberately nonterminating run without freezing editing. A service failure/restart does not lose source files.
4. Declared capabilities work, omitted capabilities remain explicit, and empty valid answers are distinguished from service failure.
5. Stdin, stdout/stderr, expected failures and optional artifacts use the same public runtime contract for examples and user programs.
6. Closing tabs retains files and undo history. Folder moves update descendant URIs. Deletes close removed documents; restored files start fresh URI lifetimes.
7. The production static build loads its workers/assets correctly, including from a subdirectory, and generated files remain untracked.

An integration is complete when it honors these contracts with the real language implementation, documents its supported semantic contexts and build requirements, and leaves language-specific semantics behind the adapter boundary.
