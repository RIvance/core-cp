import type { LanguagePlugin } from '../core/contracts';
import { PlaygroundController, errorMessage, type PlaygroundState } from '../core/controller';
import { readSession, type Session } from '../core/session';
import {
  defaultSettings,
  workspaceUri,
  appendCheckpoint,
  workbenchSettingsSchema,
  type WorkbenchSettings,
  type Snapshot,
  type SessionRepository,
} from './session';
import { Workspace } from '../workspace/model';
import { ThemeCatalog } from '../appearance/catalog';
import { setTheme } from '../appearance/page-theme';

export interface WorkbenchState extends Omit<PlaygroundState, 'settings'> {
  readonly settings: WorkbenchSettings;
  readonly persistence: 'saved' | 'pending' | 'failed';
  readonly persistenceError?: string;
  readonly history: readonly Snapshot[];
}

/** Owns application persistence, checkpoints, and file management around the shared playground controller. */
export class WorkbenchController {
  readonly core: PlaygroundController;
  private state: WorkbenchState;
  private readonly listeners = new Set<() => void>();
  private readonly unsubscribe: () => void;
  private persistenceTimer?: ReturnType<typeof setTimeout>;
  private disposed = false;

  constructor(
    readonly plugin: LanguagePlugin,
    private readonly repository: SessionRepository,
    session: Session,
    settings: WorkbenchSettings = defaultSettings,
    history: readonly Snapshot[] = [],
    readonly themes: ThemeCatalog = new ThemeCatalog(),
  ) {
    const validated = workbenchSettingsSchema.parse(settings);
    themes.get(validated.theme);
    this.core = new PlaygroundController({ plugin, session, settings: validated, workspaceUri });
    this.state = { ...this.core.getSnapshot(), settings: validated, history, persistence: 'pending' };
    this.unsubscribe = this.core.subscribe(() => {
      const next = this.core.getSnapshot();
      const changed = next.session !== this.state.session || next.settings !== this.settings;
      this.settings = next.settings;
      this.update({
        ...next,
        settings: { ...next.settings, theme: this.state.settings.theme },
        ...(changed ? { persistence: 'pending' as const } : {}),
      });
      if (changed) {
        clearTimeout(this.persistenceTimer);
        this.persistenceTimer = setTimeout(() => this.save(), 500);
      }
    });
    this.settings = this.core.getSnapshot().settings;
  }
  private settings: PlaygroundState['settings'];
  getSnapshot = (): WorkbenchState => this.state;
  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  };
  private update(change: Partial<WorkbenchState>): void {
    if (this.disposed) return;
    this.state = { ...this.state, ...change };
    for (const listener of this.listeners) listener();
  }
  connect(): Promise<void> {
    return this.core.connect();
  }
  selectFile(fileId: string): void {
    this.core.selectFile(fileId);
  }
  updateSession(session: Session): void {
    this.core.updateSession(session);
  }
  addFile(path: string, text = ''): string {
    const session = this.state.session;
    const id = crypto.randomUUID();
    this.updateSession({
      ...session,
      workspace: session.workspace.addFile({ id, path, text }),
      openFileIds: [...session.openFileIds, id],
      activeFileId: id,
      entryFileId: session.entryFileId ?? id,
    });
    return id;
  }
  addDirectory(path: string): void {
    const session = this.state.session;
    this.updateSession({ ...session, workspace: session.workspace.addDirectory(path) });
  }
  movePath(source: string, destination: string): void {
    const session = this.state.session;
    const workspace = session.workspace.move(source, destination);
    if (workspace === session.workspace) return;
    this.checkpoint();
    this.updateSession({ ...session, workspace });
  }
  closeFile(fileId: string): void {
    const session = this.state.session;
    const index = session.openFileIds.indexOf(fileId);
    if (index < 0) return;
    const openFileIds = session.openFileIds.filter((id) => id !== fileId);
    this.updateSession({
      ...session,
      openFileIds,
      activeFileId:
        session.activeFileId === fileId
          ? (openFileIds[Math.min(index, openFileIds.length - 1)] ?? null)
          : session.activeFileId,
    });
  }
  removePath(path: string): void {
    const session = this.state.session;
    const workspace = session.workspace.remove(path);
    const openFileIds = session.openFileIds.filter((id) => workspace.file(id));
    this.checkpoint();
    this.updateSession({
      ...session,
      workspace,
      openFileIds,
      activeFileId:
        session.activeFileId && workspace.file(session.activeFileId) ? session.activeFileId : (openFileIds[0] ?? null),
      entryFileId:
        session.entryFileId && workspace.file(session.entryFileId)
          ? session.entryFileId
          : (workspace.files[0]?.id ?? null),
    });
  }
  replaceSession(input: Session): void {
    const session = readSession(input);
    if (session.languageId !== this.plugin.definition.id) throw new Error('This project uses a different language.');
    // Reuse identities for existing paths so replacing source remains undoable in each editor model.
    // Reserve them first: a restored checkpoint may assign the same identity to a file at a different path.
    const existing = new Map(this.state.session.workspace.files.map((file) => [file.path, file.id]));
    const reserved = new Set(
      session.workspace.files.flatMap((file) => {
        const id = existing.get(file.path);
        return id ? [id] : [];
      }),
    );
    const identities = new Map<string, string>();
    const workspace = new Workspace({
      files: session.workspace.files.map((file) => {
        const retained = existing.get(file.path);
        let id = retained ?? file.id;
        if (!retained) {
          while (reserved.has(id)) id = crypto.randomUUID();
          reserved.add(id);
        }
        identities.set(file.id, id);
        return { ...file, id };
      }),
      directories: session.workspace.directories,
    });
    const replacement: Session = {
      ...session,
      workspace,
      openFileIds: session.openFileIds.map((id) => identities.get(id) ?? id),
      activeFileId: session.activeFileId ? (identities.get(session.activeFileId) ?? null) : null,
      entryFileId: session.entryFileId ? (identities.get(session.entryFileId) ?? null) : null,
    };
    this.checkpoint();
    this.stop('Workspace replaced');
    this.updateSession(replacement);
    this.core.clearOutput();
    this.core.clearLogs();
  }
  save(): void {
    clearTimeout(this.persistenceTimer);
    // Closing an unchanged tab must not overwrite work saved by another tab.
    if (this.state.persistence === 'saved') return;
    try {
      this.repository.save(this.state.session);
      this.repository.saveSettings(this.state.settings);
      this.repository.saveHistory(this.state.history);
      this.update({ persistence: 'saved', persistenceError: undefined });
    } catch (error) {
      this.update({ persistence: 'failed', persistenceError: errorMessage(error) });
    }
  }
  checkpoint(): void {
    this.update({ history: appendCheckpoint(this.state.history, this.state.session), persistence: 'pending' });
    this.save();
  }
  configure(change: Partial<WorkbenchSettings>): void {
    const settings = workbenchSettingsSchema.parse({ ...this.state.settings, ...change });
    const theme = this.themes.get(settings.theme);
    this.core.configure(settings);
    this.update({ settings, persistence: 'pending' });
    if (change.theme !== undefined) setTheme(theme);
    this.save();
  }
  run(): Promise<void> {
    this.save();
    return this.core.run();
  }
  stop(reason?: string): void {
    this.core.stop(reason);
  }
  dispose(): void {
    if (this.disposed) return;
    this.save();
    this.disposed = true;
    clearTimeout(this.persistenceTimer);
    this.unsubscribe();
    this.listeners.clear();
    this.core.dispose();
  }
}
