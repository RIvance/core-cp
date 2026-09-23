import { afterEach, describe, expect, it, vi } from 'vitest';
import { EmbeddedController } from '../src/embedded/controller';
import type { EmbeddedContentChange } from '../src/embedded/api';
import type { LanguagePlugin, LanguageRuntime, LanguageService } from '../src/core/contracts';
import { ReadOnlyDocumentError } from '../src/core/controller';
import { defaultPlaygroundSettings } from '../src/core/settings';
import { getTheme, setTheme } from '../src/appearance/page-theme';

const plugin: LanguagePlugin = {
  definition: {
    id: 'text',
    name: 'Text',
    extension: '.txt',
    defaultFilePath: 'main.txt',
    defaultEntryPoint: '',
    examples: [],
  },
  createRuntime: () => ({
    execute: () => Promise.resolve({ status: 'success', value: 'done', diagnostics: [], artifacts: [] }),
  }),
};
const instances: EmbeddedController[] = [];
function create(
  options: Omit<ConstructorParameters<typeof EmbeddedController>[0], 'plugin'> & { plugin?: LanguagePlugin } = {},
) {
  const instance = new EmbeddedController({ plugin, ...options });
  instances.push(instance);
  return instance;
}
afterEach(() => {
  instances.splice(0).forEach((instance) => instance.dispose());
  setTheme('dark');
  vi.useRealTimers();
});

