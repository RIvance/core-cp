import type { z } from 'zod';
import type { Example, LanguageDefinition } from '../core/contracts';
import { sessionSchema, type Session } from '../core/session';
import { defaultPlaygroundSettings, playgroundSettingsSchema } from '../core/settings';
import { Workspace } from '../workspace/model';
import { builtinThemes, themeIdSchema } from '../appearance/catalog';

/** The full application persists a page theme alongside shared editor/execution settings. */
export const workbenchSettingsSchema = playgroundSettingsSchema.extend({ theme: themeIdSchema });
export type WorkbenchSettings = Readonly<z.infer<typeof workbenchSettingsSchema>>;
export const defaultSettings: WorkbenchSettings = { ...defaultPlaygroundSettings, theme: builtinThemes[0].id };

export interface Snapshot {
  readonly id: string;
  readonly createdAt: number;
  readonly session: Session;
}
export const workspaceUri = 'file:///workspace';
/** Choose an initial entry for imported source; users can change it in workspace settings or the explorer. */
export function sessionFromWorkspace(definition: LanguageDefinition, workspace: Workspace): Session {
  const entry =
    workspace.files.find((file) => file.path === definition.defaultFilePath) ??
    workspace.files.find((file) => file.path.endsWith(definition.extension)) ??
    workspace.files[0];
  return sessionSchema.parse({
    languageId: definition.id,
    workspace,
    openFileIds: entry ? [entry.id] : [],
    activeFileId: entry?.id ?? null,
    entryFileId: entry?.id ?? null,
    entryPoint: definition.defaultEntryPoint,
    stdin: '',
  });
}
export function sessionFromExample(definition: LanguageDefinition, example?: Example): Session {
  const workspace = new Workspace({
    files: (example?.files ?? [{ path: definition.defaultFilePath, text: '' }]).map((file) => ({
      ...file,
      id: crypto.randomUUID(),
    })),
    directories: example?.directories,
  });
  const session = sessionFromWorkspace(definition, workspace);
  if (!example) return session;
  const entry = workspace.files.find((file) => file.path === example.entryPath);
  if (!entry) throw new Error('The example entry file is missing from its workspace.');
  return sessionSchema.parse({
    ...session,
    openFileIds: [entry.id],
    activeFileId: entry.id,
    entryFileId: entry.id,
    entryPoint: example.entryPoint ?? definition.defaultEntryPoint,
    stdin: example.stdin ?? '',
  });
}

/** Persistence needed by the workbench; browser storage is one implementation. */
export interface SessionRepository {
  save(session: Session): void;
  saveSettings(settings: WorkbenchSettings): void;
  saveHistory(history: readonly Snapshot[]): void;
}
export const checkpointLimit = 20;

/** Immutable workspace snapshots can be shared with history; later operations construct new workspaces. */
export function appendCheckpoint(history: readonly Snapshot[], session: Session): Snapshot[] {
  const snapshot = {
    id: crypto.randomUUID(),
    createdAt: Date.now(),
    session: { ...session, openFileIds: [...session.openFileIds] },
  };
  return [snapshot, ...history].slice(0, checkpointLimit);
}
