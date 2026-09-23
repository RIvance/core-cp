import type { Session } from '../src/core/session';
import { describe, expect, it } from 'vitest';
import {
  exportSession,
  importSession,
  SessionStore,
  sessionFromFragment,
  shareFragment,
} from '../src/persistence/session-store';
import { Workspace } from '../src/workspace/model';
import { appendCheckpoint, defaultSettings, type Snapshot } from '../src/workbench/session';

const session: Session = {
  languageId: 'test-language',
  workspace: new Workspace({
    files: [
      { id: 'source', path: 'source.test', text: 'λ → 🌍\n"&<#' },
      { id: 'dependency', path: 'library.test', text: 'dependency' },
    ],
    directories: ['empty'],
  }),
  openFileIds: ['source', 'dependency'],
  activeFileId: 'dependency',
  entryFileId: 'source',
  entryPoint: 'start',
  stdin: '你好',
};
describe('session persistence boundary', () => {
  it('adds display preferences to older settings without resetting existing choices', () => {
    let stored = JSON.stringify({
      ...defaultSettings,
      fontSize: 21,
      wordWrap: true,
      inlayHints: undefined,
      semanticHighlighting: undefined,
    });
    const store = new SessionStore(
      {
        getItem: () => stored,
        setItem: (_key, value) => {
          stored = value;
        },
      },
      session.languageId,
    );
    expect(store.settings()).toMatchObject({
      fontSize: 21,
      wordWrap: true,
      inlayHints: true,
      semanticHighlighting: true,
    });
    store.saveSettings({ ...store.settings(), inlayHints: false, semanticHighlighting: false });
    expect(store.settings()).toMatchObject({
      fontSize: 21,
      wordWrap: true,
      inlayHints: false,
      semanticHighlighting: false,
    });
  });
  it('round-trips source, input and language identity through export and sharing', () => {
    expect(importSession(exportSession(session))).toEqual(session);
    expect(sessionFromFragment(shareFragment(session))).toEqual(session);
  });
  it('rejects unsupported versions, invalid file names and damaged links', () => {
    expect(() => importSession('{"format":"language-playground","version":2}')).toThrow();
    const invalid = {
      format: 'language-playground',
      version: 3,
      session: {
        ...session,
        workspace: { ...session.workspace.snapshot, files: [{ ...session.workspace.files[0], path: '../escape' }] },
      },
    };
    expect(() => importSession(JSON.stringify(invalid))).toThrow();
    expect(() => sessionFromFragment('#code=invalid')).toThrow();
    expect(sessionFromFragment('#unrelated')).toBeUndefined();
  });
  it('migrates single-file projects and browser saves at the serialization boundary', () => {
    const legacy = JSON.stringify({
      format: 'language-playground',
      version: 1,
      session: {
        languageId: 'test-language',
        fileName: 'old.test',
        text: 'old source',
        entryPoint: 'start',
        stdin: 'input',
      },
    });
    const migrated = importSession(legacy);
    expect(migrated.workspace.files).toMatchObject([{ path: 'old.test', text: 'old source' }]);
    expect(migrated.workspace.files[0]?.id).toBeTypeOf('string');
    expect(migrated.activeFileId).toBe(migrated.workspace.files[0]?.id);
    expect(migrated.entryFileId).toBe(migrated.workspace.files[0]?.id);
    expect(importSession(exportSession(migrated))).toEqual(migrated);
    const store = new SessionStore({ getItem: () => legacy, setItem: () => {} }, 'test-language');
    expect(store.load()?.workspace.files[0]?.text).toBe('old source');
  });
  it('rejects duplicate identities, duplicate names, dangling selections and excessive source', () => {
    const invalid = {
      format: 'language-playground',
      version: 3,
      session: {
        ...session,
        workspace: { ...session.workspace.snapshot, files: [session.workspace.files[0], session.workspace.files[0]] },
      },
    };
    expect(() => importSession(JSON.stringify(invalid))).toThrow();
    expect(() => exportSession({ ...session, activeFileId: 'missing' })).toThrow();
    expect(() => session.workspace.edit('source', '🌍'.repeat(128_001))).toThrow();
    const empty = {
      ...session,
      workspace: new Workspace({ files: [], directories: ['empty'] }),
      openFileIds: [],
      activeFileId: null,
      entryFileId: null,
    };
    expect(importSession(exportSession(empty))).toEqual(empty);
  });
  it('stores independent checkpoints and retains the newest 20', () => {
    const data = new Map<string, string>();
    const store = new SessionStore(
      {
        getItem: (key) => data.get(key) ?? null,
        setItem: (key, value) => {
          data.set(key, value);
        },
      },
      session.languageId,
    );
    store.save(session);
    expect(store.load()).toEqual(session);
    let history: Snapshot[] = [];
    for (let index = 0; index < 23; index++)
      history = appendCheckpoint(history, {
        ...session,
        workspace: new Workspace({ files: session.workspace.files.map((file) => ({ ...file, text: String(index) })) }),
      });
    store.saveHistory(history);
    expect(store.history()).toHaveLength(20);
    expect(store.history()[0]?.session.workspace.files[0]?.text).toBe('22');
    expect(store.history().at(-1)?.session.workspace.files[0]?.text).toBe('3');
    expect(new Set(store.history().map((snapshot) => snapshot.id)).size).toBe(20);
  });
  it('preserves storage errors so callers can report unsaved work', () => {
    const store = new SessionStore(
      {
        getItem: () => null,
        setItem: () => {
          throw new DOMException('Quota exceeded');
        },
      },
      session.languageId,
    );
    expect(() => store.save(session)).toThrow('Quota exceeded');
  });
});

it('migrates version 2 flat files and emits only version 3 workspaces', () => {
  const flat = {
    format: 'language-playground',
    version: 2,
    session: {
      languageId: 'test-language',
      files: [{ id: 'same-id', fileName: 'source.test', text: 'saved source' }],
      activeFileId: 'same-id',
      entryFileId: 'same-id',
      entryPoint: 'start',
      stdin: '',
    },
  };
  const restored = importSession(JSON.stringify(flat));
  expect(restored.workspace.files).toEqual([{ id: 'same-id', path: 'source.test', text: 'saved source' }]);
  expect(restored.openFileIds).toEqual(['same-id']);
  expect(exportSession(restored)).toContain('"version": 3');
  expect(exportSession(restored)).not.toContain('fileName');
  const closed = { ...restored, openFileIds: [], activeFileId: null };
  expect(importSession(exportSession(closed))).toEqual(closed);
});
