import { describe, expect, it } from 'vitest';
import { Workspace } from '../src/workspace/model';
import { searchWorkspace } from '../src/workspace/search';

const workspace = (text: string) => new Workspace({ files: [{ id: 'source', path: 'notes.txt', text }] });

describe('workspace text search', () => {
  it('searches every file using stable identities and current paths, without language metadata', () => {
    const original = new Workspace({
      files: [
        { id: 'one', path: 'one.any', text: 'value' },
        { id: 'two', path: 'nested/two.md', text: 'VALUE and value' },
      ],
    });
    const renamed = original.move('nested', 'renamed');
    const result = searchWorkspace(renamed, 'value');
    expect(result.count).toBe(3);
    expect(result.files.map(({ fileId, path }) => ({ fileId, path }))).toEqual([
      { fileId: 'one', path: 'one.any' },
      { fileId: 'two', path: 'renamed/two.md' },
    ]);
    expect(original.file('two')?.path).toBe('nested/two.md');
  });
  it('reports UTF-16 ranges through astral text and all common newline forms', () => {
    const result = searchWorkspace(workspace('😀hit\r\nhit\rhit\nhit'), 'hit');
    expect(result.files[0]?.matches.map(({ range }) => range)).toEqual([
      { start: { line: 0, character: 2 }, end: { line: 0, character: 5 } },
      ...[1, 2, 3].map((line) => ({ start: { line, character: 0 }, end: { line, character: 3 } })),
    ]);
  });
  it('distinguishes literal text, Unicode word boundaries, and case sensitivity', () => {
    const source = workspace('foo foobar fooé _foo foo9 foó Foo .+');
    expect(searchWorkspace(source, 'foo', { wholeWord: true }).count).toBe(2);
    expect(searchWorkspace(source, 'foo', { wholeWord: true, caseSensitive: true }).count).toBe(1);
    expect(searchWorkspace(source, '.+').count).toBe(1);
    expect(searchWorkspace(source, '.+', { regularExpression: true }).files[0]?.matches[0]?.range.end.character).toBe(
      source.files[0]?.text.length,
    );
    expect(searchWorkspace(source, '').count).toBe(0);
    expect(searchWorkspace(source, ' ').count).toBe(7);
  });
  it('advances zero-width regular expressions by Unicode scalars', () => {
    const result = searchWorkspace(workspace('😀x'), '(?=.)', { regularExpression: true });
    expect(result.files[0]?.matches.map(({ range }) => range.start.character)).toEqual([0, 2]);
    expect(searchWorkspace(workspace('a'), '^|$', { regularExpression: true }).count).toBe(2);
  });
  it('reports truncation only after finding another match and bounds preview work', () => {
    expect(searchWorkspace(workspace('a a'), 'a', { limit: 2 })).toMatchObject({ count: 2, truncated: false });
    expect(searchWorkspace(workspace('a a a'), 'a', { limit: 2 })).toMatchObject({ count: 2, truncated: true });
    const long = searchWorkspace(workspace('😀'.repeat(5_000)), '.+', { regularExpression: true });
    expect(long.files[0]?.matches[0]).toMatchObject({
      range: { end: { line: 0, character: 10_000 } },
      preview: { match: '😀'.repeat(100) + '…' },
    });
    const frequent = searchWorkspace(workspace('x'.repeat(100_000)), 'x');
    expect(frequent).toMatchObject({ count: 1_000, truncated: true });
    expect(frequent.files[0]?.matches.every((match) => match.preview.after.length <= 81)).toBe(true);
  });
  it('rejects invalid patterns and limits with structured errors', () => {
    expect(() => searchWorkspace(workspace('text'), '[', { regularExpression: true })).toThrow(
      expect.objectContaining({ code: 'invalid-pattern' }),
    );
    for (const limit of [0, -1, 0.5, Infinity]) {
      expect(() => searchWorkspace(workspace('text'), 'text', { limit })).toThrow(
        expect.objectContaining({ code: 'invalid-limit' }),
      );
    }
  });
});
