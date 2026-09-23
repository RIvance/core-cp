import * as Lsp from 'vscode-languageserver-protocol';
import type {
  CodeAction,
  CodeActionContext,
  InlayHint,
  LanguageCapabilities,
  LanguageCommand,
  SourceDocument,
} from '../../core/contracts';
import type { MessageConnection } from '../../transport/rpc';
import { diagnostic, protocolDiagnostic } from './diagnostics';
import { decodeSemanticTokens, documentRange } from './semantic-tokens';
import { convertWorkspaceEdit } from './workspace-edits';

/** Optional LSP features share the document lifecycle, but own their protocol translation and refresh events. */
export class LspSemanticFeatures {
  private readonly hintListeners = new Set<() => void>();
  private readonly tokenListeners = new Set<() => void>();
  constructor(
    private readonly connection: MessageConnection,
    private readonly synchronize: (document: SourceDocument) => void,
    private readonly command: (command: Lsp.Command) => LanguageCommand,
  ) {}

  capabilities(
    options: Lsp.ServerCapabilities,
  ): Pick<LanguageCapabilities, 'inlayHints' | 'semanticTokens' | 'codeActions'> {
    const inlay = options.inlayHintProvider;
    const tokens = options.semanticTokensProvider;
    const actions = options.codeActionProvider;
    return {
      inlayHints: inlay
        ? {
            provide: async (document, range, signal) => {
              const result = await this.request<Lsp.InlayHint[] | null>(
                'textDocument/inlayHint',
                document,
                { range },
                signal,
              );
              return (result ?? []).map((value) =>
                this.hint(value, typeof inlay === 'object' && !!inlay.resolveProvider),
              );
            },
            onDidChange: (listener) => {
              this.hintListeners.add(listener);
              return () => this.hintListeners.delete(listener);
            },
          }
        : undefined,
      semanticTokens:
        tokens && (tokens.full || tokens.range)
          ? {
              legend: tokens.legend,
              provide: async (document, signal) =>
                decodeSemanticTokens(
                  await this.request<unknown>(
                    tokens.full ? 'textDocument/semanticTokens/full' : 'textDocument/semanticTokens/range',
                    document,
                    tokens.full ? {} : { range: documentRange(document) },
                    signal,
                  ),
                  tokens.legend,
                  document,
                ),
              onDidChange: (listener) => {
                this.tokenListeners.add(listener);
                return () => this.tokenListeners.delete(listener);
              },
            }
          : undefined,
      codeActions: actions
        ? {
            kinds: typeof actions === 'object' ? actions.codeActionKinds : undefined,
            provide: async (document, range, context, signal) => {
              const result = await this.request<(Lsp.CodeAction | Lsp.Command)[] | null>(
                'textDocument/codeAction',
                document,
                { range, context: this.actionContext(context, document.uri) },
                signal,
              );
              return (result ?? []).map((value) =>
                this.action(value, document.uri, typeof actions === 'object' && !!actions.resolveProvider),
              );
            },
          }
        : undefined,
    };
  }

  /** Re-query after asynchronous analysis has caught up with an edit. */
  analysisChanged(): void {
    for (const listener of this.hintListeners) listener();
    for (const listener of this.tokenListeners) listener();
  }

  private request<T>(method: string, document: SourceDocument, params: object, signal: AbortSignal): Promise<T> {
    this.synchronize(document);
    return this.connection.request(method, { textDocument: { uri: document.uri }, ...params }, signal);
  }

  private hint(value: Lsp.InlayHint, resolve: boolean): InlayHint {
    const tooltip = (text: string | Lsp.MarkupContent | undefined) => (typeof text === 'string' ? text : text?.value);
    return {
      position: value.position,
      label:
        typeof value.label === 'string'
          ? value.label
          : value.label.map((part) => ({
              value: part.value,
              tooltip: tooltip(part.tooltip),
              location: part.location,
              command: part.command && this.command(part.command),
            })),
      kind:
        value.kind === Lsp.InlayHintKind.Type
          ? 'type'
          : value.kind === Lsp.InlayHintKind.Parameter
            ? 'parameter'
            : undefined,
      tooltip: tooltip(value.tooltip),
      paddingLeft: value.paddingLeft,
      paddingRight: value.paddingRight,
      textEdits: value.textEdits?.map((edit) => ({ range: edit.range, text: edit.newText })),
      resolve: resolve
        ? async (signal) =>
            this.hint(await this.connection.request<Lsp.InlayHint>('inlayHint/resolve', value, signal), false)
        : undefined,
    };
  }

  private actionContext(context: CodeActionContext, uri: string): Lsp.CodeActionContext {
    return {
      diagnostics: context.diagnostics.flatMap((value) => {
        const projected = protocolDiagnostic(value, uri);
        return projected ? [projected] : [];
      }),
      only: context.only ? [...context.only] : undefined,
      triggerKind:
        context.trigger === 'automatic' ? Lsp.CodeActionTriggerKind.Automatic : Lsp.CodeActionTriggerKind.Invoked,
    };
  }

  private action(value: Lsp.CodeAction | Lsp.Command, uri: string, resolve: boolean): CodeAction {
    if (Lsp.Command.is(value)) return { title: value.title, command: this.command(value) };
    const edit = value.edit && convertWorkspaceEdit(value.edit);
    return {
      title: value.title,
      kind: value.kind,
      isPreferred: value.isPreferred,
      diagnostics: value.diagnostics?.map((item) => diagnostic(item, uri)),
      disabled: value.disabled?.reason ?? (edit?.kind === 'unsupported' ? edit.reason : undefined),
      edit: edit?.kind === 'supported' ? edit.changes : undefined,
      command: value.command && this.command(value.command),
      resolve: resolve
        ? async (signal) =>
            this.action(await this.connection.request<Lsp.CodeAction>('codeAction/resolve', value, signal), uri, false)
        : undefined,
    };
  }

  dispose(): void {
    this.hintListeners.clear();
    this.tokenListeners.clear();
  }
}
