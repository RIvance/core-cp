import type { Session } from '../src/core/session';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { ExecutionResult, LanguagePlugin, LanguageRuntime } from '../src/core/contracts';
import { defaultSettings, type SessionRepository } from '../src/workbench/session';
import { Workspace } from '../src/workspace/model';
import { WorkbenchController } from '../src/workbench/controller';

const session: Session = {
  languageId: 'independent-language',
  workspace: new Workspace({ files: [{ id: 'first', path: 'source.lang', text: 'original' }] }),
  openFileIds: ['first'],
  activeFileId: 'first',
  entryFileId: 'first',
  entryPoint: 'entry',
  stdin: '',
};
const success: ExecutionResult = { status: 'success', value: 'computed', diagnostics: [], artifacts: [] };
const repository = () => ({
  save: vi.fn<SessionRepository['save']>(),
  saveSettings: vi.fn<SessionRepository['saveSettings']>(),
  saveHistory: vi.fn<SessionRepository['saveHistory']>(),
});
function plugin(runtime: LanguageRuntime): LanguagePlugin {
  return {
    definition: {
      id: session.languageId,
      name: 'Independent language',
      extension: '.lang',
      defaultFilePath: 'source.lang',
      defaultEntryPoint: 'entry',
      examples: [],
    },
    createRuntime: () => runtime,
  };
}
afterEach(() => vi.useRealTimers());
describe('language-neutral workbench controller', () => {
  it('does not overwrite storage when an unchanged workspace is closed', () => {
    const store = repository();
    const controller = new WorkbenchController(plugin({ execute: () => Promise.resolve(success) }), store, session);
    controller.save();
    controller.save();
    controller.dispose();
    expect(store.save).toHaveBeenCalledTimes(1);
    expect(store.saveHistory).toHaveBeenCalledTimes(1);
    expect(store.saveSettings).toHaveBeenCalledTimes(1);
  });
  it('reports service creation failures while keeping execution available', async () => {
    const controller = new WorkbenchController(
      {
        ...plugin({ execute: () => Promise.resolve(success) }),
        createLanguageService: () => {
          throw new Error('Could not start the worker');
        },
      },
      repository(),
      session,
    );
    await controller.connect();
    expect(controller.getSnapshot().service).toEqual({ kind: 'failed', message: 'Could not start the worker' });
    await controller.run();
    expect(controller.getSnapshot().execution).toMatchObject({ kind: 'finished', result: success });
    controller.dispose();
  });
  it('rejects invalid settings before changing the workspace or persisting them', () => {
    const store = repository();
    const controller = new WorkbenchController(plugin({ execute: () => Promise.resolve(success) }), store, session);
    expect(() => controller.configure({ timeout: -1 })).toThrow();
    expect(() => controller.configure({ fontSize: 0 })).toThrow();
    expect(() => controller.configure({ theme: 'not-installed' })).toThrow(RangeError);
    expect(controller.getSnapshot().settings).toEqual(defaultSettings);
    expect(store.saveSettings).not.toHaveBeenCalled();
    controller.dispose();
  });
  it('keeps checkpoints in memory when storage fails and separates input revisions from source versions', () => {
    const store = repository();
    store.saveHistory.mockImplementation(() => {
      throw new Error('History quota reached');
    });
    const controller = new WorkbenchController(plugin({ execute: () => Promise.resolve(success) }), store, session);
    const documentVersion = controller.getSnapshot().workspaceVersion;
    const revision = controller.getSnapshot().revision;
    controller.updateSession({ ...session, stdin: 'input changed' });
    expect(controller.getSnapshot().workspaceVersion).toBe(documentVersion);
    expect(controller.getSnapshot().revision).toBeGreaterThan(revision);
    controller.replaceSession({ ...session, workspace: session.workspace.edit('first', 'replacement') });
    expect(controller.getSnapshot().history[0]?.session.workspace.files[0]?.text).toBe('original');
    expect(controller.getSnapshot().session.workspace.files[0]?.text).toBe('replacement');
    expect(controller.getSnapshot().history).toHaveLength(1);
    controller.save();
    expect(controller.getSnapshot().persistence).toBe('failed');
    controller.dispose();
  });
  it('runs a different language through the same contract, saves source, and tracks result revisions', async () => {
    const execute = vi.fn<LanguageRuntime['execute']>(() => Promise.resolve(success));
    const store = repository();
    const controller = new WorkbenchController(plugin({ execute }), store, session);
    await controller.connect();
    expect(controller.getSnapshot().service.kind).toBe('unavailable');
    await controller.run();
    expect(execute).toHaveBeenCalledOnce();
    expect(execute.mock.calls[0]?.[0]).toMatchObject({
      documents: [{ languageId: session.languageId, text: 'original' }],
      entryDocumentUri: 'file:///workspace/source.lang',
      entryPoint: 'entry',
    });
    expect(execute.mock.calls[0]?.[2]).toBeInstanceOf(AbortSignal);
    const revision = controller.getSnapshot().revision;
    controller.core.edit('first', 'changed');
    expect(controller.getSnapshot().execution).toMatchObject({ kind: 'finished', revision });
    expect(controller.getSnapshot().revision).toBeGreaterThan(revision);
    controller.save();
    expect(store.save.mock.lastCall?.[0].workspace.file('first')?.text).toBe('changed');
    controller.dispose();
  });
  it('keeps file identity, entry selection and undo checkpoints independent of the active tab', async () => {
    const execute = vi.fn<LanguageRuntime['execute']>(() => Promise.resolve(success));
    const controller = new WorkbenchController(plugin({ execute }), repository(), session);
    const added = controller.addFile('other.lang', 'dependency');
    const revision = controller.getSnapshot().revision;
    const firstVersion = controller.core.document('first').version;
    controller.selectFile('first');
    expect(controller.getSnapshot().revision).toBe(revision);
    controller.core.edit(added, 'updated dependency');
    expect(controller.core.document('first').version).toBe(firstVersion);
    expect(controller.core.document(added).version).toBeGreaterThan(firstVersion);
    controller.movePath('other.lang', 'renamed.lang');
    expect(controller.core.document(added).uri).toBe('file:///workspace/renamed.lang');
    controller.updateSession({ ...controller.getSnapshot().session, entryFileId: added });
    await controller.run();
    expect(execute.mock.calls[0]?.[0]).toMatchObject({
      documents: [{ text: 'original' }, { text: 'updated dependency' }],
      entryDocumentUri: 'file:///workspace/renamed.lang',
    });
    controller.closeFile(added);
    expect(controller.getSnapshot().session).toMatchObject({
      entryFileId: added,
      activeFileId: 'first',
      openFileIds: ['first'],
    });
    expect(controller.getSnapshot().session.workspace.files).toHaveLength(2);
    controller.removePath('renamed.lang');
    expect(controller.getSnapshot().session.entryFileId).toBe('first');
    expect(controller.getSnapshot().history[0]?.session.workspace.files).toHaveLength(2);
    controller.closeFile('first');
    expect(controller.getSnapshot().session).toMatchObject({
      entryFileId: 'first',
      activeFileId: null,
      openFileIds: [],
    });
    await controller.run();
    controller.removePath('source.lang');
    expect(controller.getSnapshot().session.workspace.files).toEqual([]);
    expect(controller.getSnapshot().session).toMatchObject({ entryFileId: null, activeFileId: null });
    await expect(controller.run()).rejects.toThrow('Add a file');
    const next = controller.addFile('new.lang');
    expect(controller.getSnapshot().session).toMatchObject({ entryFileId: next, activeFileId: next });
    controller.dispose();
  });
  it('rejects duplicate names and invalid selections without changing the workspace', () => {
    const controller = new WorkbenchController(
      plugin({ execute: () => Promise.resolve(success) }),
      repository(),
      session,
    );
    expect(() => controller.addFile('source.lang')).toThrow();
    expect(() => controller.selectFile('missing')).toThrow();
    controller.addFile('other.lang');
    expect(() => controller.movePath('other.lang', 'source.lang')).toThrow();
    expect(controller.getSnapshot().session.workspace.files.map((file) => file.path)).toEqual([
      'source.lang',
      'other.lang',
    ]);
    controller.dispose();
  });
  it('keeps cancelled and stale executions from overwriting the next run', async () => {
    const completions: ((value: ExecutionResult) => void)[] = [];
    const signals: AbortSignal[] = [];
    const controller = new WorkbenchController(
      plugin({
        execute: (_input, _emit, signal) => {
          signals.push(signal);
          return new Promise((resolve) => completions.push(resolve));
        },
      }),
      repository(),
      session,
    );
    const first = controller.run();
    controller.stop();
    expect(signals[0]?.aborted).toBe(true);
    expect(controller.getSnapshot().execution.kind).toBe('stopped');
    const second = controller.run();
    completions[0]?.({ ...success, value: 'old' });
    await first;
    expect(controller.getSnapshot().execution.kind).toBe('running');
    completions[1]?.({ ...success, value: 'new' });
    await second;
    expect(controller.getSnapshot().execution).toMatchObject({ kind: 'finished', result: { value: 'new' } });
    controller.dispose();
  });
  it('enforces execution limits and reports storage failures without losing the in-memory source', async () => {
    vi.useFakeTimers();
    const store = repository();
    store.save.mockImplementation(() => {
      throw new Error('Storage unavailable');
    });
    const runtime: LanguageRuntime = {
      execute: (_input, _emit, signal) =>
        new Promise((_resolve, reject) => {
          signal.addEventListener('abort', () => reject(new DOMException('Cancelled', 'AbortError')));
        }),
    };
    const controller = new WorkbenchController(plugin(runtime), store, session, { ...defaultSettings, timeout: 1 });
    controller.core.edit('first', 'keep this source');
    const execution = controller.run();
    await vi.advanceTimersByTimeAsync(1100);
    await execution;
    expect(controller.getSnapshot().execution).toMatchObject({
      kind: 'stopped',
      reason: 'Execution time limit reached',
    });
    expect(controller.getSnapshot().persistence).toBe('failed');
    expect(controller.getSnapshot().session.workspace.files[0]?.text).toBe('keep this source');
    controller.dispose();
  });
});

