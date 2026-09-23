import type {
  Diagnostic,
  DocumentSymbol,
  ExecutionResult,
  LanguagePlugin,
  LanguageService,
  OutputChunk,
  ServiceLog,
  SourceDocument,
} from './contracts';
import { documentFor, readSession, type Session } from './session';
import { defaultPlaygroundSettings, playgroundSettingsSchema, type PlaygroundSettings } from './settings';

/** Lifecycle of the owned language service. Unavailable means the plugin provides no factory. */
export type ServiceState =
  | { readonly kind: 'starting' }
  | { readonly kind: 'ready'; readonly service: LanguageService }
  | { readonly kind: 'unavailable' }
  | { readonly kind: 'failed'; readonly message: string };
/**
 * Execution lifecycle. revision identifies the source/entry/input snapshot, not the selected tab.
 * startedAt is performance.now() time; duration is elapsed milliseconds. Output preserves channel order.
 * Compare revision with PlaygroundState.revision to detect a result retained from earlier source/input.
 */
export type ExecutionState =
  | { readonly kind: 'idle' }
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
/** A versioned document and the latest accepted analysis for it. */
export interface WorkspaceDocument {
  /** Identity retained across path moves; distinct from the display path. */
  readonly fileId: string;
  /** Snapshot supplied to language services and execution. */
  readonly source: SourceDocument;
  /** Most recent service diagnostics accepted for this source version. */
  readonly diagnostics: readonly Diagnostic[];
  /** Most recent symbols accepted for the workspace generation. */
  readonly symbols: readonly DocumentSymbol[];
  /** User/service edit policy. Host session updates may replace protected source. */
  readonly readOnly: boolean;
}
/** Read-only snapshot. Never mutate its nested arrays or service data; subscribe for subsequent snapshots. */
export interface PlaygroundState {
  /** Files, tab selection, execution entry, and input. */
  readonly session: Session;
  /** Validated editor/execution settings, excluding themes. */
  readonly settings: PlaygroundSettings;
  /** Monotonic execution-input revision, starting at 1. Identifies stale results. */
  readonly revision: number;
  /** Monotonic source/access-policy generation, starting at 1. Invalidates pending editor requests. */
  readonly workspaceVersion: number;
  /** Every source file, including those whose tabs are closed. */
  readonly documents: readonly WorkspaceDocument[];
  /** Current lifecycle state and capabilities, when ready. */
  readonly service: ServiceState;
  /** Latest 100 messages in arrival order. */
  readonly logs: readonly ServiceLog[];
  /** Current or last execution, independent of service availability. */
  readonly execution: ExecutionState;
}

/** Inputs for a document and execution controller. No browser storage or UI is created by this class. */
export interface PlaygroundControllerOptions {
  /** Language factories. Each controller owns its service and each run owns its runtime. */
  readonly plugin: LanguagePlugin;
  /** Validated files, open tabs, entry selection, and input. The controller copies the session metadata. */
  readonly session: Session;
  /** Absolute URI of this instance's workspace. Concurrent instances must use distinct roots. */
  readonly workspaceUri: string;
  /** Initial editor and execution settings; omitted fields use defaultPlaygroundSettings. */
  readonly settings?: Partial<PlaygroundSettings>;
  /** IDs protected from user edits, including language-service edits. Host session updates remain authoritative. */
  readonly readOnlyFileIds?: readonly string[];
}

/** A user edit targeting a protected document. The path is separate from its display message. */
export class ReadOnlyDocumentError extends Error {
  constructor(readonly path: string) {
    super(`“${path}” is read-only.`);
    this.name = 'ReadOnlyDocumentError';
  }
}

/**
 * Owns document revisions, service synchronization, analysis, and execution. Shared by every presentation.
 * It has no persistence, history, import/export, command-palette, or DOM dependencies.
 * Subscribe to immutable snapshots; dispose the controller when its owning view is removed.
 */
