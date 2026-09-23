import { describe, expect, it } from 'vitest';
import { BlobReader, BlobWriter, TextReader, ZipReader, ZipWriter } from '@zip.js/zip.js';
import { packTar, unpackTar, type TarEntry } from 'modern-tar';
import { Workspace, workspaceLimits } from '../src/workspace/model';
import { getWorkspaceArchiveFormats, WorkspaceArchiveError } from '../src/workspace/archives';

const formats = getWorkspaceArchiveFormats();
const zip = formats.find((format) => format.id === 'zip')!;
const tar = formats.find((format) => format.id === 'tar')!;
const contents = new Workspace({
  files: [
    { id: 'entry', path: 'project/src/main.lang', text: '\ufeffhello\r\n🌍\n' },
    { id: 'library', path: 'project/資料/a #?%.lang', text: 'λ → text' },
    { id: 'empty', path: 'empty.txt', text: '' },
    { id: 'long', path: `project/${'p'.repeat(110)}/source.lang`, text: 'long path' },
  ],
  directories: ['empty-directory', 'project/assets/empty'],
});
const comparable = (workspace: Workspace) => ({
  files: workspace.files.map(({ path, text }) => ({ path, text })).sort((a, b) => a.path.localeCompare(b.path)),
  directories: workspace.directories,
});
async function zipFixture(entries: readonly { name: string; text: string; unixMode?: number }[]): Promise<Blob> {
  const writer = new ZipWriter(new BlobWriter(), { useWebWorkers: false });
  for (const entry of entries) await writer.add(entry.name, new TextReader(entry.text), { unixMode: entry.unixMode });
  return writer.close();
}
async function tarFixture(entries: TarEntry[]): Promise<Blob> {
  return new Blob([await packTar(entries)]);
}

describe('workspace archives', () => {
  for (const format of formats) {
    it(`${format.label} preserves folders, empty files, Unicode, BOMs, line endings, and long paths`, async () => {
      const archive = await format.write(contents);
      const restored = await format.read(archive);
      expect(comparable(restored)).toEqual(comparable(contents));
      expect(restored.files.map((file) => file.id)).not.toEqual(contents.files.map((file) => file.id));
      expect(comparable(await format.read(await format.write(new Workspace({ files: [] }))))).toEqual({
        files: [],
        directories: [],
      });
    });
    it(`${format.label} honors cancellation without returning partial contents`, async () => {
      const cancellation = new AbortController();
      const reason = new Error('Cancelled');
      cancellation.abort(reason);
      await expect(format.read(new Blob(), cancellation.signal)).rejects.toBe(reason);
      await expect(format.write(contents, cancellation.signal)).rejects.toBe(reason);
    });
  }
  it('exports ordinary ZIP and TAR entries without embedded project metadata', async () => {
    const reader = new ZipReader(new BlobReader(await zip.write(contents)), { useWebWorkers: false });
    const entries = await reader.getEntries();
    expect(entries.map((entry) => entry.filename)).toEqual([
      ...contents.directories.map((path) => `${path}/`),
      ...contents.files.map((file) => file.path),
    ]);
    await reader.close();
    const parsed = await unpackTar(await (await tar.write(contents)).arrayBuffer(), { strict: true });
    expect(parsed.map((entry) => entry.header.name)).toEqual(entries.map((entry) => entry.filename));
  });
  it('accepts standard ./ prefixes while rejecting duplicate normalized paths and file/folder collisions', async () => {
    const valid = await tarFixture([
      { header: { name: './', type: 'directory', size: 0 } },
      { header: { name: './src/a.lang', size: 5 }, body: 'hello' },
    ]);
    expect((await tar.read(valid)).files[0]?.path).toBe('src/a.lang');
    await expect(
      zip.read(
        await zipFixture([
          { name: 'a.lang', text: 'a' },
          { name: './a.lang', text: 'b' },
        ]),
      ),
    ).rejects.toMatchObject({ code: 'duplicate-entry' });
    await expect(
      zip.read(
        await zipFixture([
          { name: 'src', text: '' },
          { name: 'src/a.lang', text: 'a' },
        ]),
      ),
    ).rejects.toMatchObject({ code: 'path-conflict' });
  });
  it.each(['../escape', '/absolute', 'C:/escape', 'a/../../escape', 'a\\b'])(
    'rejects unsafe archive members: %s',
    async (name) => {
      await expect(zip.read(await zipFixture([{ name, text: 'source' }]))).rejects.toThrow();
      await expect(tar.read(await tarFixture([{ header: { name, size: 6 }, body: 'source' }]))).rejects.toThrow();
    },
  );
  it('rejects links, binary files, invalid UTF-8, damaged headers, and oversized source', async () => {
    await expect(
      zip.read(await zipFixture([{ name: 'link', text: 'target', unixMode: 0o120777 }])),
    ).rejects.toMatchObject({ code: 'unsupported-entry' });
    for (const type of ['symlink', 'link', 'fifo'] as const) {
      await expect(
        tar.read(await tarFixture([{ header: { name: 'special', type, size: 0, linkname: 'target' } }])),
      ).rejects.toMatchObject({ code: 'unsupported-entry' });
    }
    await expect(zip.read(await zipFixture([{ name: 'binary', text: 'a\0b' }]))).rejects.toMatchObject({
      code: 'invalid-text',
    });
    await expect(
      tar.read(await tarFixture([{ header: { name: 'invalid', size: 1 }, body: new Uint8Array([255]) }])),
    ).rejects.toMatchObject({ code: 'invalid-text' });
    const corrupted = new Uint8Array(await (await tar.write(contents)).arrayBuffer());
    corrupted[0] = corrupted[0]! ^ 1;
    await expect(tar.read(new Blob([corrupted]))).rejects.toBeInstanceOf(WorkspaceArchiveError);
    await expect(
      zip.read(await zipFixture([{ name: 'large', text: 'a'.repeat(workspaceLimits.fileBytes + 1) }])),
    ).rejects.toMatchObject({ code: 'limit' });
  });
  it('rejects incomplete TAR headers, bodies, padding, and end markers', async () => {
    const archive = await tar.write(new Workspace({ files: [{ id: 'a', path: 'a.txt', text: 'hello' }] }));
    for (const length of [0, 511, 513, 519, 1024, archive.size - 1]) {
      await expect(tar.read(archive.slice(0, length))).rejects.toMatchObject({ code: 'invalid-archive' });
    }
  });
  it('rejects a ZIP whose stored contents no longer match its CRC', async () => {
    const writer = new ZipWriter(new BlobWriter(), { useWebWorkers: false, level: 0 });
    await writer.add('source.txt', new TextReader('integrity check'));
    const bytes = new Uint8Array(await (await writer.close()).arrayBuffer());
    const view = new DataView(bytes.buffer);
    const bodyOffset = 30 + view.getUint16(26, true) + view.getUint16(28, true);
    bytes[bodyOffset] = bytes[bodyOffset]! ^ 1;
    await expect(zip.read(new Blob([bytes]))).rejects.toMatchObject({ code: 'invalid-archive' });
  });
});
