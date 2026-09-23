import type { CodeAction, Diagnostic, InlayHint, LanguageService, SourceDocument } from '../../../src/core/contracts';
import { attachLanguageService } from '../../../src/editor/language-bridge';
import { installThemes, monaco } from '../../../src/editor/monaco';
import { projectWorkspaceEdits } from '../../../src/editor/workspace-edits';

// A test-only provider exercises the public editor contract without requiring a particular compiler.
const initialSource = 'let answer = 41;\nanswer';
const uri = 'file:///contracts/example.fixture';
monaco.languages.register({ id: 'contract-fixture' });
installThemes();
const model = monaco.editor.createModel(initialSource, 'contract-fixture', monaco.Uri.parse(uri));
let version = 1;
model.onDidChangeContent(() => {
  version += 1;
});
const source = (): SourceDocument => ({ uri, languageId: model.getLanguageId(), text: model.getValue(), version });
const literalRange = { start: { line: 0, character: 13 }, end: { line: 0, character: 15 } };
const diagnostic: Diagnostic = {
  message: 'Use the checked value',
  severity: 'warning',
  code: 41,
  data: { literal: 1 },
  location: { uri, range: literalRange },
};
const context = {
  canEdit: () => true,
  model: (value: string) => (value === uri ? model : undefined),
  document: (value: string) => (value === uri ? source() : undefined),
  diagnostics: () => [diagnostic],
};
const hintListeners = new Set<() => void>();
const tokenListeners = new Set<() => void>();
const errors: string[] = [];
let hintLabel = ': Int';
let tokenType = 'function';
let releaseHints: (() => void) | undefined;
let holdHints = false;
let cancelled = 0;
let resolvedActions = 0;
let receivedDiagnostics: readonly Diagnostic[] = [];
const service: LanguageService = {
  capabilities: {
    inlayHints: {
      async provide(document, _range, signal) {
        const label = hintLabel;
        if (holdHints) {
          await new Promise<void>((resolve) => {
            releaseHints = resolve;
          });
          if (signal.aborted) cancelled += 1;
        }
        if (!document.text.startsWith('let answer')) return [];
        const hint: InlayHint = { position: { line: 0, character: 10 }, label, kind: 'type', paddingLeft: true };
        return [hint];
      },
      onDidChange(listener) {
        hintListeners.add(listener);
        return () => hintListeners.delete(listener);
      },
    },
    semanticTokens: {
      legend: { tokenTypes: ['function', 'type'], tokenModifiers: [] },
      provide(document) {
        return Promise.resolve(
          document.text.startsWith('let answer')
            ? [
                {
                  range: { start: { line: 0, character: 4 }, end: { line: 0, character: 10 } },
                  type: tokenType,
                  modifiers: [],
                },
              ]
            : [],
        );
      },
      onDidChange(listener) {
        tokenListeners.add(listener);
        return () => tokenListeners.delete(listener);
      },
    },
    codeActions: {
      kinds: ['quickfix', 'refactor.rewrite'],
      provide(document, _range, actionContext) {
        receivedDiagnostics = actionContext.diagnostics;
        const actions: CodeAction[] = [
          {
            title: 'Use 42',
            kind: 'quickfix',
            isPreferred: true,
            diagnostics: [diagnostic],
            resolve() {
              resolvedActions += 1;
              return Promise.resolve({
                title: 'Use 42',
                kind: 'quickfix',
                edit: [
                  {
                    uri,
                    version: document.version,
                    edits: [{ range: literalRange, text: '42' }],
                  },
                ],
              });
            },
          },
          {
            title: 'Edit unavailable file',
            kind: 'quickfix',
            edit: [
              {
                uri: 'file:///outside.fixture',
                edits: [{ range: literalRange, text: '42' }],
              },
            ],
          },
        ];
        return Promise.resolve(actions);
      },
    },
  },
  start: () => Promise.resolve(),
  synchronize() {},
  close() {},
  onDiagnostics: () => () => {},
  onLog: () => () => {},
  onFailure: () => () => {},
  dispose() {},
};
const registration = attachLanguageService(
  model,
  service,
  source,
  (error) => errors.push(String(error)),
  () => version,
  context,
);
const editor = monaco.editor.create(document.getElementById('editor')!, {
  model,
  automaticLayout: true,
  theme: 'playground-dark',
  fontSize: 18,
  minimap: { enabled: false },
  'semanticHighlighting.enabled': true,
  inlayHints: { enabled: 'on' },
});
monaco.editor.setModelMarkers(model, 'fixture', [
  {
    startLineNumber: 1,
    startColumn: 14,
    endLineNumber: 1,
    endColumn: 16,
    severity: monaco.MarkerSeverity.Warning,
    message: diagnostic.message,
    code: String(diagnostic.code),
  },
]);

const harness = {
  editor,
  model,
  errors,
  get resolvedActions() {
    return resolvedActions;
  },
  get diagnostics() {
    return receivedDiagnostics;
  },
  get cancelled() {
    return cancelled;
  },
  get hintsPending() {
    return releaseHints !== undefined;
  },
  refresh(label: string, type: string) {
    hintLabel = label;
    tokenType = type;
    for (const listener of hintListeners) listener();
    for (const listener of tokenListeners) listener();
  },
  holdHints() {
    holdHints = true;
  },
  releaseHints() {
    holdHints = false;
    releaseHints?.();
    releaseHints = undefined;
  },
  validateEdits: (changes: Parameters<typeof projectWorkspaceEdits>[0]) => projectWorkspaceEdits(changes, context).kind,
  dispose() {
    registration.dispose();
    editor.dispose();
    model.dispose();
  },
};
declare global {
  interface Window {
    editorHarness: typeof harness;
  }
}
window.editorHarness = harness;