export class PlaygroundController {
  /** Factories and language metadata supplied at construction. */
  readonly plugin: LanguagePlugin;
  /** Absolute, query-free root URI used to identify source documents. */
  readonly workspaceUri: string;
  private state: PlaygroundState;
  private readonly readOnlyFileIds: Set<string>;
  private readonly listeners = new Set<() => void>();
  private serviceSubscriptions: (() => void)[] = [];
  private service?: LanguageService;
  private analysis?: AbortController;
  private execution?: AbortController;
  private synchronizationTimer?: ReturnType<typeof setTimeout>;
  private autoRunTimer?: ReturnType<typeof setTimeout>;
  private disposed = false;

  /** Validate all input before starting work. Call connect separately to start language analysis. */
  constructor(options: PlaygroundControllerOptions) {
    const session = readSession(options.session);
    if (session.languageId !== options.plugin.definition.id)
      throw new Error('The session language does not match its plugin.');
    const root = new URL(options.workspaceUri);
    if (root.search || root.hash) throw new TypeError('A workspace root URI cannot contain a query or fragment.');
    this.workspaceUri = root.href.replace(/\/$/, '');
    this.plugin = options.plugin;
    this.readOnlyFileIds = new Set(options.readOnlyFileIds);
    for (const id of this.readOnlyFileIds) {
      if (!session.workspace.file(id)) throw new RangeError(`Unknown read-only file ID: ${id}`);
    }
    this.state = {
      session,
      settings: Object.freeze(playgroundSettingsSchema.parse({ ...defaultPlaygroundSettings, ...options.settings })),
      revision: 1,
      workspaceVersion: 1,
      documents: session.workspace.files.map((file) => ({
        fileId: file.id,
        source: documentFor(this.workspaceUri, session.languageId, file, 1),
        readOnly: this.isReadOnly(file.id),
        diagnostics: [],
        symbols: [],
      })),
      service: { kind: 'starting' },
      logs: [],
      execution: { kind: 'idle' },
    };
  }

  /** Current snapshot. Its contents must be treated as immutable; updates produce a new snapshot. */
  readonly getSnapshot = (): PlaygroundState => this.state;
  /** Observe subsequent state changes. The returned function removes only this subscription. */
  readonly subscribe = (listener: () => void): (() => void) => {
    this.assertActive();
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  };
  private assertActive(): void {
    if (this.disposed) throw new Error('The playground controller has been disposed.');
  }
  private update(change: Partial<PlaygroundState>): void {
    if (this.disposed) return;
    this.state = { ...this.state, ...change };
    for (const listener of this.listeners) listener();
  }
  private log(log: ServiceLog): void {
    this.update({ logs: [...this.state.logs, log].slice(-100) });
  }
  /** Whether editor-originated changes to this identity are prohibited. Host updates can replace its contents. */
  isReadOnly(fileId: string): boolean {
    return this.readOnlyFileIds.has(fileId);
  }
  /** Set the edit policy for an existing document. This does not alter its source or execution revision. */
  setReadOnly(fileId: string, readOnly: boolean): void {
    this.assertActive();
    if (!this.state.session.workspace.file(fileId)) throw new RangeError(`Unknown file ID: ${fileId}`);
    if (typeof readOnly !== 'boolean') throw new TypeError('readOnly must be a boolean.');
    if (readOnly === this.isReadOnly(fileId)) return;
    this.analysis?.abort();
    if (readOnly) this.readOnlyFileIds.add(fileId);
    else this.readOnlyFileIds.delete(fileId);
    this.update({
      workspaceVersion: this.state.workspaceVersion + 1,
      documents: this.state.documents.map((document) =>
        document.fileId === fileId ? { ...document, readOnly } : document,
      ),
    });
    this.synchronize();
  }
  /** Start or recreate the owned language service. Unsupported services become unavailable; failures are recorded in state. */
  async connect(): Promise<void> {
    this.assertActive();
    this.analysis?.abort();
    let service: LanguageService | undefined;
    try {
      this.disconnect();
      service = this.plugin.createLanguageService?.({ workspaceUri: this.workspaceUri });
    } catch (error) {
      this.service = undefined;
      this.update({ service: { kind: 'failed', message: errorMessage(error) } });
      return;
    }
    this.service = service;
    if (!service) {
      this.update({ service: { kind: 'unavailable' } });
      return;
    }
    try {
      this.update({ service: { kind: 'starting' } });
      this.serviceSubscriptions.push(
        service.onDiagnostics((update) => {
          if (this.service !== service) return;
          this.update({
            documents: this.state.documents.map((document) =>
              document.source.uri === update.uri &&
              (update.version === undefined || update.version === document.source.version)
                ? { ...document, diagnostics: update.diagnostics }
                : document,
            ),
          });
        }),
      );
      this.serviceSubscriptions.push(
        service.onLog((log) => {
          if (this.service === service) this.log(log);
        }),
      );
      this.serviceSubscriptions.push(
        service.onFailure((error) => {
          if (this.service === service) {
            this.analysis?.abort();
            this.update({ service: { kind: 'failed', message: error.message } });
          }
        }),
      );
      if (this.disposed || this.service !== service) return;
      await service.start();
      if (this.disposed || this.service !== service) return;
      this.update({ service: { kind: 'ready', service } });
      this.synchronize();
    } catch (error) {
      if (this.service !== service || this.disposed) return;
      let failure = error;
      try {
        this.disconnect();
      } catch (cleanupError) {
        failure = new AggregateError([error, cleanupError], 'Language service startup and cleanup failed.', {
          cause: cleanupError,
        });
      }
      this.update({ service: { kind: 'failed', message: errorMessage(failure) } });
    }
  }

