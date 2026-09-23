import { z } from 'zod';
import type * as monaco from 'monaco-editor/editor/editor.api';
import { PlaygroundController, type PlaygroundState } from '../core/controller';
import { currentDiagnostics } from '../core/diagnostics';
import { readSession } from '../core/session';
import { playgroundSettingsSchema } from '../core/settings';
import type { Location } from '../core/contracts';
import { Workspace, type WorkspaceFile } from '../workspace/model';
import { getTheme, setTheme, readPageTheme } from '../appearance/page-theme';
import type { EditorHandle } from '../editor/workspace';
import type {
  EmbeddedContentChange,
  EmbeddedFileSnapshot,
  EmbeddedPlaygroundHandle,
  EmbeddedPlaygroundOptions,
  EmbeddedPlaygroundUpdate,
} from './api';

function sourceFile(workspace: Workspace, path: string): WorkspaceFile {
  const entry = workspace.entry(path);
  if (entry?.kind !== 'file') throw new RangeError(`Unknown workspace file: ${path}`);
  return entry.file;
}

const viewSchema = z
  .object({
    title: z.string(),
    ariaLabel: z.string().trim().min(1),
    breadcrumbs: z.boolean(),
    panels: z
      .array(z.enum(['output', 'problems', 'input', 'inspector', 'logs']))
      .min(1)
      .refine((panels) => new Set(panels).size === panels.length, 'Result panels must be distinct.'),
    panel: z.enum(['output', 'problems', 'input', 'inspector', 'logs']),
  })
  .refine((view) => view.panels.includes(view.panel), 'The selected result panel must be visible.');
export interface EmbeddedViewState {
  readonly options: Readonly<z.infer<typeof viewSchema>>;
  readonly error?: unknown;
}

/** Fixed-file policy and host operations around the shared controller. The mounted view owns its own DOM cleanup. */
export class EmbeddedController implements EmbeddedPlaygroundHandle {
  readonly core: PlaygroundController;
  readonly ready: Promise<void>;
  private resolveReady!: () => void;
  private rejectReady!: (error: unknown) => void;
  private editor?: EditorHandle;
  private focusRequested = false;
  private disposed = false;
  private view: EmbeddedViewState;
  private readonly viewListeners = new Set<() => void>();
  private readonly errors = new Set<(error: unknown) => void>();
  private readonly disposal = new Set<() => void>();

  constructor(options: EmbeddedPlaygroundOptions) {
    if (options.files !== undefined && options.value !== undefined)
      throw new TypeError('Use either value for one file or files for multiple files.');
    for (const file of options.files ?? []) {
      if (file.readOnly !== undefined && typeof file.readOnly !== 'boolean')
        throw new TypeError('readOnly must be a boolean.');
    }
    const files = options.files ?? [{ path: options.plugin.definition.defaultFilePath, value: options.value }];
    if (!files.length) throw new RangeError('An embedded playground requires at least one file.');
    const workspace = new Workspace({
      files: files.map((file) => ({
        id: crypto.randomUUID(),
        path: file.path,
        text: file.value ?? '',
      })),
    });
    const select = (path: string | undefined) =>
      (path === undefined ? workspace.files[0]! : sourceFile(workspace, path)).id;
    const panels = options.panels ?? ['output', 'problems'];
    this.view = {
      options: viewSchema.parse({
        title: options.title ?? options.plugin.definition.name,
        ariaLabel: options.ariaLabel ?? `${options.plugin.definition.name} playground`,
        breadcrumbs: options.breadcrumbs ?? false,
        panels,
        panel: options.panel ?? panels[0],
      }),
    };
    const theme = options.theme === undefined ? undefined : readPageTheme(options.theme);
    this.core = new PlaygroundController({
      plugin: options.plugin,
      workspaceUri: `file:///playground/${crypto.randomUUID()}`,
      session: readSession({
        languageId: options.plugin.definition.id,
        workspace,
        openFileIds: workspace.files.map((file) => file.id),
        activeFileId: select(options.activeFile),
        entryFileId: select(options.entryFile),
        entryPoint: options.entryPoint ?? options.plugin.definition.defaultEntryPoint,
        stdin: options.stdin ?? '',
      }),
      settings: options,
      readOnlyFileIds: files.flatMap((file, index) => (file.readOnly ? [workspace.files[index]!.id] : [])),
    });
    this.ready = new Promise<void>((resolve, reject) => {
      this.resolveReady = resolve;
      this.rejectReady = reject;
    });
    // A caller may never need editor readiness. Still expose rejection to callers that await it.
    void this.ready.catch(() => {});
    if (options.onError) this.errors.add(options.onError);
    if (theme) setTheme(theme);
  }
  get workspaceUri(): string {
    return this.core.workspaceUri;
  }
  private assertActive(): void {
    if (this.disposed) throw new Error('The embedded playground has been disposed.');
  }
  private file(path?: string): WorkspaceFile {
    this.assertActive();
    const session = this.core.getSnapshot().session;
    return path === undefined ? session.workspace.file(session.activeFileId!)! : sourceFile(session.workspace, path);
  }

