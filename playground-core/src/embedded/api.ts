import type * as monaco from 'monaco-editor/editor/editor.api';
import type { Diagnostic, LanguagePlugin, Location } from '../core/contracts';
import type { PlaygroundState } from '../core/controller';
import type { PlaygroundSettings } from '../core/settings';
import type { EditorLanguage } from '../editor/monaco';
import type { PlaygroundTheme } from '../appearance/themes';
import type { ResultPanelId } from '../components/ResultPanel';

/** A fixed tab supplied by the host. File names follow Workspace's relative path rules. */
export interface EmbeddedFile {
  /** Unique relative path, such as main.txt or lib/definitions.txt. */
  readonly path: string;
  /** Initial source text. Defaults to an empty string. */
  readonly value?: string;
  /** Prevent user edits and language-service edits. Defaults to false. Host setValue/setValues can still write. */
  readonly readOnly?: boolean;
}

/** Current contents and access policy of a fixed tab. */
export interface EmbeddedFileSnapshot {
  /** Stable workspace-relative path. */
  readonly path: string;
  /** Absolute instance-scoped document URI used by the language service and runtime. */
  readonly uri: string;
  /** Current source text. */
  readonly value: string;
  /** Whether user edits and language-service edits are prohibited. */
  readonly readOnly: boolean;
}

/** Options that can be changed without replacing the editor, models, services, or undo history. */
export interface EmbeddedPlaygroundUpdate extends Partial<PlaygroundSettings> {
  /** Header text. Defaults to the language's display name. Empty text hides the title. */
  readonly title?: string;
  /** Region accessible name. Defaults to "<language name> playground"; must be nonempty. */
  readonly ariaLabel?: string;
  /** Show path breadcrumbs. Defaults to false. */
  readonly breadcrumbs?: boolean;
  /** Visible result tabs, in the standard panel order. Defaults to output and problems. Must be nonempty and unique. */
  readonly panels?: readonly ResultPanelId[];
  /** Selected result tab. Defaults to output, or the first configured tab. Must be present in panels. */
  readonly panel?: ResultPanelId;
  /** Initial/current entry file path. Defaults to the first file. Switching the active tab does not change it. */
  readonly entryFile?: string;
  /** Runtime entry expression/name. Defaults to plugin.definition.defaultEntryPoint. */
  readonly entryPoint?: string;
  /** Standard input. Defaults to empty; maximumInputBytes limits its UTF-8 length. */
  readonly stdin?: string;
  /**
   * Built-in theme ID or custom palette. Omit to inherit the page's current theme.
   * This changes ALL playground instances in the window, matching Monaco's theme scope.
   * Use the themes entry point's setTheme for a page-level control. No preference is stored by the embed.
   */
  readonly theme?: string | PlaygroundTheme;
}

/** Creation requires only a language plugin; all presentation and execution settings have defaults. */
export interface EmbeddedPlaygroundOptions extends EmbeddedPlaygroundUpdate {
  /** Each instance owns a fresh language service; each run receives a fresh runtime from these factories. */
  readonly plugin: LanguagePlugin;
  /** Lexical syntax installer. Omit for plain text; language-service capabilities still work. */
  readonly editor?: EditorLanguage;
  /** Single-file initial text. Uses plugin.definition.defaultFilePath. Defaults to empty; cannot accompany files. */
  readonly value?: string;
  /** Fixed files in tab order. Omit for one blank file. An explicitly empty array is invalid. */
  readonly files?: readonly EmbeddedFile[];
  /** Initially selected tab path. Defaults to the first file. */
  readonly activeFile?: string;
  /** Optional error observer, installed before rendering or service startup. Errors also appear in the instance UI. */
  readonly onError?: (error: unknown) => void;
}

/** One atomic content change notification. Host setValues emits once for the whole update. */
export interface EmbeddedContentChange {
  /** Files whose text changed, in tab order. Tab selection, diagnostics, and settings do not emit this event. */
  readonly changes: readonly {
    readonly path: string;
    readonly previousValue: string;
    readonly value: string;
  }[];
}

/**
 * An independently owned embedded editor. No browser storage, URL state, import/export, file management, or
 * workbench command palette is created. Methods throw after disposal, except dispose and isDisposed.
 * Invalid file paths or settings reject the complete operation before changing the instance.
 */
