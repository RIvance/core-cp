import { BlobReader, BlobWriter, Uint8ArrayReader, ZipReader, ZipWriter } from '@zip.js/zip.js';
import { createTarDecoder, packTar } from 'modern-tar';
import { decodeWorkspaceText, encodeWorkspaceText, WorkspaceTextError } from './text';
import { Workspace, workspaceLimits, type WorkspaceInput } from './model';
import { parseWorkspacePath, WorkspacePathError, type WorkspacePath } from './path';

/** Maximum compressed/archive input size. Expanded text is additionally bounded by workspaceLimits. */
export const maximumArchiveBytes: number = 16_000_000;

/** Archive failures are separate from invalid workspace paths and workspace invariant failures. */
export type WorkspaceArchiveErrorCode =
  'invalid-archive' | 'unsupported-entry' | 'invalid-text' | 'limit' | 'duplicate-entry';

/** A failed archive read or write. No partial workspace is returned. */
export class WorkspaceArchiveError extends Error {
  /** entry identifies the rejected archive member when known; cause preserves codec/decoder errors. */
  constructor(
    readonly code: WorkspaceArchiveErrorCode,
    message: string,
    readonly entry?: string,
    options?: ErrorOptions,
  ) {
    super(message, options);
    this.name = 'WorkspaceArchiveError';
  }
}

/** A supported archive format, including its file-picker metadata and complete import/export behavior. */
export interface WorkspaceArchiveFormat {
  /** Stable format identifier. */
  readonly id: string;
  /** Display label used in export commands. */
  readonly label: string;
  /** Recognized file suffixes, including dots. The first is used for exports. */
  readonly extensions: readonly string[];
  /** Download content type. */
  readonly mediaType: string;
  /**
   * Decode all files and empty directories into a new workspace with fresh file IDs. Source is strict UTF-8;
   * BOMs and line endings are preserved. Leading ./ on archive member names is accepted. Absolute paths,
   * traversal, duplicate entries, collisions, links, devices, encrypted files, and NUL-containing binary files
   * reject the entire import. No common top-level folder is stripped. Abort rejects with the signal's reason.
   */
  read(archive: Blob, signal?: AbortSignal): Promise<Workspace>;
  /**
   * Encode the workspace's files and directories with relative paths and UTF-8 contents. This is a source archive:
   * tabs, entry selection, input, and settings are omitted. Permissions/timestamps are deterministic defaults;
   * original host filesystem metadata is not retained. Abort rejects without yielding a partial archive.
   */
  write(workspace: Workspace, signal?: AbortSignal): Promise<Blob>;
}

class ArchiveContents {
  private readonly names = new Set<WorkspacePath>();
  private readonly files: WorkspaceInput['files'][number][] = [];
  private readonly directories: WorkspacePath[] = [];
  private bytes = 0;
  private entries = 0;

  entry(name: string, directory: boolean, size: number): WorkspacePath | undefined {
    if (++this.entries > workspaceLimits.files + workspaceLimits.directories + 1)
      throw new WorkspaceArchiveError('limit', 'The archive contains too many entries.');
    if (name.startsWith('/')) throw new WorkspacePathError(name);
    const relativeName = name.replace(/^(\.\/)+/, '');
    const relative = directory && relativeName.endsWith('/') ? relativeName.slice(0, -1) : relativeName;
    if (directory && (name === '.' || /^(\.\/)+$/.test(name))) {
      if (size !== 0)
        throw new WorkspaceArchiveError('invalid-archive', 'The archive root directory must be empty.', name);
      return undefined;
    }
    const path = parseWorkspacePath(relative);
    if (this.names.has(path))
      throw new WorkspaceArchiveError('duplicate-entry', `The archive contains “${path}” more than once.`, path);
    this.names.add(path);
    if (
      !Number.isSafeInteger(size) ||
      size < 0 ||
      size > (directory ? 0 : workspaceLimits.fileBytes) ||
      this.bytes + size > workspaceLimits.totalBytes
    )
      throw new WorkspaceArchiveError('limit', `The archive entry “${path}” exceeds the workspace size limit.`, path);
    if (directory) this.directories.push(path);
    if (
      this.directories.length > workspaceLimits.directories ||
      (!directory && this.files.length >= workspaceLimits.files)
    )
      throw new WorkspaceArchiveError('limit', 'The archive exceeds the workspace entry limit.', path);
    return path;
  }