describe('embedded host API', () => {
  it('defaults to one blank file and keeps simultaneous instances independent', async () => {
    setTheme('ocean');
    const first = create();
    const second = create({ value: 'second' });
    expect(first.getValue()).toBe('');
    expect(first.getOptions()).toMatchObject({
      ...defaultPlaygroundSettings,
      title: 'Text',
      entryFile: 'main.txt',
      panel: 'output',
      panels: ['output', 'problems'],
      theme: { id: 'ocean' },
      breadcrumbs: false,
      stdin: '',
    });
    expect(first.getSnapshot().session.openFileIds).toHaveLength(1);
    expect(first.workspaceUri).not.toBe(second.workspaceUri);
    first.setValue('first');
    first.updateOptions({ fontSize: 18, stdin: 'one', wordWrap: true });
    await first.run();
    expect(first.getSnapshot().execution.kind).toBe('finished');
    expect(second.getSnapshot().execution.kind).toBe('idle');
    expect(second.getOptions()).toMatchObject({ fontSize: 14, stdin: '', wordWrap: false });
    first.dispose();
    expect(second.getValue()).toBe('second');
    await second.run();
    expect(second.getSnapshot().execution.kind).toBe('finished');
  });
  it('protects user edits while allowing the host to update fixed files in one transaction', () => {
    const instance = create({
      files: [
        { path: 'lib/static.txt', value: 'fixed', readOnly: true },
        { path: 'main.txt', value: 'editable' },
      ],
      activeFile: 'main.txt',
      entryFile: 'main.txt',
    });
    const changes: EmbeddedContentChange[] = [];
    const subscription = instance.onDidChangeContent((event) => changes.push(event));
    const fixedId = instance.getSnapshot().session.workspace.files[0]!.id;
    expect(() => instance.core.edit(fixedId, 'user edit')).toThrow(ReadOnlyDocumentError);
    expect(instance.getValue('lib/static.txt')).toBe('fixed');
    instance.setValues([
      { path: 'lib/static.txt', value: 'host update' },
      { path: 'main.txt', value: 'new content' },
    ]);
    expect(changes).toEqual([
      {
        changes: [
          { path: 'lib/static.txt', previousValue: 'fixed', value: 'host update' },
          { path: 'main.txt', previousValue: 'editable', value: 'new content' },
        ],
      },
    ]);
    instance.setActiveFile('lib/static.txt');
    instance.updateOptions({ fontSize: 16 });
    expect(changes).toHaveLength(1);
    expect(instance.getOptions().entryFile).toBe('main.txt');
    instance.setReadOnly(false);
    instance.core.edit(fixedId, 'now editable');
    expect(changes).toHaveLength(2);
    subscription.dispose();
    instance.setValue('no subscription');
    expect(changes).toHaveLength(2);
    expect(instance.getFiles().map((file) => file.path)).toEqual(['lib/static.txt', 'main.txt']);
  });
  it('refreshes pending analysis after the host changes a document access policy', async () => {
    let release!: () => void;
    let pending = true;
    const instance = create({
      plugin: {
        ...plugin,
        createLanguageService: () => ({
          capabilities: {
            symbols: (document) => {
              const symbols = [
                {
                  name: 'entry',
                  kind: 'function' as const,
                  children: [],
                  location: {
                    uri: document.uri,
                    range: { start: { line: 0, character: 0 }, end: { line: 0, character: 1 } },
                  },
                },
              ];
              if (!pending) return Promise.resolve(symbols);
              pending = false;
              return new Promise((resolve) => {
                release = () => resolve(symbols);
              });
            },
          },
          start: () => Promise.resolve(),
          synchronize() {},
          close() {},
          onDiagnostics: () => () => {},
          onLog: () => () => {},
          onFailure: () => () => {},
          dispose() {},
        }),
      },
      value: 'entry',
    });
    await instance.restartLanguageService();
    instance.setReadOnly(true);
    release();
    await vi.waitFor(() => expect(instance.getSnapshot().documents[0]!.symbols).toMatchObject([{ name: 'entry' }]));
    expect(instance.getFiles()[0]!.readOnly).toBe(true);
  });
  it('validates entire content/configuration operations before changing any state or page theme', () => {
    const instance = create({
      files: [
        { path: 'a.txt', value: 'a' },
        { path: 'b.txt', value: 'b' },
      ],
    });
    const snapshot = instance.getSnapshot();
    expect(() =>
      instance.setValues([
        { path: 'a.txt', value: 'changed' },
        { path: 'missing.txt', value: 'no' },
      ]),
    ).toThrow(RangeError);
    expect(() =>
      instance.setValues([
        { path: 'a.txt', value: '1' },
        { path: 'a.txt', value: '2' },
      ]),
    ).toThrow(RangeError);
    expect(() =>
      instance.setValues([
        { path: 'a.txt', value: 'ok' },
        { path: 'b.txt', value: 'x'.repeat(600_000) },
      ]),
    ).toThrow();
    expect(() =>
      instance.updateOptions({ fontSize: 19, title: 'changed', entryFile: 'missing.txt', theme: 'light' }),
    ).toThrow();
    expect(() => instance.updateOptions({ fontSize: 19, panels: ['logs'], panel: 'output' })).toThrow();
    expect(() => instance.updateOptions({ fontSize: 19, theme: 'missing' })).toThrow();
    expect(instance.getSnapshot()).toBe(snapshot);
    expect(instance.getOptions()).toMatchObject({ fontSize: 14, title: 'Text' });
    expect(getTheme().id).toBe('dark');
    instance.updateOptions({ panels: ['logs', 'input'] });
    expect(instance.getOptions().panel).toBe('logs');
  });
  it('rejects malformed initial options and exposes disposal before editor readiness', async () => {
    expect(() => create({ files: [] })).toThrow();
    expect(() => create({ files: [{ path: '../outside' }] })).toThrow();
    expect(() => create({ value: 'x', files: [{ path: 'x' }] })).toThrow();
    expect(() => create({ activeFile: 'unknown.txt' })).toThrow();
    expect(() => create({ panels: ['output', 'output'] })).toThrow();
    const instance = create();
    const disposed = vi.fn();
    instance.onDidDispose(disposed);
    instance.dispose();
    instance.dispose();
    expect(disposed).toHaveBeenCalledOnce();
    await expect(instance.ready).rejects.toMatchObject({ name: 'AbortError' });
    expect(instance.isDisposed()).toBe(true);
    expect(() => instance.getValue()).toThrow('disposed');
    expect(() => instance.updateOptions({ title: 'late' })).toThrow('disposed');
  });
  it('settles a stopped run even if the backend ignores cancellation and discards its late result', async () => {
    let complete!: (result: Awaited<ReturnType<LanguageRuntime['execute']>>) => void;
    let emit!: Parameters<LanguageRuntime['execute']>[1];
    const instance = create({
      plugin: {
        ...plugin,
        createRuntime: () => ({
          execute: (_input, output) => {
            emit = output;
            return new Promise((resolve) => {
              complete = resolve;
            });
          },
        }),
      },
    });
    const execution = instance.run();
    emit({ channel: 'stdout', text: 'before' });
    instance.stop();
    await execution;
    complete({ status: 'success', value: 'late', diagnostics: [], artifacts: [] });
    emit({ channel: 'stdout', text: 'after' });
    await Promise.resolve();
    expect(instance.getSnapshot().execution).toMatchObject({
      kind: 'stopped',
      output: [{ channel: 'stdout', text: 'before' }],
    });
  });
  it('allows observers to stop immediately without creating a runtime', async () => {
    const factory = vi.fn(() => plugin.createRuntime());
    const instance = create({ plugin: { ...plugin, createRuntime: factory } });
    instance.onDidChangeState((state) => {
      if (state.execution.kind === 'running') instance.stop();
    });
    await instance.run();
    expect(factory).not.toHaveBeenCalled();
    expect(instance.getSnapshot().execution.kind).toBe('stopped');
  });
  it('owns each service and unsubscribes on restart and disposal, including pending startup', async () => {
    const releases: ReturnType<typeof vi.fn>[] = [];
    const disposals: ReturnType<typeof vi.fn>[] = [];
    const servicePlugin: LanguagePlugin = {
      ...plugin,
      createLanguageService: () => {
        const release = vi.fn();
        releases.push(release);
        const dispose = vi.fn();
        disposals.push(dispose);
        const service: LanguageService = {
          capabilities: {},
          start: () => Promise.resolve(),
          synchronize() {},
          close() {},
          onDiagnostics: () => release,
          onLog: () => release,
          onFailure: () => release,
          dispose,
        };
        return service;
      },
    };
    const first = create({ plugin: servicePlugin });
    const second = create({ plugin: servicePlugin });
    await first.restartLanguageService();
    await second.restartLanguageService();
    await first.restartLanguageService();
    expect(releases[0]).toHaveBeenCalledTimes(3);
    expect(disposals[0]).toHaveBeenCalledOnce();
    expect(disposals[1]).not.toHaveBeenCalled();
    first.dispose();
    second.dispose();
    expect(releases[1]).toHaveBeenCalledTimes(3);
    expect(releases[2]).toHaveBeenCalledTimes(3);
    expect(disposals.every((dispose) => dispose.mock.calls.length === 1)).toBe(true);
  });
});
