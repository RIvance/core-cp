import {
  createEmbeddedPlayground,
  type EmbeddedPlaygroundHandle,
  type EmbeddedPlaygroundOptions,
} from '@language-playground/ide/embedded';
import { defaultPlaygroundSettings } from '@language-playground/ide/core';
import { monaco, type EditorLanguage } from '@language-playground/ide/editor';
import { setTheme } from '@language-playground/ide/themes';
import { WorkerRuntime } from '@language-playground/ide/transport';
import type { LanguagePlugin, SourceDocument } from '@language-playground/ide/api';
import '@language-playground/ide/styles.css';

const instances = new Map<string, EmbeddedPlaygroundHandle>();
const errors: string[] = [];
const serviceRoots: string[] = [];
const requests: { uri: string; languageId: string }[] = [];
let installations = 0;
let serviceDisposals = 0;
const plugin: LanguagePlugin = {
  definition: {
    id: 'text',
    name: 'Text',
    extension: '.txt',
    defaultFilePath: 'main.txt',
    defaultEntryPoint: '',
    examples: [],
  },
  createRuntime: () =>
    new WorkerRuntime(() => new Worker(new URL('./runtime.worker.ts', import.meta.url), { type: 'module' })),
  createLanguageService({ workspaceUri }) {
    serviceRoots.push(workspaceUri);
    const documents = new Map<string, SourceDocument>();
    return {
      capabilities: {
        completions: {
          triggerCharacters: [],
          provide(document) {
            requests.push({ uri: document.uri, languageId: document.languageId });
            return Promise.resolve({
              isIncomplete: false,
              items: [{ label: 'completion', insertText: 'completion', kind: 'keyword' }],
            });
          },
        },
        rename(_document, _position, name) {
          // Include the writable target first: a rejected transaction must leave that target unchanged too.
          return Promise.resolve(
            [...documents.values()]
              .sort((a, b) => Number(b.uri.endsWith('main.txt')) - Number(a.uri.endsWith('main.txt')))
              .map((document) => ({
                uri: document.uri,
                version: document.version,
                edits: [{ range: { start: { line: 0, character: 0 }, end: { line: 0, character: 4 } }, text: name }],
              })),
          );
        },
      },
      start: () => Promise.resolve(),
      synchronize(document) {
        documents.set(document.uri, document);
      },
      close(uri) {
        documents.delete(uri);
      },
      onDiagnostics: () => () => {},
      onLog: () => () => {},
      onFailure: () => () => {},
      dispose() {
        serviceDisposals += 1;
        documents.clear();
      },
    };
  },
};
const language: EditorLanguage = {
  install(api, definition) {
    api.languages.register({ id: definition.id });
    const tokens = api.languages.setMonarchTokensProvider(definition.id, {
      tokenizer: { root: [[/[a-z]+/, 'keyword']] },
    });
    installations += 1;
    return {
      dispose() {
        tokens.dispose();
        installations -= 1;
      },
    };
  },
};
const harness = {
  mount(id: string, options: Omit<EmbeddedPlaygroundOptions, 'plugin' | 'editor'> = {}) {
    const container = document.getElementById(id);
    if (!container) throw new Error(`Missing container: ${id}`);
    const instance = createEmbeddedPlayground(container, {
      plugin,
      editor: language,
      ...options,
      onError: (error) => errors.push(String(error)),
    });
    instances.set(id, instance);
    return instance.ready;
  },
  get(id: string): EmbeddedPlaygroundHandle {
    const instance = instances.get(id);
    if (!instance) throw new Error(`Missing instance: ${id}`);
    return instance;
  },
  get modelCount() {
    return monaco.editor.getModels().length;
  },
  get installations() {
    return installations;
  },
  get serviceDisposals() {
    return serviceDisposals;
  },
  errors,
  requests,
  serviceRoots,
  defaults: defaultPlaygroundSettings,
  setTheme,
};
window.embeddedHarness = harness;
declare global {
  interface Window {
    embeddedHarness: typeof harness;
  }
}
