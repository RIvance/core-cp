import { mountPlayground, type PlaygroundHandle, type PlaygroundOptions } from '@language-playground/ide';
import type { LanguagePlugin, EditorLanguage } from '@language-playground/ide/api';
import { monaco } from '@language-playground/ide/editor';
import { loadExampleCatalog } from '@language-playground/ide/examples';
import { LspLanguageService } from '@language-playground/ide/lsp';
import { WorkerRuntime } from '@language-playground/ide/transport';
import '@language-playground/ide/styles.css';

const container = document.getElementById('host');
if (!container) throw new Error('Missing consumer container.');
let mounted: PlaygroundHandle | undefined;
let installations = 0;
let serviceDisposals = 0;
const plugin: LanguagePlugin = {
  definition: {
    id: 'package-consumer',
    name: 'Text consumer',
    defaultFilePath: 'source.txt',
    extension: '.txt',
    defaultEntryPoint: '',
    examples: loadExampleCatalog({
      'greeting/example.json': JSON.stringify({
        id: 'greeting',
        title: 'Greeting',
        description: 'Plain text',
        entryPath: 'source.txt',
      }),
      'greeting/source.txt': 'independent package',
    }),
  },
  createRuntime: () =>
    new WorkerRuntime(() => new Worker(new URL('./runtime.worker.ts', import.meta.url), { type: 'module' })),
};
const editor: EditorLanguage = {
  install(api, language) {
    if (!api.languages.getLanguages().some((candidate) => candidate.id === language.id))
      api.languages.register({ id: language.id });
    installations += 1;
    const registration = api.languages.setLanguageConfiguration(language.id, {});
    const tokens = api.languages.setMonarchTokensProvider(language.id, {
      tokenizer: { root: [[/[a-z]+/, 'keyword']] },
    });
    return {
      dispose() {
        registration.dispose();
        tokens.dispose();
        installations -= 1;
      },
    };
  },
};
const harness = {
  mount(
    withService = false,
    failingStorage = false,
    appearance: Pick<PlaygroundOptions, 'themes' | 'defaultTheme' | 'title' | 'documentTitle'> = {},
  ) {
    mounted = mountPlayground(container, {
      ...appearance,
      plugin: withService
        ? {
            ...plugin,
            createLanguageService: () => ({
              capabilities: {
                definitions: (document) =>
                  Promise.resolve([
                    {
                      uri: document.uri,
                      range: { start: { line: 0, character: 0 }, end: { line: 0, character: 5 } },
                    },
                  ]),
              },
              start: () => Promise.resolve(),
              synchronize: () => undefined,
              close: () => undefined,
              onDiagnostics: () => () => undefined,
              onLog: () => () => undefined,
              onFailure: () => () => undefined,
              dispose: () => {
                serviceDisposals += 1;
              },
            }),
          }
        : plugin,
      editor,
      storage: failingStorage
        ? {
            getItem() {
              throw new Error('Storage disabled by consumer');
            },
            setItem() {
              throw new Error('Storage disabled by consumer');
            },
          }
        : undefined,
    });
  },
  dispose() {
    mounted?.dispose();
  },
  get installations() {
    return installations;
  },
  get serviceDisposals() {
    return serviceDisposals;
  },
  get modelCount() {
    return monaco.editor.getModels().length;
  },
  get modelUris() {
    return monaco.editor.getModels().map((model) => model.uri.toString());
  },
  replaceSource(uri: string, text: string) {
    const model = monaco.editor.getModel(monaco.Uri.parse(uri));
    if (!model) throw new Error(`Missing editor model: ${uri}`);
    model.pushStackElement();
    model.pushEditOperations([], [{ range: model.getFullModelRange(), text }], () => null);
    model.pushStackElement();
  },
  get lspExported() {
    return typeof LspLanguageService === 'function';
  },
  async computeDiff() {
    const target = document.getElementById('diff');
    if (!target) throw new Error('Missing diff container.');
    const original = monaco.editor.createModel('before');
    const modified = monaco.editor.createModel('after');
    const diff = monaco.editor.createDiffEditor(target);
    try {
      await new Promise<void>((resolve) => {
        const listener = diff.onDidUpdateDiff(() => {
          listener.dispose();
          resolve();
        });
        diff.setModel({ original, modified });
      });
      return diff.getLineChanges()?.length;
    } finally {
      diff.dispose();
      original.dispose();
      modified.dispose();
    }
  },
};
window.consumerHarness = harness;
declare global {
  interface Window {
    consumerHarness: typeof harness;
  }
}
