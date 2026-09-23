import { currentDiagnostics } from '../core/diagnostics';
import { monaco, installThemes, type EditorLanguage } from './monaco';
import { attachLanguageService, editorRange } from './language-bridge';
import { getTheme, onDidChangeTheme } from '../appearance/page-theme';
import type { Diagnostic, LanguageService, Location } from '../core/contracts';
import type { PlaygroundController, PlaygroundState } from '../core/controller';

interface OpenDocument {
  readonly id: string;
  readonly model: monaco.editor.ITextModel;
  readonly changes: monaco.IDisposable;
  viewState: monaco.editor.ICodeEditorViewState | null;
  readOnly: boolean;
  service?: LanguageService;
  registration?: monaco.IDisposable;
}
/** Shared editor operations. Locations use the language contract's zero-based coordinates. */
export interface EditorHandle {
  /**
   * Await an action returned by Monaco's getAction; unknown or unsupported actions reject.
   * Monaco also registers commands outside that action API. Dispatch those through getEditor().trigger,
   * which returns immediately and leaves asynchronous completion and error reporting to Monaco.
   */
  action(identifier: string): Promise<void>;
  /** Select and reveal a workspace location; an external URI throws RangeError. */
  reveal(location: Location): void;
  /** Give keyboard focus to the editor. */
  focus(): void;
  /** Native editor, or null while loading. The caller does not own its disposal or models. */
  getEditor(): monaco.editor.IStandaloneCodeEditor | null;
}