  sink(path: WorkspacePath, expected: number): WritableStream<Uint8Array> {
    const chunks: Uint8Array[] = [];
    let length = 0;
    return new WritableStream({
      write: (chunk) => {
        length += chunk.byteLength;
        this.bytes += chunk.byteLength;
        if (length > expected || length > workspaceLimits.fileBytes || this.bytes > workspaceLimits.totalBytes)
          throw new WorkspaceArchiveError('limit', `The expanded file “${path}” exceeds its size limit.`, path);
        chunks.push(chunk.slice());
      },
      close: () => {
        if (length !== expected)
          throw new WorkspaceArchiveError('invalid-archive', `The file “${path}” is truncated.`, path);
        const bytes = new Uint8Array(length);
        let offset = 0;
        for (const chunk of chunks) {
          bytes.set(chunk, offset);
          offset += chunk.length;
        }
        this.files.push({ id: crypto.randomUUID(), path, text: decodeWorkspaceText(bytes, path) });
      },
    });
  }

  workspace(): Workspace {
    return new Workspace({ files: this.files, directories: this.directories });
  }
}

async function readZip(archive: Blob, contents: ArchiveContents, signal?: AbortSignal): Promise<void> {
  const reader = new ZipReader(new BlobReader(archive), {
    useWebWorkers: false,
    checkSignature: true,
    strictness: 'strict',
    // The shared workspace path policy accepts ./ prefixes and validates both archive formats uniformly.
    filenameValidation: 'balanced',
  });
  try {
    for await (const entry of reader.getEntriesGenerator()) {
      signal?.throwIfAborted();
      const type = (entry.unixMode ?? 0) & 0o170000;
      if (entry.encrypted || (type !== 0 && type !== 0o100000 && type !== 0o040000))
        throw new WorkspaceArchiveError(
          'unsupported-entry',
          `“${entry.filename}” is encrypted or is not a regular file or folder.`,
          entry.filename,
        );
      const path = contents.entry(entry.filename, entry.directory, entry.uncompressedSize);
      if (path && !entry.directory) await entry.getData(contents.sink(path, entry.uncompressedSize), { signal });
    }
  } finally {
    await reader.close();
  }
}

async function readTar(archive: Blob, contents: ArchiveContents, signal?: AbortSignal): Promise<void> {
  const reader = archive
    .stream()
    .pipeThrough(createTarDecoder({ strict: true }), { signal })
    .getReader();
  try {
    while (true) {
      signal?.throwIfAborted();
      const next = await reader.read();
      if (next.done) break;
      const { header, body } = next.value;
      try {
        if (header.type !== 'file' && header.type !== 'directory')
          throw new WorkspaceArchiveError(
            'unsupported-entry',
            `“${header.name}” is not a regular file or folder.`,
            header.name,
          );
        const path = contents.entry(header.name, header.type === 'directory', header.size);
        if (path && header.type === 'file') await body.pipeTo(contents.sink(path, header.size), { signal });
        else await body.cancel();
      } catch (error) {
        if (!body.locked) await body.cancel(error).catch(() => undefined);
        throw error;
      }
    }
  } finally {
    await reader.cancel().catch(() => undefined);
    reader.releaseLock();
  }
}

async function writeZip(workspace: Workspace, signal?: AbortSignal): Promise<Blob> {
  const writer = new ZipWriter(new BlobWriter('application/zip'), {
    useWebWorkers: false,
    lastModDate: new Date(1980, 0, 1),
    extendedTimestamp: false,
  });
  for (const path of workspace.directories) {
    signal?.throwIfAborted();
    await writer.add(`${path}/`, undefined, { directory: true, signal });
  }
  for (const file of workspace.files) {
    signal?.throwIfAborted();
    await writer.add(file.path, new Uint8ArrayReader(encodeWorkspaceText(file.text, file.path)), { signal });
  }
  return writer.close();
}
async function writeTar(workspace: Workspace, signal?: AbortSignal): Promise<Blob> {
  signal?.throwIfAborted();
  const bytes = await packTar([
    ...workspace.directories.map((path) => ({
      header: { name: `${path}/`, type: 'directory' as const, size: 0, mtime: new Date(0) },
    })),
    ...workspace.files.map((file) => {
      const body = encodeWorkspaceText(file.text, file.path);
      return { header: { name: file.path, type: 'file' as const, size: body.length, mtime: new Date(0) }, body };
    }),
  ]);
  signal?.throwIfAborted();
  return new Blob([bytes], { type: 'application/x-tar' });
}

