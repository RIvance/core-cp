import { monaco } from './monaco';
import type { Completion, DocumentSymbol, LanguageService, Position, Range, SourceDocument } from '../core/contracts';
import { LanguageRequests } from './language-requests';
import { attachSemanticFeatures, type LanguageWorkspaceContext } from './semantic-features';
import { projectWorkspaceEdits } from './workspace-edits';

export function editorRange(range: Range): monaco.Range {
  return new monaco.Range(range.start.line + 1, range.start.character + 1, range.end.line + 1, range.end.character + 1);
}
function sourcePosition(position: monaco.Position): Position {
  return { line: position.lineNumber - 1, character: position.column - 1 };
}
function sourceSymbol(symbol: DocumentSymbol): monaco.languages.DocumentSymbol {
  const kinds = monaco.languages.SymbolKind;
  const kind = {
    module: kinds.Module,
    type: kinds.Class,
    function: kinds.Function,
    variable: kinds.Variable,
    member: kinds.Method,
  }[symbol.kind];
  return {
    name: symbol.name,
    detail: symbol.detail ?? '',
    kind,
    tags: [],
    range: editorRange(symbol.location.range),
    selectionRange: editorRange(symbol.location.range),
    children: symbol.children.map(sourceSymbol),
  };
}

/**
 * Register a started service's capabilities for one model and return their shared disposable.
 * currentDocument must flush pending synchronization before returning the model's source snapshot.
 * workspaceVersion must change after edits to any document that can affect this model's analysis.
 * Late results are discarded after a revision change or disposal. reportError receives current, non-cancelled failures.
 */
