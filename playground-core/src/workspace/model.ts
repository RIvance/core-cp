import { z } from 'zod';
import { parentPath, parseWorkspacePath, withinPath, type WorkspacePath } from './path';

/** Workspace resource limits, applied equally to edits, examples, persistence, and archive imports. */
export const workspaceLimits: Readonly<{ files: number; directories: number; fileBytes: number; totalBytes: number }> =
  Object.freeze({ files: 50, directories: 200, fileBytes: 512_000, totalBytes: 2_000_000 });

/** An editable UTF-8 text file. Identity survives moves; path determines its URI and directory membership. */
export interface WorkspaceFile {
  /** Nonempty opaque identity, unique within this workspace and independent of path spelling. */
  readonly id: string;
  /** Canonical workspace-relative path; separators represent virtual directories. */
  readonly path: WorkspacePath;
  /** Exact editable text. Size limits use its UTF-8 byte length; archives additionally require lossless UTF-8 encoding. */
  readonly text: string;
}

/** Flat serialized workspace contents. Directories include empty folders and all parents of files and folders. */
export interface WorkspaceSnapshot {
  readonly files: readonly WorkspaceFile[];
  readonly directories: readonly WorkspacePath[];
}

/** Input to a workspace constructor. Omitted directories are inferred from file paths. */
export interface WorkspaceInput {
  readonly files: readonly { readonly id: string; readonly path: string; readonly text: string }[];
  readonly directories?: readonly string[];
}

/** An immediate child returned by readDirectory. A directory is a path, not a second copy of its descendants. */
export type WorkspaceEntry =
  | { readonly kind: 'file'; readonly file: WorkspaceFile; readonly path: WorkspacePath }
  | { readonly kind: 'directory'; readonly path: WorkspacePath };

/** The invariant that rejected a workspace operation. */
export type WorkspaceErrorCode =
  'invalid-data' | 'duplicate-id' | 'path-conflict' | 'missing-entry' | 'invalid-move' | 'limit';

/** Structured workspace failure. Operations throw before changing the original immutable workspace. */
export class WorkspaceError extends Error {
  /** path identifies the affected entry when available; cause retains input-validation errors. */
  constructor(
    readonly code: WorkspaceErrorCode,
    message: string,
    readonly path?: string,
    options?: ErrorOptions,
  ) {
    super(message, options);
    this.name = 'WorkspaceError';
  }
}

const inputSchema = z.strictObject({
  files: z.array(z.strictObject({ id: z.string().min(1), path: z.string(), text: z.string() })),
  directories: z.array(z.string()).default([]),
});
const comparePaths = (a: string, b: string) => (a < b ? -1 : a > b ? 1 : 0);

/**
 * Immutable virtual workspace with flat storage and hierarchical paths. Construction validates all entries,
 * infers parents, and freezes owned copies. No DOM, storage, compiler, archive, or process dependencies.
 * Files and folders cannot occupy the same path; file IDs and paths are unique. Mutations return a new workspace
 * and validate the complete result atomically. Removing a child leaves its parent folder in place.
 */
export class Workspace {
  private readonly contents: WorkspaceSnapshot;

  /** Construct a validated workspace. Throws WorkspaceError or WorkspacePathError without retaining mutable inputs. */
  constructor(input: WorkspaceInput) {
    const parsed = inputSchema.safeParse(input);
    if (!parsed.success)
      throw new WorkspaceError('invalid-data', 'Invalid workspace contents.', undefined, { cause: parsed.error });
    const files = parsed.data.files.map((file) => Object.freeze({ ...file, path: parseWorkspacePath(file.path) }));
    const directories = new Set(parsed.data.directories.map(parseWorkspacePath));
    if (directories.size !== parsed.data.directories.length)
      throw new WorkspaceError('path-conflict', 'Folder paths must be unique.');
    const ids = new Set<string>();
    const paths = new Set<WorkspacePath>();
    let total = 0;
    for (const file of files) {
      if (ids.has(file.id)) throw new WorkspaceError('duplicate-id', 'File identities must be unique.', file.path);
      if (paths.has(file.path))
        throw new WorkspaceError('path-conflict', `The path “${file.path}” is used more than once.`, file.path);
      ids.add(file.id);
      paths.add(file.path);
      const size = new TextEncoder().encode(file.text).byteLength;
      if (size > workspaceLimits.fileBytes)
        throw new WorkspaceError(
          'limit',
          `The file “${file.path}” exceeds ${workspaceLimits.fileBytes} UTF-8 bytes.`,
          file.path,
        );
      total += size;
    }
    for (const path of [...directories, ...paths]) {
      for (let parent = parentPath(path); parent; parent = parentPath(parent)) directories.add(parent);
    }
    for (const path of directories) {
      if (paths.has(path))
        throw new WorkspaceError('path-conflict', `“${path}” cannot be both a file and a folder.`, path);
    }
    if (
      files.length > workspaceLimits.files ||
      directories.size > workspaceLimits.directories ||
      total > workspaceLimits.totalBytes
    )
      throw new WorkspaceError('limit', 'The workspace exceeds its file, folder, or source size limit.');
    this.contents = Object.freeze({
      files: Object.freeze(files),
      directories: Object.freeze([...directories].sort(comparePaths)),
    });
  }