async function boundedBlob(stream: ReadableStream<Uint8Array>, signal?: AbortSignal): Promise<Blob> {
  const chunks: Uint8Array<ArrayBuffer>[] = [];
  let length = 0;
  await stream.pipeTo(
    new WritableStream({
      write(chunk) {
        length += chunk.length;
        if (length > maximumArchiveBytes)
          throw new WorkspaceArchiveError('limit', 'The expanded archive exceeds its size limit.');
        chunks.push(new Uint8Array(chunk));
      },
    }),
    { signal },
  );
  return new Blob(chunks);
}

function format(
  metadata: Omit<WorkspaceArchiveFormat, 'read' | 'write'>,
  decode: (archive: Blob, contents: ArchiveContents, signal?: AbortSignal) => Promise<void>,
  encode: WorkspaceArchiveFormat['write'],
): WorkspaceArchiveFormat {
  return Object.freeze({
    ...metadata,
    extensions: Object.freeze([...metadata.extensions]),
    async read(archive: Blob, signal?: AbortSignal) {
      signal?.throwIfAborted();
      if (archive.size > maximumArchiveBytes)
        throw new WorkspaceArchiveError('limit', 'The archive exceeds the import size limit.');
      const contents = new ArchiveContents();
      try {
        await decode(archive, contents, signal);
      } catch (cause) {
        signal?.throwIfAborted();
        if (cause instanceof WorkspaceTextError)
          throw new WorkspaceArchiveError('invalid-text', cause.message, cause.path, { cause });
        if (cause instanceof WorkspaceArchiveError || cause instanceof WorkspacePathError) throw cause;
        throw new WorkspaceArchiveError('invalid-archive', `Could not read the ${metadata.label} archive.`, undefined, {
          cause,
        });
      }
      signal?.throwIfAborted();
      return contents.workspace();
    },
    async write(workspace: Workspace, signal?: AbortSignal) {
      signal?.throwIfAborted();
      try {
        const archive = await encode(workspace, signal);
        signal?.throwIfAborted();
        return archive;
      } catch (cause) {
        signal?.throwIfAborted();
        if (cause instanceof WorkspaceTextError)
          throw new WorkspaceArchiveError('invalid-text', cause.message, cause.path, { cause });
        throw cause;
      }
    },
  });
}
const formats: readonly WorkspaceArchiveFormat[] = Object.freeze([
  format({ id: 'zip', label: 'ZIP', extensions: ['.zip'], mediaType: 'application/zip' }, readZip, writeZip),
  format({ id: 'tar', label: 'TAR', extensions: ['.tar'], mediaType: 'application/x-tar' }, readTar, writeTar),
  format(
    { id: 'tar-gzip', label: 'TAR.GZ', extensions: ['.tar.gz', '.tgz'], mediaType: 'application/gzip' },
    async (archive, contents, signal) =>
      readTar(
        await boundedBlob(archive.stream().pipeThrough(new DecompressionStream('gzip'), { signal }), signal),
        contents,
        signal,
      ),
    async (workspace, signal) =>
      new Blob(
        [
          await boundedBlob(
            (await writeTar(workspace, signal)).stream().pipeThrough(new CompressionStream('gzip'), { signal }),
            signal,
          ),
        ],
        { type: 'application/gzip' },
      ),
  ),
]);

/** Return immutable format definitions in menu order. This module has no language, DOM, or persistence dependencies. */
export function getWorkspaceArchiveFormats(): readonly WorkspaceArchiveFormat[] {
  return formats;
}
