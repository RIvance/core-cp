import { describe, expect, it } from 'vitest';
import { Workspace, workspaceLimits } from '../src/workspace/model';
import { parseWorkspacePath, workspaceDocumentUri } from '../src/workspace/path';

const initial = () =>
  new Workspace({
    files: [
      { id: 'entry', path: 'src/main.lang', text: 'entry' },
      { id: 'dependency', path: 'src/lib/main.lang', text: 'dependency' },
      { id: 'sibling', path: 'src-extra/main.lang', text: 'sibling' },
    ],
    directories: ['src/empty'],
  });

describe('virtual workspace', () => {
  it('projects flat files and empty folders into directory children without duplicating content', () => {
    const workspace = initial();
    expect(workspace.directories).toEqual(['src', 'src-extra', 'src/empty', 'src/lib']);
    expect(workspace.readDirectory('src').map((entry) => [entry.kind, entry.path])).toEqual([
      ['directory', 'src/empty'],
      ['directory', 'src/lib'],
      ['file', 'src/main.lang'],
    ]);
    expect(workspace.readDirectory('src/empty')).toEqual([]);
    expect(Object.isFrozen(workspace.files[0])).toBe(true);
    expect(Object.isFrozen(workspace.snapshot)).toBe(true);
  });
  it('moves complete subtrees atomically and preserves identities, content, and unrelated sibling prefixes', () => {
    const original = initial();
    const moved = original.move('src', 'project/source');
    expect(moved.files).toEqual([
      { id: 'entry', path: 'project/source/main.lang', text: 'entry' },
      { id: 'dependency', path: 'project/source/lib/main.lang', text: 'dependency' },
      { id: 'sibling', path: 'src-extra/main.lang', text: 'sibling' },
    ]);
    expect(moved.entry('project/source/empty')?.kind).toBe('directory');
    expect(original.file('entry')?.path).toBe('src/main.lang');
    expect(() => original.move('src', 'src/lib/nested')).toThrow(expect.objectContaining({ code: 'invalid-move' }));
    expect(() => original.move('src', 'src-extra')).toThrow(expect.objectContaining({ code: 'path-conflict' }));
    expect(() => original.move('src/main.lang', 'src/lib/main.lang')).toThrow();
    expect(original.files).toHaveLength(3);
  });
  it('keeps empty parents and performs deletion, editing, and merging through the same invariants', () => {
    const workspace = initial().remove('src/lib');
    expect(workspace.file('dependency')).toBeUndefined();
    expect(workspace.entry('src')).toEqual({ kind: 'directory', path: 'src' });
    const incoming = new Workspace({ files: [{ id: 'new-id', path: 'src/main.lang', text: 'replacement' }] });
    const merged = workspace.merge(incoming);
    expect(merged.file('entry')?.text).toBe('replacement');
    expect(merged.file('new-id')).toBeUndefined();
    expect(merged.edit('entry', 'changed').file('entry')?.path).toBe('src/main.lang');
    expect(() => merged.edit('missing', '')).toThrow(expect.objectContaining({ code: 'missing-entry' }));
    expect(() => merged.addFile({ id: 'new', path: 'src', text: '' })).toThrow(
      expect.objectContaining({ code: 'path-conflict' }),
    );
    expect(() => merged.addDirectory('src/main.lang/child')).toThrow(
      expect.objectContaining({ code: 'path-conflict' }),
    );
    expect(() => merged.merge(new Workspace({ files: [{ id: 'new', path: 'src/empty', text: '' }] }))).toThrow();
  });
  it('accepts case-distinct paths, repeated basenames, and literal URI punctuation without aliases', () => {
    const path = parseWorkspacePath('資料/a b#c?%!.lang');
    expect(workspaceDocumentUri('file:///workspace', path)).toBe(
      'file:///workspace/%E8%B3%87%E6%96%99/a%20b%23c%3F%25%21.lang',
    );
    const workspace = new Workspace({
      files: [
        { id: 'a', path: 'src/A.lang', text: '' },
        { id: 'b', path: 'src/a.lang', text: '' },
      ],
    });
    expect(workspace.files).toHaveLength(2);
  });
  it.each([
    '',
    '/',
    '/src/a',
    'a/',
    'a//b',
    'a/../b',
    './a',
    'a/./b',
    'C:/src/a',
    'C:a',
    'a\\b',
    'a\0b',
    'a\u007fb',
    '\ud800',
    'a'.repeat(121),
  ])('rejects invalid paths without normalizing them: %j', (path) => {
    expect(() => parseWorkspacePath(path)).toThrow(expect.objectContaining({ path }));
  });
  it('enforces uniqueness and resource limits at construction and editing, without mutating the original', () => {
    const workspace = initial();
    expect(() => workspace.addFile({ id: 'entry', path: 'unique.lang', text: '' })).toThrow(
      expect.objectContaining({ code: 'duplicate-id' }),
    );
    expect(() => workspace.addFile({ id: 'unique', path: 'src/main.lang', text: '' })).toThrow(
      expect.objectContaining({ code: 'path-conflict' }),
    );
    expect(() => workspace.edit('entry', '🌍'.repeat(Math.floor(workspaceLimits.fileBytes / 4) + 1))).toThrow(
      expect.objectContaining({ code: 'limit' }),
    );
    expect(workspace.file('entry')?.text).toBe('entry');
  });
});
