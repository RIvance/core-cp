import { z } from 'zod';
import type { SourceDocument } from './contracts';
import { Workspace, type WorkspaceFile } from '../workspace/model';
import { workspaceDocumentUri } from '../workspace/path';

/** Maximum standard-input payload, measured as UTF-8 bytes. */
export const maximumInputBytes: number = 64_000;
/** @internal Boundary validation shared with persisted session decoding. */
export const sessionSchema = z
  .object({
    languageId: z.string().min(1).max(100),
    workspace: z.instanceof(Workspace),
    openFileIds: z.array(z.string()),
    activeFileId: z.string().nullable(),
    entryFileId: z.string().nullable(),
    entryPoint: z.string().max(200),
    stdin: z
      .string()
      .max(maximumInputBytes)
      .refine(
        (value) => new TextEncoder().encode(value).length <= maximumInputBytes,
        'Standard input exceeds its UTF-8 size limit.',
      ),
  })
  .superRefine((session, context) => {
    const ids = new Set(session.workspace.files.map((file) => file.id));
    const opened = new Set(session.openFileIds);
    if (opened.size !== session.openFileIds.length || session.openFileIds.some((id) => !ids.has(id)))
      context.addIssue({
        code: 'custom',
        path: ['openFileIds'],
        message: 'Open tabs must identify distinct workspace files.',
      });
    if (
      opened.size === 0
        ? session.activeFileId !== null
        : session.activeFileId === null || !opened.has(session.activeFileId)
    )
      context.addIssue({
        code: 'custom',
        path: ['activeFileId'],
        message: 'The active file must belong to the open tabs.',
      });
    if (ids.size === 0 ? session.entryFileId !== null : session.entryFileId === null || !ids.has(session.entryFileId))
      context.addIssue({
        code: 'custom',
        path: ['entryFileId'],
        message: 'The entry file must belong to the workspace.',
      });
  });
/** One editor/execution session, independent of persistence and presentation controls. */
export interface Session {
  /** Language identifier shared by every source document. */
  readonly languageId: string;
  /** Immutable source files and folders. */
  readonly workspace: Workspace;
  /** Visible tabs in display order, each identifying a distinct workspace file. */
  readonly openFileIds: readonly string[];
  /** Selected visible tab; null exactly when no tabs are open. */
  readonly activeFileId: string | null;
  /** Execution entry, even when its tab is closed; null exactly when there are no files. */
  readonly entryFileId: string | null;
  /** Language-owned entry expression or name. */
  readonly entryPoint: string;
  /** Standard input supplied to each execution, limited to maximumInputBytes in UTF-8. */
  readonly stdin: string;
}

/** Validate and copy session metadata. Workspace contents are already immutable. */
export function readSession(input: Session): Session {
  const valid = sessionSchema.parse(input);
  return Object.freeze({ ...valid, openFileIds: Object.freeze(valid.openFileIds) });
}

/** @internal Project a validated workspace file into a versioned language-service document. */
export function documentFor(
  workspaceUri: string,
  languageId: string,
  file: WorkspaceFile,
  version: number,
): SourceDocument {
  return { uri: workspaceDocumentUri(workspaceUri, file.path), languageId, text: file.text, version };
}
/** @internal Resolve the current tab, if any. */
export function activeFile(session: Session): WorkspaceFile | undefined {
  return session.activeFileId ? session.workspace.file(session.activeFileId) : undefined;
}
