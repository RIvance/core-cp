import { z } from 'zod';
import type { Example } from '../core/contracts';
import { Workspace } from './model';
import { parseWorkspacePath, parentPath, type WorkspacePath } from './path';

/** Metadata stored in example.json. Descendant source assets belong to their nearest manifest directory. */
export interface ExampleManifest extends Omit<Example, 'files'> {
  /** Display priority, ascending; defaults to 0. Equal priorities sort by stable example ID. */
  readonly order?: number;
}
const manifestSchema: z.ZodType<ExampleManifest> = z.strictObject({
  id: z.string().min(1),
  title: z.string().min(1),
  description: z.string(),
  entryPath: z.string().transform(parseWorkspacePath),
  directories: z.array(z.string()).optional(),
  entryPoint: z.string().optional(),
  stdin: z.string().optional(),
  order: z.number().int().optional(),
});

/** Validation stage that rejected an example; assetPath identifies the original input key. */
export type ExampleCatalogErrorCode =
  | 'invalid-path'
  | 'duplicate-asset'
  | 'missing-manifest'
  | 'invalid-manifest'
  | 'duplicate-id'
  | 'missing-entry'
  | 'invalid-workspace';

/** A catalog input error with structured context and the underlying validation failure. */
export class ExampleCatalogError extends Error {
  /** code identifies the failed invariant, assetPath the input key, and options the underlying cause. */
  constructor(
    readonly code: ExampleCatalogErrorCode,
    readonly assetPath: string,
    message: string,
    options?: ErrorOptions,
  ) {
    super(`${assetPath}: ${message}`, options);
    this.name = 'ExampleCatalogError';
  }
}
interface Asset {
  path: string;
  relative: WorkspacePath;
  text: string;
}
const compareText = (a: string, b: string) => (a < b ? -1 : a > b ? 1 : 0);

/**
 * Discover language-owned examples from path/text assets, independent of a filesystem or bundler.
 * Paths are canonical relative POSIX paths with an optional leading './'. Invalid or duplicate paths fail.
 * Every asset belongs to the nearest ancestor directory containing example.json; nested source folders need
 * no manifest. Nested manifests start separate examples. Orphaned assets are errors.
 *
 * Manifests require id, title, description, and entryPath. Optional fields are entryPoint, stdin, directories
 * (empty folders), and integer order. Unknown fields and duplicate IDs are rejected. Entry paths are relative
 * to the manifest directory. Contents obey Workspace invariants and limits; text is preserved exactly.
 * Results sort by order, then ID; files put the entry first, followed by code-unit path order. An empty input
 * returns no examples. Throws ExampleCatalogError; JSON, path, and workspace failures retain their cause.
 */
export function loadExampleCatalog(input: Readonly<Record<string, string>>): readonly Example[] {
  const assets = new Map<WorkspacePath, Asset>();
  for (const [path, text] of Object.entries(input).sort(([a], [b]) => compareText(a, b))) {
    let relative: WorkspacePath;
    try {
      relative = parseWorkspacePath(path.startsWith('./') ? path.slice(2) : path);
    } catch (cause) {
      throw new ExampleCatalogError('invalid-path', path, 'Invalid relative asset path.', { cause });
    }
    if (assets.has(relative))
      throw new ExampleCatalogError('duplicate-asset', path, 'This asset path occurs more than once.');
    assets.set(relative, { path, relative, text });
  }
  const manifests = new Map<WorkspacePath | '', { asset: Asset; files: { path: string; text: string }[] }>();
  for (const asset of assets.values()) {
    if (asset.relative === 'example.json' || asset.relative.endsWith('/example.json'))
      manifests.set(parentPath(asset.relative), { asset, files: [] });
  }
  for (const asset of assets.values()) {
    if (manifests.get(parentPath(asset.relative))?.asset === asset) continue;
    let directory = parentPath(asset.relative);
    while (directory && !manifests.has(directory)) directory = parentPath(directory);
    const owner = manifests.get(directory);
    if (!owner) throw new ExampleCatalogError('missing-manifest', asset.path, 'This asset has no parent example.json.');
    owner.files.push({
      path: directory ? asset.relative.slice(directory.length + 1) : asset.relative,
      text: asset.text,
    });
  }
  const identifiers = new Set<string>();
  const examples = [...manifests.values()].map(({ asset, files }) => {
    let metadata: ExampleManifest;
    try {
      metadata = manifestSchema.parse(JSON.parse(asset.text));
    } catch (cause) {
      throw new ExampleCatalogError('invalid-manifest', asset.path, 'Invalid example metadata.', { cause });
    }
    if (identifiers.has(metadata.id))
      throw new ExampleCatalogError('duplicate-id', asset.path, `Example ID “${metadata.id}” occurs more than once.`);
    identifiers.add(metadata.id);
    if (!files.some((file) => file.path === metadata.entryPath))
      throw new ExampleCatalogError('missing-entry', asset.path, `Entry file “${metadata.entryPath}” is missing.`);
    try {
      new Workspace({ files: files.map((file) => ({ ...file, id: file.path })), directories: metadata.directories });
    } catch (cause) {
      throw new ExampleCatalogError('invalid-workspace', asset.path, 'Invalid example workspace.', { cause });
    }
    files.sort(
      (a, b) =>
        Number(b.path === metadata.entryPath) - Number(a.path === metadata.entryPath) || compareText(a.path, b.path),
    );
    const { order = 0, ...example } = metadata;
    return { order, example: { ...example, files } };
  });
  return examples
    .sort((a, b) => a.order - b.order || compareText(a.example.id, b.example.id))
    .map(({ example }) => example);
}