  private disconnect(): void {
    const service = this.service;
    this.service = undefined;
    const releases = this.serviceSubscriptions.splice(0);
    if (service) releases.push(() => service.dispose());
    const errors: unknown[] = [];
    for (const release of releases) {
      try {
        release();
      } catch (error) {
        errors.push(error);
      }
    }
    if (errors.length) throw new AggregateError(errors, 'Could not release the language service.');
  }
  /** Resolve the current versioned source for a workspace file identity. Throws for an unknown file. */
  document(fileId: string): SourceDocument {
    this.assertActive();
    const document = this.state.documents.find((document) => document.fileId === fileId);
    if (!document) throw new Error('The source file no longer belongs to this workspace.');
    return document.source;
  }
  /** Select a file, adding its tab if needed. Its identity must already belong to the workspace. */
  selectFile(fileId: string): void {
    this.assertActive();
    const session = this.state.session;
    this.updateSession({
      ...session,
      activeFileId: fileId,
      openFileIds: session.openFileIds.includes(fileId) ? session.openFileIds : [...session.openFileIds, fileId],
    });
  }
  /** Apply user-originated text. Protected files throw ReadOnlyDocumentError; invalid workspace contents are rejected. */
  edit(fileId: string, text: string): void {
    this.assertActive();
    const { session } = this.state;
    const file = session.workspace.file(fileId);
    if (!file) throw new Error('The source file no longer belongs to this workspace.');
    if (file.text === text) return;
    if (this.isReadOnly(fileId)) throw new ReadOnlyDocumentError(file.path);
    this.updateSession({ ...session, workspace: session.workspace.edit(fileId, text) });
  }
  /**
   * Replace validated session metadata/source as a host operation, preserving per-file source versions when unchanged.
   * Closed tabs retain their documents; removed/moved documents are closed in the service. Protected text can be replaced
   * here. Source changes invalidate analysis, increment workspaceVersion, and schedule background synchronization at 350 ms.
   * Input/entry/source changes increment revision; selecting a tab does not. Optional settings are validated and published
   * in the same snapshot. Invalid input changes nothing.
   */
  updateSession(input: Session, change?: Partial<PlaygroundSettings>): void {
    this.assertActive();
    const session: Session = readSession(input);
    const settings =
      change === undefined
        ? this.state.settings
        : Object.freeze(playgroundSettingsSchema.parse({ ...this.state.settings, ...change }));
    if (session.languageId !== this.plugin.definition.id) throw new Error('This project uses a different language.');
    const previous = this.state.session;
    const previousFiles = new Map(previous.workspace.files.map((file) => [file.id, file]));
    const filesChanged =
      session.workspace.files.length !== previous.workspace.files.length ||
      session.workspace.files.some((file) => {
        const old = previousFiles.get(file.id);
        return !old || old.text !== file.text || old.path !== file.path;
      });
    const executionChanged =
      filesChanged ||
      session.entryFileId !== previous.entryFileId ||
      session.entryPoint !== previous.entryPoint ||
      session.stdin !== previous.stdin;
    const workspaceVersion = this.state.workspaceVersion + (filesChanged ? 1 : 0);
    const previousDocuments = new Map(this.state.documents.map((document) => [document.fileId, document]));
    const documents = session.workspace.files.map((file) => {
      const old = previousFiles.get(file.id);
      const document = previousDocuments.get(file.id);
      const changed = !old || old.text !== file.text || old.path !== file.path;
      const source =
        changed || !document
          ? documentFor(this.workspaceUri, session.languageId, file, workspaceVersion)
          : document.source;
      return {
        fileId: file.id,
        readOnly: this.isReadOnly(file.id),
        source,
        diagnostics: filesChanged ? [] : (document?.diagnostics ?? []),
        symbols: filesChanged ? [] : (document?.symbols ?? []),
      };
    });
    if (this.state.service.kind === 'ready') {
      const uris = new Set(documents.map((document) => document.source.uri));
      for (const document of this.state.documents) {
        if (!uris.has(document.source.uri)) this.service?.close(document.source.uri);
      }
    }
    if (filesChanged) {
      this.analysis?.abort();
      clearTimeout(this.synchronizationTimer);
    }
    if (executionChanged || !settings.autoRun) clearTimeout(this.autoRunTimer);
    this.update({
      session,
      settings,
      documents,
      workspaceVersion,
      revision: this.state.revision + (executionChanged ? 1 : 0),
    });
    if (filesChanged) this.synchronizationTimer = setTimeout(() => this.synchronize(), 350);
    if (executionChanged && this.state.settings.autoRun && session.entryFileId)
      this.autoRunTimer = setTimeout(() => {
        void this.run();
      }, 1000);
  }
  /** Validate and apply editor/execution settings atomically. Themes are owned by the presentation layer. */
  configure(change: Partial<PlaygroundSettings>): void {
    this.updateSession(this.state.session, change);
  }
  /** Flush all edited documents before any interactive language-service request. */
  synchronizeDocuments(): void {
    this.assertActive();
    if (this.state.service.kind !== 'ready') return;
    for (const document of this.state.documents) this.state.service.service.synchronize(document.source);
  }
  private synchronize(): void {
    if (this.state.service.kind !== 'ready') return;
    const service = this.state.service.service;
    this.analysis?.abort();
    const cancellation = new AbortController();
    this.analysis = cancellation;
    const version = this.state.workspaceVersion;
    try {
      this.synchronizeDocuments();
      for (const document of this.state.documents) {
        void service.capabilities
          .symbols?.(document.source, cancellation.signal)
          .then((symbols) => {
            if (!cancellation.signal.aborted && this.state.workspaceVersion === version)
              this.update({
                documents: this.state.documents.map((current) =>
                  current.fileId === document.fileId ? { ...current, symbols } : current,
                ),
              });
          })
          .catch((error) => {
            if (!cancellation.signal.aborted) this.log({ level: 'error', message: errorMessage(error) });
          });
      }
    } catch (error) {
      this.update({ service: { kind: 'failed', message: errorMessage(error) } });
    }
  }