it('synchronizes nested document URIs and closes old paths before a moved subtree is reopened', async () => {
  const synchronize = vi.fn();
  const close = vi.fn();
  const service = {
    capabilities: {},
    start: async () => {},
    synchronize,
    close,
    onDiagnostics: () => () => {},
    onLog: () => () => {},
    onFailure: () => () => {},
    dispose: () => {},
  };
  const controller = new WorkbenchController(
    { ...plugin({ execute: () => Promise.resolve(success) }), createLanguageService: () => service },
    repository(),
    session,
  );
  await controller.connect();
  const id = controller.addFile('lib/資料 #%.lang', 'nested source');
  controller.addDirectory('lib/empty');
  controller.selectFile(id);
  controller.updateSession({ ...controller.getSnapshot().session, entryFileId: id });
  controller.core.synchronizeDocuments();
  const oldUri = 'file:///workspace/lib/%E8%B3%87%E6%96%99%20%23%25.lang';
  expect(synchronize).toHaveBeenCalledWith(expect.objectContaining({ uri: oldUri, text: 'nested source' }));
  synchronize.mockClear();
  controller.movePath('lib', 'src/dependencies');
  expect(close).toHaveBeenCalledWith(oldUri);
  controller.core.synchronizeDocuments();
  expect(controller.core.document(id).uri).toBe('file:///workspace/src/dependencies/%E8%B3%87%E6%96%99%20%23%25.lang');
  expect(controller.getSnapshot().session).toMatchObject({
    activeFileId: id,
    entryFileId: id,
    openFileIds: ['first', id],
  });
  expect(synchronize).toHaveBeenCalledWith(expect.objectContaining({ uri: controller.core.document(id).uri }));
  const previous = controller.getSnapshot();
  expect(() => controller.movePath('src', 'src/dependencies/inside')).toThrow();
  expect(controller.getSnapshot()).toBe(previous);
  controller.closeFile(id);
  expect(controller.core.document(id).text).toBe('nested source');
  controller.selectFile(id);
  controller.removePath('src');
  expect(close).toHaveBeenCalledWith('file:///workspace/src/dependencies/%E8%B3%87%E6%96%99%20%23%25.lang');
  expect(controller.getSnapshot().session.entryFileId).toBe('first');
  controller.dispose();
});

it('restores moved files without assigning the same identity to different paths', () => {
  const controller = new WorkbenchController(
    plugin({ execute: () => Promise.resolve(success) }),
    repository(),
    session,
  );
  const second = controller.addFile('other.lang', 'other');
  controller.replaceSession({
    ...session,
    workspace: new Workspace({
      files: [
        { id: 'first', path: 'other.lang', text: 'restored other' },
        { id: second, path: 'moved/source.lang', text: 'restored entry' },
      ],
    }),
    openFileIds: ['first', second],
    activeFileId: second,
    entryFileId: second,
  });
  const restored = controller.getSnapshot().session;
  expect(restored.workspace.file(second)).toMatchObject({ path: 'other.lang', text: 'restored other' });
  expect(restored.workspace.file(restored.entryFileId!)).toMatchObject({
    path: 'moved/source.lang',
    text: 'restored entry',
  });
  expect(new Set(restored.openFileIds).size).toBe(2);
  const previous = controller.getSnapshot();
  expect(() => controller.replaceSession({ ...restored, languageId: 'another-language' })).toThrow();
  expect(controller.getSnapshot()).toBe(previous);
  controller.dispose();
});