export function attachLanguageService(
  model: monaco.editor.ITextModel,
  service: LanguageService,
  currentDocument: () => SourceDocument,
  reportError: (error: unknown) => void,
  workspaceVersion: () => number,
  workspace: LanguageWorkspaceContext,
): monaco.IDisposable {
  const registrations: monaco.IDisposable[] = [];
  const capabilities = service.capabilities;
  const selector = { language: model.getLanguageId(), scheme: model.uri.scheme, pattern: model.uri.path };
  const requests = new LanguageRequests(model, currentDocument, workspaceVersion, reportError);
  const request = requests.run.bind(requests);
  registrations.push(attachSemanticFeatures(model, capabilities, requests, workspace));

  if (capabilities.hover) {
    const hover = capabilities.hover;
    registrations.push(
      monaco.languages.registerHoverProvider(selector, {
        async provideHover(target, position, token) {
          const value = await request(target, token, (document, signal) =>
            hover(document, sourcePosition(position), signal),
          );
          return value
            ? {
                contents: [{ value: `\`\`\`\n${value.text}\n\`\`\``, isTrusted: false }],
                range: value.range && editorRange(value.range),
              }
            : undefined;
        },
      }),
    );
  }
  if (capabilities.definitions) {
    const definitions = capabilities.definitions;
    registrations.push(
      monaco.languages.registerDefinitionProvider(selector, {
        async provideDefinition(target, position, token) {
          const locations = await request(target, token, (document, signal) =>
            definitions(document, sourcePosition(position), signal),
          );
          return locations?.map((location) => ({
            uri: monaco.Uri.parse(location.uri),
            range: editorRange(location.range),
          }));
        },
      }),
    );
  }
  if (capabilities.references) {
    const references = capabilities.references;
    registrations.push(
      monaco.languages.registerReferenceProvider(selector, {
        async provideReferences(target, position, _context, token) {
          const locations = await request(target, token, (document, signal) =>
            references(document, sourcePosition(position), signal),
          );
          return locations?.map((location) => ({
            uri: monaco.Uri.parse(location.uri),
            range: editorRange(location.range),
          }));
        },
      }),
    );
  }
  if (capabilities.symbols) {
    const symbols = capabilities.symbols;
    registrations.push(
      monaco.languages.registerDocumentSymbolProvider(selector, {
        async provideDocumentSymbols(target, token) {
          return (await request(target, token, symbols))?.map(sourceSymbol);
        },
      }),
    );
  }
  if (capabilities.completions) {
    const completions = capabilities.completions;
    const sourceItems = new WeakMap<monaco.languages.CompletionItem, Completion>();
    const acceptCommand = `playground.completion.${crypto.randomUUID()}`;
    registrations.push(
      monaco.editor.registerCommand(acceptCommand, (_accessor: unknown, accept: () => Promise<void>) => {
        void accept().catch(reportError);
      }),
    );
    const project = (
      value: Completion,
      fallbackRange: monaco.languages.CompletionItem['range'],
    ): monaco.languages.CompletionItem => {
      const kinds = monaco.languages.CompletionItemKind;
      const range = !value.range
        ? fallbackRange
        : 'insert' in value.range
          ? {
              insert: editorRange(value.range.insert),
              replace: editorRange(value.range.replace),
            }
          : editorRange(value.range);
      const item: monaco.languages.CompletionItem = {
        label: value.label,
        insertText: value.insertText,
        detail: value.detail,
        documentation: value.documentation,
        sortText: value.sortText,
        filterText: value.filterText,
        commitCharacters: value.commitCharacters,
        additionalTextEdits: value.additionalEdits?.map((edit) => ({
          range: editorRange(edit.range),
          text: edit.text,
        })),
        range,
        kind: {
          keyword: kinds.Keyword,
          function: kinds.Function,
          type: kinds.Class,
          variable: kinds.Variable,
          field: kinds.Field,
          constructor: kinds.Constructor,
          module: kinds.Module,
          snippet: kinds.Snippet,
        }[value.kind],
        insertTextRules: value.snippet ? monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet : undefined,
        command: value.onAccepted
          ? { id: acceptCommand, title: value.label, arguments: [value.onAccepted] }
          : undefined,
      };
      sourceItems.set(item, value);
      return item;
    };
    registrations.push(
      monaco.languages.registerCompletionItemProvider(selector, {
        triggerCharacters: [...completions.triggerCharacters],
        async provideCompletionItems(target, position, _context, token) {
          const batch = await request(target, token, (document, signal) =>
            completions.provide(document, sourcePosition(position), signal),
          );
          const word = target.getWordUntilPosition(position);
          const fallbackRange = new monaco.Range(
            position.lineNumber,
            word.startColumn,
            position.lineNumber,
            word.endColumn,
          );
          return {
            suggestions: (batch?.items ?? []).map((value) => project(value, fallbackRange)),
            incomplete: batch?.isIncomplete ?? false,
          };
        },
        async resolveCompletionItem(item, token) {
          const resolve = sourceItems.get(item)?.resolve;
          if (!resolve) return item;
          const value = await request(model, token, (_document, signal) => resolve(signal));
          return value ? project(value, item.range) : item;
        },
      }),
    );
  }

  if (capabilities.format) {
    const format = capabilities.format;
    registrations.push(
      monaco.languages.registerDocumentFormattingEditProvider(selector, {
        async provideDocumentFormattingEdits(target, options, token) {
          return (await request(target, token, (document, signal) => format(document, options, signal)))?.map(
            (edit) => ({ range: editorRange(edit.range), text: edit.text }),
          );
        },
      }),
    );
  }
  if (capabilities.rename) {
    const rename = capabilities.rename;
    registrations.push(
      monaco.languages.registerRenameProvider(selector, {
        async provideRenameEdits(target, position, name, token) {
          const changes = await request(target, token, (document, signal) =>
            rename(document, sourcePosition(position), name, signal),
          );
          const projected = projectWorkspaceEdits(changes ?? [], workspace);
          if (projected.kind === 'ready') return projected.edit;
          // Standalone Monaco sends rename rejections to its console-only notification service.
          // Route the same structured failure through the owning presentation's error channel.
          reportError(projected.error);
          return { edits: [], rejectReason: projected.error.message };
        },
      }),
    );
  }
  if (capabilities.signature) {
    const signature = capabilities.signature;
    registrations.push(
      monaco.languages.registerSignatureHelpProvider(selector, {
        signatureHelpTriggerCharacters: [...signature.triggerCharacters],
        signatureHelpRetriggerCharacters: [...signature.retriggerCharacters],
        async provideSignatureHelp(target, position, token) {
          const value = await request(target, token, (document, signal) =>
            signature.provide(document, sourcePosition(position), signal),
          );
          // Monaco uses an out-of-range index to leave all parameters unhighlighted.
          return value
            ? {
                value: {
                  ...value,
                  activeParameter: value.activeParameter ?? -1,
                  signatures: value.signatures.map((item) => ({
                    ...item,
                    activeParameter: item.activeParameter === null ? -1 : item.activeParameter,
                  })),
                },
                dispose() {},
              }
            : undefined;
        },
      }),
    );
  }
  if (capabilities.folding) {
    const folding = capabilities.folding;
    registrations.push(
      monaco.languages.registerFoldingRangeProvider(selector, {
        async provideFoldingRanges(target, _context, token) {
          return (await request(target, token, folding))?.map((range) => ({
            start: range.startLine + 1,
            end: range.endLine + 1,
          }));
        },
      }),
    );
  }
  return {
    dispose() {
      requests.dispose();
      for (const registration of registrations) registration.dispose();
    },
  };
}