  /**
   * Execute the current workspace, superseding any earlier run. Captures source/input and revision at invocation.
   * Runtime/factory failures become result diagnostics. Enforces timeout and a 256,000 UTF-16-unit streamed output limit.
   * Results emitted after cancellation are ignored. The promise settles on completion or cancellation.
   */
  async run(): Promise<void> {
    this.assertActive();
    clearTimeout(this.autoRunTimer);
    this.stop('Superseded by a new run');
    const entryFileId = this.state.session.entryFileId;
    if (!entryFileId) throw new Error('Add a file and select an entry file before running.');
    const cancellation = new AbortController();
    this.execution = cancellation;
    const snapshot = this.state;
    const revision = snapshot.revision;
    const entryDocumentUri = this.document(entryFileId).uri;
    const documents = this.state.documents.map((document) => document.source);
    const startedAt = performance.now();
    let output: OutputChunk[] = [];
    let outputLength = 0;
    this.update({ execution: { kind: 'running', revision, startedAt, output } });
    if (this.execution !== cancellation || cancellation.signal.aborted) return;
    const timeout = setTimeout(() => {
      if (this.execution === cancellation) this.stop('Execution time limit reached');
    }, snapshot.settings.timeout * 1000);
    const emit = (chunk: OutputChunk) => {
      if (this.execution !== cancellation || cancellation.signal.aborted) return;
      outputLength += chunk.text.length;
      if (outputLength > 256_000) {
        this.stop('Output limit reached');
        return;
      }
      const last = output.at(-1);
      output =
        last?.channel === chunk.channel
          ? [...output.slice(0, -1), { channel: chunk.channel, text: last.text + chunk.text }]
          : [...output, chunk];
      this.update({ execution: { kind: 'running', revision, startedAt, output } });
    };
    const aborted = () => rejectCancellation(new DOMException('Execution cancelled.', 'AbortError'));
    let rejectCancellation!: (reason: unknown) => void;
    const cancelled = new Promise<never>((_resolve, reject) => {
      rejectCancellation = reject;
    });
    cancellation.signal.addEventListener('abort', aborted, { once: true });
    try {
      const result = await Promise.race([
        cancelled,
        this.plugin.createRuntime().execute(
          {
            documents,
            entryDocumentUri,
            entryPoint: snapshot.session.entryPoint,
            stdin: snapshot.session.stdin,
          },
          emit,
          cancellation.signal,
        ),
      ]);
      if (this.execution !== cancellation || cancellation.signal.aborted) return;
      this.update({
        execution: { kind: 'finished', revision, duration: performance.now() - startedAt, output, result },
      });
    } catch (error) {
      if (this.execution !== cancellation || cancellation.signal.aborted) return;
      this.update({
        execution: {
          kind: 'finished',
          revision,
          duration: performance.now() - startedAt,
          output,
          result: {
            status: 'error',
            diagnostics: [{ severity: 'error', message: errorMessage(error) }],
            artifacts: [],
          },
        },
      });
    } finally {
      cancellation.signal.removeEventListener('abort', aborted);
      clearTimeout(timeout);
      if (this.execution === cancellation) this.execution = undefined;
    }
  }
  /** Cancel a running invocation immediately, retain its output, and record a human-readable reason. Idle calls do nothing. */
  stop(reason: string = 'Stopped by you'): void {
    this.assertActive();
    this.execution?.abort();
    this.execution = undefined;
    const execution = this.state.execution;
    if (execution.kind === 'running')
      this.update({
        execution: {
          kind: 'stopped',
          revision: execution.revision,
          output: execution.output,
          duration: performance.now() - execution.startedAt,
          reason,
        },
      });
  }
  /** Remove a completed or stopped result. A running execution is unaffected. */
  clearOutput(): void {
    this.assertActive();
    if (this.state.execution.kind !== 'running') this.update({ execution: { kind: 'idle' } });
  }
  /** Clear the bounded language-service log without restarting analysis. */
  clearLogs(): void {
    this.assertActive();
    this.update({ logs: [] });
  }
  /** Stop work and release the language service, listeners, and timers. Repeated disposal has no effect. */
  dispose(): void {
    if (this.disposed) return;
    const errors: unknown[] = [];
    try {
      this.stop('Workspace closed');
    } catch (error) {
      errors.push(error);
    }
    this.disposed = true;
    clearTimeout(this.synchronizationTimer);
    clearTimeout(this.autoRunTimer);
    this.analysis?.abort();
    this.listeners.clear();
    try {
      this.disconnect();
    } catch (error) {
      errors.push(error);
    }
    if (errors.length) throw new AggregateError(errors, 'Could not release all controller resources.');
  }
}

/** @internal Render an unknown failure at an application or UI boundary without discarding structured errors internally. */
export function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