  private emit<T>(listeners: ReadonlySet<(value: T) => void>, value: T): void {
    for (const listener of [...listeners]) {
      try {
        listener(value);
      } catch (error) {
        globalThis.reportError(error);
      }
    }
  }
  private listen<T>(listeners: Set<T>, listener: T): monaco.IDisposable {
    this.assertActive();
    listeners.add(listener);
    return {
      dispose: () => {
        listeners.delete(listener);
      },
    };
  }
  getView = (): EmbeddedViewState => this.view;
  subscribeView = (listener: () => void): (() => void) =>
    (() => {
      const subscription = this.listen(this.viewListeners, listener);
      return () => subscription.dispose();
    })();
  private updateView(view: EmbeddedViewState): void {
    this.view = view;
    this.emit(this.viewListeners, undefined);
  }
  attachEditor(editor: EditorHandle): void {
    if (this.disposed) return;
    this.editor = editor;
    editor.getEditor()?.onDidDispose(() => {
      if (this.editor === editor) this.editor = undefined;
    });
    editor.getEditor()?.updateOptions({ contextmenu: false });
    if (this.focusRequested) editor.focus();
    this.resolveReady();
  }
  report = (error: unknown): void => {
    if (this.disposed) return;
    this.updateView({ ...this.view, error });
    this.emit(this.errors, error);
  };
  renderingFailed = (error: unknown): void => {
    this.rejectReady(error);
    this.report(error);
  };
  dismissError(): void {
    this.updateView({ ...this.view, error: undefined });
  }
  getSnapshot(): PlaygroundState {
    this.assertActive();
    return this.core.getSnapshot();
  }
  getFiles(): readonly EmbeddedFileSnapshot[] {
    return this.getSnapshot().documents.map((document) => ({
      path: this.core.getSnapshot().session.workspace.file(document.fileId)!.path,
      uri: document.source.uri,
      value: document.source.text,
      readOnly: document.readOnly,
    }));
  }
  getValue(path?: string): string {
    return this.file(path).text;
  }
  setValue(value: string, path?: string): void {
    this.setValues([{ path: this.file(path).path, value }]);
  }
  setValues(values: readonly { readonly path: string; readonly value: string }[]): void {
    this.assertActive();
    const changes = new Map<string, string>();
    for (const change of values) {
      const file = this.file(change.path);
      if (changes.has(file.id)) throw new RangeError(`Duplicate file update: ${file.path}`);
      changes.set(file.id, change.value);
    }
    const session = this.core.getSnapshot().session;
    const workspace = new Workspace({
      files: session.workspace.files.map((file) => ({ ...file, text: changes.get(file.id) ?? file.text })),
      directories: session.workspace.directories,
    });
    this.core.updateSession({ ...session, workspace });
  }
  getActiveFile(): string {
    return this.file().path;
  }
  setActiveFile(path: string): void {
    this.core.selectFile(this.file(path).id);
  }
  setReadOnly(readOnly: boolean, path?: string): void {
    this.core.setReadOnly(this.file(path).id, readOnly);
  }
  updateOptions(options: EmbeddedPlaygroundUpdate): void {
    this.assertActive();
    const settings = playgroundSettingsSchema.parse({ ...this.core.getSnapshot().settings, ...options });
    const previous = this.view.options;
    const panels = options.panels ?? previous.panels;
    const panel = options.panel ?? (panels.includes(previous.panel) ? previous.panel : panels[0]);
    const view = viewSchema.parse({ ...previous, ...options, panels, panel });
    const session = readSession({
      ...this.core.getSnapshot().session,
      entryFileId:
        options.entryFile === undefined ? this.core.getSnapshot().session.entryFileId : this.file(options.entryFile).id,
      entryPoint: options.entryPoint ?? this.core.getSnapshot().session.entryPoint,
      stdin: options.stdin ?? this.core.getSnapshot().session.stdin,
    });
    const theme = options.theme === undefined ? undefined : readPageTheme(options.theme);
    // Validate every boundary before publishing any mutation, including a page-wide theme change.
    this.view = { ...this.view, options: view };
    this.core.updateSession(session, settings);
    this.emit(this.viewListeners, undefined);
    if (theme) setTheme(theme);
  }
  getOptions(): Readonly<Required<EmbeddedPlaygroundUpdate>> {
    const state = this.getSnapshot();
    return {
      ...state.settings,
      ...this.view.options,
      entryFile: state.session.workspace.file(state.session.entryFileId!)!.path,
      entryPoint: state.session.entryPoint,
      stdin: state.session.stdin,
      theme: getTheme(),
    };
  }
  run(): Promise<void> {
    this.assertActive();
    if (this.view.options.panels.includes('output'))
      this.updateView({ ...this.view, options: { ...this.view.options, panel: 'output' } });
    return this.core.run();
  }
  stop(): void {
    this.assertActive();
    this.core.stop();
  }
  clearOutput(): void {
    this.assertActive();
    this.core.clearOutput();
  }
  clearLogs(): void {
    this.assertActive();
    this.core.clearLogs();
  }
  getDiagnostics() {
    return currentDiagnostics(this.getSnapshot());
  }
  restartLanguageService(): Promise<void> {
    this.assertActive();
    return this.core.connect();
  }
  focus(): void {
    this.assertActive();
    this.focusRequested = true;
    this.editor?.focus();
  }
  layout(): void {
    this.assertActive();
    this.editor?.getEditor()?.layout();
  }
  async reveal(location: Location): Promise<void> {
    this.assertActive();
    await this.ready;
    this.assertActive();
    if (!this.editor) throw new Error('The source editor is unavailable.');
    this.editor.reveal(location);
  }
  async action(identifier: string): Promise<void> {
    this.assertActive();
    await this.ready;
    this.assertActive();
    if (!this.editor) throw new Error('The source editor is unavailable.');
    await this.editor.action(identifier);
  }
  getEditor(): monaco.editor.IStandaloneCodeEditor | null {
    this.assertActive();
    return this.editor?.getEditor() ?? null;
  }
  onDidChangeContent(listener: (event: EmbeddedContentChange) => void): monaco.IDisposable {
    this.assertActive();
    let previous = this.core.getSnapshot().session.workspace;
    return this.onDidChangeState((state) => {
      const current = state.session.workspace;
      if (current === previous) return;
      const changes = current.files.flatMap((file) => {
        const previousValue = previous.file(file.id)!.text;
        return file.text === previousValue ? [] : [{ path: file.path, previousValue, value: file.text }];
      });
      previous = current;
      if (changes.length) listener({ changes });
    });
  }
  onDidChangeState(listener: (state: PlaygroundState) => void): monaco.IDisposable {
    this.assertActive();
    return {
      dispose: this.core.subscribe(() => {
        if (this.disposed) return;
        try {
          listener(this.core.getSnapshot());
        } catch (error) {
          globalThis.reportError(error);
        }
      }),
    };
  }
  onDidError(listener: (error: unknown) => void): monaco.IDisposable {
    return this.listen(this.errors, listener);
  }
  onDidDispose(listener: () => void): monaco.IDisposable {
    return this.listen(this.disposal, listener);
  }
  isDisposed(): boolean {
    return this.disposed;
  }
  dispose(): void {
    if (this.disposed) return;
    this.disposed = true;
    this.rejectReady(new DOMException('The embedded playground was disposed before its editor loaded.', 'AbortError'));
    const errors: unknown[] = [];
    for (const release of [() => this.core.dispose(), ...this.disposal]) {
      try {
        release();
      } catch (error) {
        errors.push(error);
      }
    }
    this.disposal.clear();
    this.errors.clear();
    this.viewListeners.clear();
    this.editor = undefined;
    if (errors.length) throw new AggregateError(errors, 'Could not release all embedded playground resources.');
  }
}