export interface EmbeddedPlaygroundHandle {
  /** Resolves after Monaco and all models are installed. Rejects on rendering failure or disposal before readiness. */
  readonly ready: Promise<void>;
  /** Instance-scoped absolute root used by its language service and source documents. */
  readonly workspaceUri: string;
  /** Current immutable core state: session, settings, documents, service capabilities, diagnostics, logs, and execution. */
  getSnapshot(): PlaygroundState;
  /** Fixed tabs and their current contents/access policy, in initial order. */
  getFiles(): readonly EmbeddedFileSnapshot[];
  /** Source text for path, or the active tab when omitted. Unknown paths throw RangeError. */
  getValue(path?: string): string;
  /** Replace source as a host operation, including a read-only file. Existing models and undo history are retained. */
  setValue(value: string, path?: string): void;
  /** Atomically replace text in existing files, including protected files. Unknown/duplicate paths reject every change. */
  setValues(values: readonly { readonly path: string; readonly value: string }[]): void;
  /** Selected tab path. */
  getActiveFile(): string;
  /** Select an existing tab without changing the execution entry or adding/removing tabs. */
  setActiveFile(path: string): void;
  /** Change one existing file's user edit policy. Defaults to the active tab. The host can always replace its text. */
  setReadOnly(readOnly: boolean, path?: string): void;
  /** Apply any subset of settings atomically. Omitted properties keep their current values. */
  updateOptions(options: EmbeddedPlaygroundUpdate): void;
  /** Merged current options, including defaults and the actual page theme; excludes factories and initial content. */
  getOptions(): Readonly<Required<EmbeddedPlaygroundUpdate>>;
  /**
   * Run the complete workspace using the selected execution entry and input; supersedes a previous run.
   * Opens Output when that panel is configured. Resolves when this run finishes or is cancelled.
   * Runtime failures are represented by execution.result; factory failures become error diagnostics.
   */
  run(): Promise<void>;
  /** Abort the active run. Does nothing when no run is active. */
  stop(): void;
  /** Clear a completed/cancelled result; does nothing while running. */
  clearOutput(): void;
  /** Clear language-service messages without restarting the service. */
  clearLogs(): void;
  /** Current service and non-stale execution diagnostics, with duplicates removed. */
  getDiagnostics(): readonly Diagnostic[];
  /** Recreate the owned language service. Failure is recorded in state; execution remains available. */
  restartLanguageService(): Promise<void>;
  /** Focus the source editor; requests before ready are applied once it loads. */
  focus(): void;
  /** Recompute editor dimensions. Automatic layout is also enabled by default. */
  layout(): void;
  /** Select and reveal a workspace location. Waits for ready; an outside-workspace URI rejects. */
  reveal(location: Location): Promise<void>;
  /** Invoke a Monaco action by its public identifier. Waits for ready; unavailable actions reject. */
  action(identifier: string): Promise<void>;
  /**
   * Native Monaco editor, or null while loading or after a rendering failure. Use it for selections, view state, events, and additional editor options.
   * The playground owns models, language IDs, read-only policy, and disposal: do not replace/dispose them through this
   * reference. Use setValue/setValues/updateOptions/setReadOnly for managed state and themes.setTheme for colors.
   */
  getEditor(): monaco.editor.IStandaloneCodeEditor | null;
  /** Observe subsequent text changes from either user edits or host updates. Dispose the returned subscription to detach. */
  onDidChangeContent(listener: (event: EmbeddedContentChange) => void): monaco.IDisposable;
  /** Observe every subsequent core state transition, including diagnostics, service status, output, and cancellation. */
  onDidChangeState(listener: (state: PlaygroundState) => void): monaco.IDisposable;
  /** Observe editor/provider/rendering errors. Runtime errors are reported through execution state instead. */
  onDidError(listener: (error: unknown) => void): monaco.IDisposable;
  /** Observe disposal once. Subscriptions are cleared after notification. */
  onDidDispose(listener: () => void): monaco.IDisposable;
  /** Whether dispose has been called. */
  isDisposed(): boolean;
  /** Stop work, remove the DOM, and release models, providers, events and service. Idempotent; attempts every cleanup. */
  dispose(): void;
}