/** @internal Owns all workspace models, including inactive tabs' undo stacks, view states and service registrations. */
export class EditorWorkspace implements EditorHandle {
  private readonly editor: monaco.editor.IStandaloneCodeEditor;
  private readonly documents = new Map<string, OpenDocument>();
  private readonly languageId = `playground-${crypto.randomUUID()}`;
  private readonly releases: (() => void)[] = [];
  private disposed = false;
  private applyingSnapshot = false;
  private activeId: string | null = null;
  constructor(
    container: HTMLElement,
    language: EditorLanguage,
    private readonly controller: PlaygroundController,
    private readonly position: (line: number, column: number, selected: number) => void,
    private readonly reportError: (error: unknown) => void,
  ) {
    try {
      const applyTheme = () => {
        const theme = getTheme();
        installThemes([theme]);
        monaco.editor.setTheme(`playground-${theme.id}`);
      };
      applyTheme();
      this.releases.push(onDidChangeTheme(applyTheme));
      const installation = language.install(monaco, { definition: controller.plugin.definition, id: this.languageId });
      this.releases.push(() => installation.dispose());
      this.editor = monaco.editor.create(container, {
        model: null,
        automaticLayout: true,
        fontFamily: '"JetBrains Mono", "SFMono-Regular", Consolas, monospace',
        fontSize: controller.getSnapshot().settings.fontSize,
        lineHeight: 25,
        padding: { top: 22, bottom: 22 },
        scrollBeyondLastLine: false,
        smoothScrolling: true,
        cursorBlinking: 'smooth',
        renderLineHighlight: 'all',
        roundedSelection: true,
        glyphMargin: true,
        folding: true,
        bracketPairColorization: { enabled: true },
        guides: { bracketPairs: true, indentation: true },
        fixedOverflowWidgets: true,
        contextmenu: true,
        links: false,
        accessibilitySupport: 'auto',
        ariaLabel: 'Source code editor',
        wordBasedSuggestions: 'currentDocument',
        quickSuggestions: { other: true, comments: false, strings: false },
        suggest: { preview: true, showWords: true },
      });
      this.releases.push(() => this.editor.dispose());
      const selection = this.editor.onDidChangeCursorSelection((event) => {
        this.position(
          event.selection.positionLineNumber,
          event.selection.positionColumn,
          this.editor.getModel()?.getValueLengthInRange(event.selection) ?? 0,
        );
      });
      this.releases.push(() => selection.dispose());
      const opener = monaco.editor.registerEditorOpener({
        openCodeEditor: (_source, resource, selection) => {
          const target = [...this.documents.values()].find(
            (document) => document.model.uri.toString() === resource.toString(),
          );
          if (!target) return false;
          this.activate(target.id);
          this.controller.selectFile(target.id);
          if (selection) {
            const position =
              'startLineNumber' in selection
                ? { lineNumber: selection.startLineNumber, column: selection.startColumn }
                : selection;
            this.editor.setPosition(position);
            this.editor.revealPositionInCenter(position);
          }
          this.editor.focus();
          return true;
        },
      });
      this.releases.push(() => opener.dispose());
      this.releases.push(controller.subscribe(() => this.update(controller.getSnapshot())));
      this.update(controller.getSnapshot());
    } catch (error) {
      try {
        this.dispose();
      } catch (cleanupError) {
        throw new AggregateError([error, cleanupError], 'Editor setup and cleanup failed.', { cause: cleanupError });
      }
      throw error;
    }
  }
  private activate(id: string | null): void {
    if (this.activeId === id) return;
    const previous = this.activeId ? this.documents.get(this.activeId) : undefined;
    if (previous) previous.viewState = this.editor.saveViewState();
    this.activeId = id;
    const next = id ? this.documents.get(id) : undefined;
    this.editor.setModel(next?.model ?? null);
    this.editor.updateOptions({ readOnly: id !== null && this.controller.isReadOnly(id) });
    if (next?.viewState) this.editor.restoreViewState(next.viewState);
    const cursor = this.editor.getPosition();
    this.position(cursor?.lineNumber ?? 1, cursor?.column ?? 1, 0);
  }
  private update(state: PlaygroundState): void {
    const files = new Map(state.documents.map((document) => [document.fileId, document]));
    // Access policy can invalidate a cached cross-file action or an inlay hint's edits in any model.
    const policyChanged = [...this.documents.values()].some((document) => {
      const file = files.get(document.id);
      return file !== undefined && file.readOnly !== document.readOnly;
    });
    for (const [id, document] of this.documents) {
      const file = files.get(id);
      if (!file || file.source.uri !== document.model.uri.toString()) {
        if (id === this.activeId) this.activate(null);
        document.registration?.dispose();
        document.changes.dispose();
        document.model.dispose();
        this.documents.delete(id);
      }
    }
    const service = state.service.kind === 'ready' ? state.service.service : undefined;
    const diagnostics = currentDiagnostics(state);
    for (const file of state.documents) {
      let document = this.documents.get(file.fileId);
      if (!document) {
        const model = monaco.editor.createModel(file.source.text, this.languageId, monaco.Uri.parse(file.source.uri));
        document = {
          id: file.fileId,
          model,
          viewState: null,
          readOnly: file.readOnly,
          changes: model.onDidChangeContent(() => {
            if (this.applyingSnapshot) return;
            try {
              this.controller.edit(file.fileId, model.getValue());
            } catch (error) {
              // Monaco reports edits after applying them. Reset rejected contents to the authoritative snapshot;
              // clearing this model's undo stack also prevents replaying the invalid edit through redo.
              model.setValue(this.controller.document(file.fileId).text);
              this.reportError(error);
            }
          }),
        };
        this.documents.set(file.fileId, document);
      } else if (document.model.getValue() !== file.source.text) {
        this.applyingSnapshot = true;
        try {
          document.model.pushStackElement();
          document.model.pushEditOperations(
            [],
            [{ range: document.model.getFullModelRange(), text: file.source.text }],
            () => null,
          );
          document.model.pushStackElement();
        } finally {
          this.applyingSnapshot = false;
        }
      }
      document.model.updateOptions({ tabSize: state.settings.tabSize, insertSpaces: true });
      document.readOnly = file.readOnly;
      if (document.service !== service || policyChanged) {
        document.registration?.dispose();
        document.service = service;
        document.registration = service
          ? attachLanguageService(
              document.model,
              service,
              () => {
                this.controller.synchronizeDocuments();
                return this.controller.document(file.fileId);
              },
              this.reportError,
              () => this.controller.getSnapshot().workspaceVersion,
              {
                model: (uri) => [...this.documents.values()].find((item) => item.model.uri.toString() === uri)?.model,
                document: (uri) =>
                  this.controller.getSnapshot().documents.find((item) => item.source.uri === uri)?.source,
                canEdit: (uri) => {
                  const target = this.controller.getSnapshot().documents.find((item) => item.source.uri === uri);
                  return !!target && !target.readOnly;
                },
                diagnostics: (uri) =>
                  currentDiagnostics(this.controller.getSnapshot()).filter((item) => item.location?.uri === uri),
              },
            )
          : undefined;
      }
      this.mark(document.model, diagnostics);
    }
    this.activate(state.session.activeFileId);
    this.editor.updateOptions({
      readOnly: state.session.activeFileId !== null && this.controller.isReadOnly(state.session.activeFileId),
      inlayHints: { enabled: state.settings.inlayHints ? 'on' : 'off' },
      'semanticHighlighting.enabled': state.settings.semanticHighlighting,
      fontSize: state.settings.fontSize,
      wordWrap: state.settings.wordWrap ? 'on' : 'off',
      minimap: { enabled: state.settings.minimap },
      lineNumbers: state.settings.lineNumbers ? 'on' : 'off',
    });
  }
  private mark(model: monaco.editor.ITextModel, diagnostics: readonly Diagnostic[]): void {
    const severity = {
      error: monaco.MarkerSeverity.Error,
      warning: monaco.MarkerSeverity.Warning,
      info: monaco.MarkerSeverity.Info,
      hint: monaco.MarkerSeverity.Hint,
    };
    monaco.editor.setModelMarkers(
      model,
      'playground',
      diagnostics.flatMap((diagnostic) =>
        diagnostic.location?.uri === model.uri.toString()
          ? [
              {
                ...editorRange(diagnostic.location.range),
                message: diagnostic.message,
                severity: severity[diagnostic.severity],
                source: diagnostic.source,
                code: diagnostic.code === undefined ? undefined : String(diagnostic.code),
              },
            ]
          : [],
      ),
    );
  }
  async action(identifier: string): Promise<void> {
    const action = this.editor.getAction(identifier);
    if (!action?.isSupported()) throw new Error('This editor action is unavailable for the current language.');
    this.editor.focus();
    await action.run();
  }
  /** Select and reveal a workspace location; an external URI throws RangeError. */
  reveal(location: Location): void {
    const document = [...this.documents.values()].find((document) => document.model.uri.toString() === location.uri);
    if (!document) {
      throw new RangeError('This location is outside the current workspace.');
    }
    this.activate(document.id);
    this.controller.selectFile(document.id);
    const range = editorRange(location.range);
    this.editor.setPosition({ lineNumber: range.startLineNumber, column: range.startColumn });
    this.editor.revealRangeInCenter(range);
    this.editor.focus();
  }
  getEditor(): monaco.editor.IStandaloneCodeEditor {
    return this.editor;
  }
  focus(): void {
    this.editor.focus();
  }
  dispose(): void {
    if (this.disposed) return;
    this.disposed = true;
    const releases = this.releases.splice(0).reverse();
    for (const document of this.documents.values())
      releases.push(
        () => document.registration?.dispose(),
        () => document.changes.dispose(),
        () => document.model.dispose(),
      );
    this.documents.clear();
    const errors: unknown[] = [];
    for (const release of releases) {
      try {
        release();
      } catch (error) {
        errors.push(error);
      }
    }
    if (errors.length) throw new AggregateError(errors, 'Could not release all editor resources.');
  }
}
