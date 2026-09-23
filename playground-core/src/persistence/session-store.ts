import { sessionSchema, maximumInputBytes, type Session } from '../core/session';
import { z } from 'zod';
import { strFromU8, strToU8, zlibSync, unzlibSync } from 'fflate';
import {
  defaultSettings,
  checkpointLimit,
  workbenchSettingsSchema,
  type WorkbenchSettings,
  type Snapshot,
  type SessionRepository,
} from '../workbench/session';
import { Workspace, workspaceLimits } from '../workspace/model';

export const projectFileExtension = '.playground.json';
// JSON may use six bytes per source code unit; paths and metadata also need room.
export const maximumProjectBytes =
  (workspaceLimits.totalBytes + maximumInputBytes + (workspaceLimits.files + workspaceLimits.directories) * 1_024) * 6;
const wireSessionSchema = z
  .object({ ...sessionSchema.shape, workspace: z.unknown() })
  .transform(({ workspace, ...session }) => sessionSchema.parse({ ...session, workspace: Workspace.parse(workspace) }));

// Versions 1 and 2 remain readable at this boundary for existing saves, checkpoints, exports, and share links.
// They are immediately converted to the current model and are never emitted by a writer.
const flatFileSchema = z.object({ id: z.string().min(1), fileName: z.string(), text: z.string() });
const flatSessionSchema = z
  .object({
    languageId: z.string(),
    files: z.array(flatFileSchema),
    activeFileId: z.string().nullable(),
    entryFileId: z.string().nullable(),
    entryPoint: z.string(),
    stdin: z.string(),
  })
  .transform(({ files, ...session }) =>
    sessionSchema.parse({
      ...session,
      workspace: new Workspace({ files: files.map(({ fileName, ...file }) => ({ ...file, path: fileName })) }),
      openFileIds: files.map((file) => file.id),
    }),
  );
const singleSessionSchema = z
  .object({
    languageId: z.string(),
    fileName: z.string(),
    text: z.string(),
    entryPoint: z.string(),
    stdin: z.string(),
  })
  .transform(({ fileName, text, ...session }) => {
    const id = crypto.randomUUID();
    return sessionSchema.parse({
      ...session,
      workspace: new Workspace({ files: [{ id, path: fileName, text }] }),
      openFileIds: [id],
      activeFileId: id,
      entryFileId: id,
    });
  });
const readableExportSchema = z.discriminatedUnion('version', [
  z.object({ format: z.literal('language-playground'), version: z.literal(3), session: wireSessionSchema }),
  z.object({ format: z.literal('language-playground'), version: z.literal(2), session: flatSessionSchema }),
  z.object({ format: z.literal('language-playground'), version: z.literal(1), session: singleSessionSchema }),
]);
const readableSnapshotSchema = z.object({
  id: z.string(),
  createdAt: z.number(),
  session: z.union([wireSessionSchema, flatSessionSchema, singleSessionSchema]),
});

function serializedSession(session: Session) {
  const valid = sessionSchema.parse(session);
  return { ...valid, workspace: valid.workspace.snapshot };
}

/** Owns serialization versions and browser storage; workspace invariants belong to Workspace. */
export class SessionStore implements SessionRepository {
  constructor(
    private readonly storage: Pick<Storage, 'getItem' | 'setItem'>,
    private readonly languageId: string,
  ) {}
  private key(category: string): string {
    return `playground.${encodeURIComponent(this.languageId)}.${category}.v1`;
  }
  load(): Session | undefined {
    const raw = this.storage.getItem(this.key('session'));
    return raw === null ? undefined : importSession(raw);
  }
  save(session: Session): void {
    this.storage.setItem(this.key('session'), exportSession(session));
  }
  settings(fallback: WorkbenchSettings = defaultSettings): WorkbenchSettings {
    const raw = this.storage.getItem('playground.settings.v1');
    return raw === null ? fallback : workbenchSettingsSchema.parse(JSON.parse(raw));
  }
  saveSettings(settings: WorkbenchSettings): void {
    this.storage.setItem('playground.settings.v1', JSON.stringify(workbenchSettingsSchema.parse(settings)));
  }
  history(): Snapshot[] {
    return z
      .array(readableSnapshotSchema)
      .max(checkpointLimit)
      .parse(JSON.parse(this.storage.getItem(this.key('history')) ?? '[]'));
  }
  saveHistory(history: readonly Snapshot[]): void {
    if (history.length > checkpointLimit) throw new RangeError('Too many saved checkpoints.');
    this.storage.setItem(
      this.key('history'),
      JSON.stringify(history.map((snapshot) => ({ ...snapshot, session: serializedSession(snapshot.session) }))),
    );
  }
}
export function exportSession(session: Session): string {
  const text = JSON.stringify(
    { format: 'language-playground', version: 3, session: serializedSession(session) },
    null,
    2,
  );
  if (strToU8(text).length > maximumProjectBytes) throw new Error('The project exceeds the export size limit.');
  return text;
}
export function importSession(text: string): Session {
  if (strToU8(text).length > maximumProjectBytes) throw new Error('The project file is too large.');
  return readableExportSchema.parse(JSON.parse(text)).session;
}
export function shareFragment(session: Session): string {
  const bytes = zlibSync(strToU8(exportSession(session)), { level: 9 });
  const payload = btoa(Array.from(bytes, (byte) => String.fromCharCode(byte)).join(''))
    .replaceAll('+', '-')
    .replaceAll('/', '_')
    .replace(/=+$/, '');
  if (payload.length > 48_000) throw new Error('This source is too large for a share link. Export a project instead.');
  return `#code=${payload}`;
}
export function sessionFromFragment(fragment: string): Session | undefined {
  if (!fragment.startsWith('#code=')) return undefined;
  const payload = fragment.slice(6);
  if (payload.length > 48_000) throw new Error('This share link exceeds the size limit.');
  const bytes = Uint8Array.from(atob(payload.replaceAll('-', '+').replaceAll('_', '/')), (char) => char.charCodeAt(0));
  const output = new Uint8Array(maximumProjectBytes + 1);
  const decoded = unzlibSync(bytes, { out: output });
  if (decoded.length >= output.length) throw new Error('The shared source is too large.');
  return importSession(strFromU8(decoded));
}