  /** Validate decoded external data through the same constructor used by editing operations. */
  static parse(value: unknown): Workspace {
    const parsed = inputSchema.safeParse(value);
    if (!parsed.success)
      throw new WorkspaceError('invalid-data', 'Invalid workspace contents.', undefined, { cause: parsed.error });
    return new Workspace(parsed.data);
  }

  /** Owned, immutable flat representation suitable for serialization. */
  get snapshot(): WorkspaceSnapshot {
    return this.contents;
  }
  /** Files in insertion order; renaming never changes their IDs or order. */
  get files(): readonly WorkspaceFile[] {
    return this.contents.files;
  }
  /** Canonical folder paths in code-unit order, including inferred parents. */
  get directories(): readonly WorkspacePath[] {
    return this.contents.directories;
  }

  /** Find a file by stable identity; undefined means it has been removed or never belonged to this workspace. */
  file(id: string): WorkspaceFile | undefined {
    return this.files.find((file) => file.id === id);
  }

  /** Read one validated path. Returns undefined for a missing entry. The implicit root is not an entry. */
  entry(path: string): WorkspaceEntry | undefined {
    const canonical = parseWorkspacePath(path);
    const file = this.files.find((file) => file.path === canonical);
    return file
      ? { kind: 'file', file, path: canonical }
      : this.directories.includes(canonical)
        ? { kind: 'directory', path: canonical }
        : undefined;
  }

  /** Read direct children, folders first and then files, each in code-unit path order. '' reads the root. */
  readDirectory(path: string = ''): readonly WorkspaceEntry[] {
    const parent = path === '' ? '' : parseWorkspacePath(path);
    if (parent && this.entry(parent)?.kind !== 'directory')
      throw new WorkspaceError('missing-entry', `Folder “${path}” does not exist.`, path);
    return [
      ...this.directories
        .filter((path) => parentPath(path) === parent)
        .map((path): WorkspaceEntry => ({ kind: 'directory', path })),
      ...this.files
        .filter((file) => parentPath(file.path) === parent)
        .map((file): WorkspaceEntry => ({ kind: 'file', file, path: file.path }))
        .sort((a, b) => comparePaths(a.path, b.path)),
    ];
  }

  /** Add a file and infer any missing parent directories. Existing IDs and paths are rejected. */
  addFile(file: WorkspaceInput['files'][number]): Workspace {
    return new Workspace({ files: [...this.files, file], directories: this.directories });
  }

  /** Create a folder, including missing parents. An existing file or folder at this path is an error. */
  addDirectory(path: string): Workspace {
    if (this.entry(path)) throw new WorkspaceError('path-conflict', `“${path}” already exists.`, path);
    return new Workspace({ files: this.files, directories: [...this.directories, path] });
  }

  /**
   * Merge imported text files and folders. A file at an existing file path replaces its text and keeps the old ID.
   * New files keep their supplied IDs. Folder/file collisions and duplicate identities reject the complete merge.
   * Existing file order is preserved and new files append in incoming order.
   */
  merge(incoming: Workspace): Workspace {
    const replacements = new Map(incoming.files.map((file) => [file.path, file]));
    const existing = new Set(this.files.map((file) => file.path));
    return new Workspace({
      files: [
        ...this.files.map((file) =>
          replacements.has(file.path) ? { ...file, text: replacements.get(file.path)!.text } : file,
        ),
        ...incoming.files.filter((file) => !existing.has(file.path)),
      ],
      directories: [...new Set([...this.directories, ...incoming.directories])],
    });
  }

  /** Replace one file's text while preserving identity and path. Missing files are errors. */
  edit(id: string, text: string): Workspace {
    if (!this.file(id)) throw new WorkspaceError('missing-entry', 'The file no longer belongs to this workspace.');
    return new Workspace({
      files: this.files.map((file) => (file.id === id ? { ...file, text } : file)),
      directories: this.directories,
    });
  }

  /**
   * Rename or move a file or entire folder subtree. Destination parents are created as needed; existing destinations
   * and moves inside the source subtree are rejected. File IDs and text remain unchanged. The old parent remains.
   */
  move(source: string, destination: string): Workspace {
    const from = parseWorkspacePath(source);
    const to = parseWorkspacePath(destination);
    const entry = this.entry(from);
    if (!entry) throw new WorkspaceError('missing-entry', `“${source}” no longer exists.`, source);
    if (from === to) return this;
    if (this.entry(to)) throw new WorkspaceError('path-conflict', `“${destination}” already exists.`, destination);
    if (entry.kind === 'directory' && withinPath(to, from))
      throw new WorkspaceError('invalid-move', 'A folder cannot be moved inside itself.', destination);
    const moved = (path: WorkspacePath) => (withinPath(path, from) ? `${to}${path.slice(from.length)}` : path);
    return new Workspace({
      files: this.files.map((file) => ({ ...file, path: moved(file.path) })),
      directories: this.directories.map(moved),
    });
  }

  /** Remove a file or a folder and all descendants. Missing entries are errors; the original remains available for undo/history. */
  remove(path: string): Workspace {
    const canonical = parseWorkspacePath(path);
    if (!this.entry(canonical)) throw new WorkspaceError('missing-entry', `“${path}” no longer exists.`, path);
    return new Workspace({
      files: this.files.filter((file) => !withinPath(file.path, canonical)),
      directories: this.directories.filter((path) => !withinPath(path, canonical)),
    });
  }
}
