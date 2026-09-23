import { describe, expect, it } from 'vitest';
import { ExampleCatalogError, loadExampleCatalog, type ExampleManifest } from '../src/workspace/examples';
import { sessionFromExample } from '../src/workbench/session';
import type { LanguageDefinition } from '../src/core/contracts';

const manifest: ExampleManifest = {
  id: 'arithmetic',
  title: 'Arithmetic',
  description: 'A file-based example.',
  entryPath: 'start.lang',
};
const assets = (directory: string, metadata: ExampleManifest = manifest) => ({
  [`./${directory}/example.json`]: JSON.stringify(metadata),
  [`./${directory}/${metadata.entryPath}`]: 'source\r\n🌍\n',
});

describe('language-neutral example catalog', () => {
  it('discovers complete examples and preserves their source and input exactly', () => {
    const examples = loadExampleCatalog({
      ...assets('examples/arithmetic', { ...manifest, entryPoint: '', stdin: 'input\r\n🌍\n' }),
      './examples/arithmetic/library.lang': 'a dependency\n',
      './examples/arithmetic/config.json': '{"keep": "as source"}\n',
    });
    expect(examples).toEqual([
      {
        ...manifest,
        entryPoint: '',
        stdin: 'input\r\n🌍\n',
        files: [
          { path: 'start.lang', text: 'source\r\n🌍\n' },
          { path: 'config.json', text: '{"keep": "as source"}\n' },
          { path: 'library.lang', text: 'a dependency\n' },
        ],
      },
    ]);
    const definition: LanguageDefinition = {
      id: 'independent-language',
      name: 'Independent language',
      defaultFilePath: 'new.lang',
      extension: '.lang',
      defaultEntryPoint: 'default',
      examples,
    };
    const session = sessionFromExample(definition, examples[0]);
    expect(session.entryPoint).toBe('');
    expect(session.stdin).toBe('input\r\n🌍\n');
    expect(session.workspace.files.find((file) => file.id === session.entryFileId)?.path).toBe('start.lang');
  });
  it('adds examples through assets alone and orders them independently of paths and glob enumeration', () => {
    const initial = assets('z/renamable-folder', { ...manifest, id: 'first', order: -10 });
    const extended = {
      ...initial,
      ...assets('a/not-an-identifier', { ...manifest, id: 'second', order: 20 }),
      ...assets('y/also-renamable', { ...manifest, id: 'third', order: 20 }),
    };
    expect(loadExampleCatalog(initial).map((example) => example.id)).toEqual(['first']);
    const catalog = loadExampleCatalog(extended);
    expect(catalog.map((example) => example.id)).toEqual(['first', 'second', 'third']);
    expect(loadExampleCatalog(Object.fromEntries(Object.entries(extended).reverse()))).toEqual(catalog);
    expect(catalog.every((example) => !('order' in example))).toBe(true);
  });
  it('accepts an empty catalog and preserves omitted overrides', () => {
    expect(loadExampleCatalog({})).toEqual([]);
    const example = loadExampleCatalog(assets('single'))[0];
    expect(example).not.toHaveProperty('stdin');
    expect(example).not.toHaveProperty('entryPoint');
  });
  it.each([
    { label: 'malformed JSON', metadata: '{' },
    { label: 'unknown fields', metadata: JSON.stringify({ ...manifest, entryFilename: 'start.lang' }) },
    { label: 'missing title', metadata: JSON.stringify({ ...manifest, title: undefined }) },
    { label: 'invalid order', metadata: JSON.stringify({ ...manifest, order: 'first' }) },
    { label: 'invalid entry name', metadata: JSON.stringify({ ...manifest, entryPath: '../outside.lang' }) },
  ])('rejects $label with structured context', ({ metadata }) => {
    try {
      loadExampleCatalog({ ...assets('broken'), './broken/example.json': metadata });
      expect.fail('Expected invalid metadata to fail.');
    } catch (error) {
      if (!(error instanceof ExampleCatalogError)) throw error;
      expect(error).toMatchObject({
        code: 'invalid-manifest',
        assetPath: './broken/example.json',
      });
      expect(error.cause).toBeInstanceOf(Error);
    }
  });
  it('rejects duplicate IDs, missing entry files, and unowned source directories', () => {
    expect(() => loadExampleCatalog({ ...assets('first'), ...assets('second') })).toThrow(
      expect.objectContaining({ code: 'duplicate-id', assetPath: './second/example.json' }),
    );
    expect(() => loadExampleCatalog({ './missing/example.json': JSON.stringify(manifest) })).toThrow(
      expect.objectContaining({ code: 'missing-entry', assetPath: './missing/example.json' }),
    );
    expect(() => loadExampleCatalog({ './orphan/main.lang': 'source' })).toThrow(
      expect.objectContaining({ code: 'missing-manifest', assetPath: './orphan/main.lang' }),
    );
  });
  it.each([
    '/absolute/file.lang',
    './traversal/../file.lang',
    './empty//file.lang',
    './back\\slash/file.lang',
    './bad/\u0000.lang',
    `./long/${'a'.repeat(121)}`,
  ])('rejects an invalid asset path: %j', (path) => {
    expect(() => loadExampleCatalog({ [path]: 'source' })).toThrow(
      expect.objectContaining({ code: 'invalid-path', assetPath: path }),
    );
  });
  it('rejects two keys for the same normalized path', () => {
    expect(() => loadExampleCatalog({ ...assets('duplicate'), 'duplicate/start.lang': 'conflicting content' })).toThrow(
      expect.objectContaining({ code: 'duplicate-asset', assetPath: 'duplicate/start.lang' }),
    );
  });
});

it('discovers nested source paths beneath their nearest manifest and retains empty folders', () => {
  const examples = loadExampleCatalog({
    'first/example.json': JSON.stringify({ ...manifest, entryPath: 'src/start.lang', directories: ['assets/empty'] }),
    'first/src/start.lang': 'start',
    'first/src/library/helper.lang': 'helper',
    'first/second/example.json': JSON.stringify({ ...manifest, id: 'nested-example' }),
    'first/second/start.lang': 'separate example',
  });
  expect(examples[0]?.files).toEqual([
    { path: 'src/start.lang', text: 'start' },
    { path: 'src/library/helper.lang', text: 'helper' },
  ]);
  expect(examples[0]?.directories).toEqual(['assets/empty']);
  expect(examples[1]?.files).toEqual([{ path: 'start.lang', text: 'separate example' }]);
});
