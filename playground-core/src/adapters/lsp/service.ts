import * as Lsp from 'vscode-languageserver-protocol';
import type {
  AnalysisUpdate,
  Completion,
  CompletionBatch,
  DocumentSymbol,
  Hover,
  LanguageService,
  LanguageCommand,
  Location,
  LanguageCapabilities,
  Position,
  ServiceLog,
  SignatureHelp,
  SourceDocument,
  TextEdit,
} from '../../core/contracts';
import type { MessageConnection } from '../../transport/rpc';
import { diagnostic } from './diagnostics';
import { LspSemanticFeatures } from './semantic-features';
import { convertWorkspaceEdit } from './workspace-edits';

const markup = (value: Lsp.MarkupContent | Lsp.MarkedString | undefined): string =>
  typeof value === 'string' ? value : (value?.value ?? '');

/**
 * LanguageService backed by an owned JSON-RPC connection. start negotiates UTF-16 and optional server capabilities;
 * methods exposed through capabilities translate results to the neutral API. Compiler types and semantics stay in the connected server.
 * The caller supplies the transport. dispose closes it and rejects pending requests through MessageConnection.
 */
export class LspLanguageService implements LanguageService {
  private available: Readonly<LanguageCapabilities> = {};
  /** Empty before initialization; afterward contains only negotiated server operations. */
  get capabilities(): Readonly<LanguageCapabilities> {
    return this.available;
  }
  private documents = new Map<string, SourceDocument>();
  private diagnostics = new Set<(update: AnalysisUpdate) => void>();
  private logs = new Set<(log: ServiceLog) => void>();
  private sync: Lsp.TextDocumentSyncKind = Lsp.TextDocumentSyncKind.None;
  private resolveCompletions = false;
  private commands = new Set<string>();
  private readonly semanticFeatures: LspSemanticFeatures;
  /** Take ownership of connection. workspaceUri is sent as the LSP root and initial workspace folder. */
  constructor(
    private readonly connection: MessageConnection,
    private readonly workspaceUri: string,
  ) {
    this.semanticFeatures = new LspSemanticFeatures(
      connection,
      (document) => this.synchronize(document),
      (command) => this.command(command),
    );
    connection.onNotification((method, params) => {
      if (method === 'textDocument/publishDiagnostics') {
        const update = params as Lsp.PublishDiagnosticsParams;
        for (const listener of this.diagnostics)
          listener({
            uri: update.uri,
            version: update.version,
            diagnostics: update.diagnostics.map((value) => diagnostic(value, update.uri)),
          });
        if (update.version === undefined || update.version === this.documents.get(update.uri)?.version)
          this.semanticFeatures.analysisChanged();
      }
      if (method === 'window/logMessage' || method === 'window/showMessage') {
        const log = params as Lsp.LogMessageParams;
        for (const listener of this.logs)
          listener({
            level:
              log.type === Lsp.MessageType.Error ? 'error' : log.type === Lsp.MessageType.Warning ? 'warning' : 'info',
            message: log.message,
          });
      }
    });
  }
  /** Send initialize/initialized. Reject if initialization fails or the server selects a non-UTF-16 position encoding. */
  async start(): Promise<void> {
    const result = await this.connection.request<Lsp.InitializeResult>('initialize', {
      processId: null,
      rootUri: this.workspaceUri,
      workspaceFolders: [{ uri: this.workspaceUri, name: 'Playground' }],
      clientInfo: { name: 'Language Playground', version: '1.0.0' },
      capabilities: {
        general: { positionEncodings: ['utf-16'] },
        workspace: { workspaceEdit: { documentChanges: true } },
        textDocument: {
          publishDiagnostics: { dataSupport: true, versionSupport: true },
          hover: { contentFormat: ['plaintext'] },
          documentSymbol: { hierarchicalDocumentSymbolSupport: true },
          completion: { completionItem: { snippetSupport: true } },
          inlayHint: {
            resolveSupport: {
              properties: ['tooltip', 'textEdits', 'label.tooltip', 'label.location', 'label.command'],
            },
          },
          semanticTokens: {
            requests: { full: true, range: true },
            tokenTypes: Object.values(Lsp.SemanticTokenTypes),
            tokenModifiers: Object.values(Lsp.SemanticTokenModifiers),
            formats: ['relative'],
            overlappingTokenSupport: false,
            multilineTokenSupport: false,
          },
          codeAction: {
            codeActionLiteralSupport: { codeActionKind: { valueSet: Object.values(Lsp.CodeActionKind) } },
            isPreferredSupport: true,
            disabledSupport: true,
            dataSupport: true,
            resolveSupport: { properties: ['edit', 'command'] },
          },
        },
      },
    });
    if (result.capabilities.positionEncoding && result.capabilities.positionEncoding !== 'utf-16') {
      throw new Error('This editor requires a language service with UTF-16 positions.');
    }
    const capabilities = result.capabilities;
    this.resolveCompletions = capabilities.completionProvider?.resolveProvider ?? false;
    this.commands = new Set(capabilities.executeCommandProvider?.commands ?? []);
    this.available = {
      ...this.semanticFeatures.capabilities(capabilities),
      hover: capabilities.hoverProvider ? this.hover.bind(this) : undefined,
      definitions: capabilities.definitionProvider ? this.definitions.bind(this) : undefined,
      references: capabilities.referencesProvider ? this.references.bind(this) : undefined,
      symbols: capabilities.documentSymbolProvider ? this.symbols.bind(this) : undefined,
      completions: capabilities.completionProvider
        ? {
            triggerCharacters: capabilities.completionProvider.triggerCharacters ?? [],
            provide: this.completions.bind(this),
          }
        : undefined,
      format: capabilities.documentFormattingProvider ? this.format.bind(this) : undefined,
      rename: capabilities.renameProvider ? this.rename.bind(this) : undefined,
      signature: capabilities.signatureHelpProvider
        ? {
            triggerCharacters: capabilities.signatureHelpProvider.triggerCharacters ?? [],
            retriggerCharacters: capabilities.signatureHelpProvider.retriggerCharacters ?? [],
            provide: this.signature.bind(this),
          }
        : undefined,
      folding: capabilities.foldingRangeProvider ? this.folding.bind(this) : undefined,
    };
    const sync = result.capabilities.textDocumentSync;
    this.sync = typeof sync === 'number' ? sync : (sync?.change ?? Lsp.TextDocumentSyncKind.None);
    this.connection.notify('initialized', {});
  }
  private command(command: Lsp.Command): LanguageCommand {
    return {
      title: command.title,
      execute: async (signal) => {
        if (!this.commands.has(command.command))
          throw new Error(`Unsupported language service command: ${command.command}`);
        await this.connection.request(
          'workspace/executeCommand',
          {
            command: command.command,
            arguments: command.arguments,
          },
          signal,
        );
      },
    };
  }
  /** Send didOpen once per URI lifetime, then full-text didChange notifications for strictly newer versions. */
  synchronize(document: SourceDocument): void {
    const previous = this.documents.get(document.uri);
    if (previous && previous.version >= document.version) return;
    this.documents.set(document.uri, document);
    if (!previous) this.connection.notify('textDocument/didOpen', { textDocument: document });
    else if (this.sync !== Lsp.TextDocumentSyncKind.None)
      this.connection.notify('textDocument/didChange', {
        textDocument: { uri: document.uri, version: document.version },
        contentChanges: [{ text: document.text }],
      });
  }
  /** Send didClose if the URI is open and forget its version, allowing a later didOpen to start a new lifetime. */
  close(uri: string): void {
    if (this.documents.delete(uri)) this.connection.notify('textDocument/didClose', { textDocument: { uri } });
  }
  /** Subscribe to converted publishDiagnostics messages. The returned function removes this listener. */
  onDiagnostics(listener: (update: AnalysisUpdate) => void): () => void {
    this.diagnostics.add(listener);
    return () => this.diagnostics.delete(listener);
  }
  /** Subscribe to server log/show messages, translated to neutral levels. Return value unsubscribes. */
  onLog(listener: (log: ServiceLog) => void): () => void {
    this.logs.add(listener);
    return () => this.logs.delete(listener);
  }
  /** Subscribe to terminal connection errors. Return value unsubscribes. */
  onFailure(listener: (error: Error) => void): () => void {
    return this.connection.onFailure(listener);
  }
  private request<T>(method: string, document: SourceDocument, params: object, signal: AbortSignal): Promise<T> {
    this.synchronize(document);
    return this.connection.request(method, { textDocument: { uri: document.uri }, ...params }, signal);
  }
  private async hover(document: SourceDocument, position: Position, signal: AbortSignal): Promise<Hover | null> {
    const value = await this.request<Lsp.Hover | null>('textDocument/hover', document, { position }, signal);
    return (
      value && {
        text: Array.isArray(value.contents) ? value.contents.map(markup).join('\n\n') : markup(value.contents),
        range: value.range,
      }
    );
  }
  private async definitions(document: SourceDocument, position: Position, signal: AbortSignal): Promise<Location[]> {
    const result = await this.request<Lsp.Location | Lsp.Location[] | Lsp.LocationLink[] | null>(
      'textDocument/definition',
      document,
      { position },
      signal,
    );
    return (Array.isArray(result) ? result : result ? [result] : []).map((location) =>
      'targetUri' in location ? { uri: location.targetUri, range: location.targetSelectionRange } : location,
    );
  }
  private async references(document: SourceDocument, position: Position, signal: AbortSignal): Promise<Location[]> {
    return (
      (await this.request<Location[] | null>(
        'textDocument/references',
        document,
        {
          position,
          context: { includeDeclaration: true },
        },
        signal,
      )) ?? []
    );
  }
  private async symbols(document: SourceDocument, signal: AbortSignal): Promise<DocumentSymbol[]> {
    const values = await this.request<(Lsp.DocumentSymbol | Lsp.SymbolInformation)[] | null>(
      'textDocument/documentSymbol',
      document,
      {},
      signal,
    );
    const project = (value: Lsp.DocumentSymbol | Lsp.SymbolInformation): DocumentSymbol => ({
      name: value.name,
      detail: 'detail' in value ? value.detail : undefined,
      kind: symbolKind(value.kind),
      location: 'location' in value ? value.location : { uri: document.uri, range: value.selectionRange },
      children: 'children' in value ? (value.children ?? []).map(project) : [],
    });
    return (values ?? []).map(project);
  }
  private async completions(
    document: SourceDocument,
    position: Position,
    signal: AbortSignal,
  ): Promise<CompletionBatch> {
    const result = await this.request<Lsp.CompletionList | Lsp.CompletionItem[] | null>(
      'textDocument/completion',
      document,
      { position },
      signal,
    );
    const items = Array.isArray(result) ? result : (result?.items ?? []);
    // Item defaults are not advertised by this client, so a conforming server sends complete items.
    if (!Array.isArray(result) && result?.itemDefaults) {
      throw new Error('The language service sent completion defaults that this client did not negotiate.');
    }
    return {
      items: items.map((item) => this.completion(item, this.resolveCompletions)),
      isIncomplete: Array.isArray(result) ? false : (result?.isIncomplete ?? false),
    };
  }

  private completion(item: Lsp.CompletionItem, resolve: boolean): Completion {
    const command = item.command;
    return {
      label: item.label,
      insertText: item.textEdit?.newText ?? item.insertText ?? item.label,
      detail: item.detail,
      snippet: item.insertTextFormat === Lsp.InsertTextFormat.Snippet,
      documentation: markup(item.documentation),
      sortText: item.sortText,
      filterText: item.filterText,
      commitCharacters: item.commitCharacters,
      additionalEdits: item.additionalTextEdits?.map((edit) => ({ range: edit.range, text: edit.newText })),
      range:
        item.textEdit &&
        ('range' in item.textEdit
          ? item.textEdit.range
          : {
              insert: item.textEdit.insert,
              replace: item.textEdit.replace,
            }),
      kind: completionKind(item.kind),
      resolve: resolve
        ? async (signal) =>
            this.completion(
              await this.connection.request<Lsp.CompletionItem>('completionItem/resolve', item, signal),
              false,
            )
        : undefined,
      onAccepted: command
        ? async () => {
            if (!this.commands.has(command.command))
              throw new Error(`Unsupported completion command: ${command.command}`);
            await this.connection.request('workspace/executeCommand', {
              command: command.command,
              arguments: command.arguments,
            });
          }
        : undefined,
    };
  }
  private async format(
    document: SourceDocument,
    options: { tabSize: number; insertSpaces: boolean },
    signal: AbortSignal,
  ): Promise<TextEdit[]> {
    const edits = await this.request<Lsp.TextEdit[] | null>('textDocument/formatting', document, { options }, signal);
    return (edits ?? []).map((edit) => ({ range: edit.range, text: edit.newText }));
  }
  private async rename(document: SourceDocument, position: Position, newName: string, signal: AbortSignal) {
    const edit = await this.request<Lsp.WorkspaceEdit | null>(
      'textDocument/rename',
      document,
      { position, newName },
      signal,
    );
    if (!edit) return [];
    const converted = convertWorkspaceEdit(edit);
    if (converted.kind === 'unsupported') throw new Error(converted.reason);
    return converted.changes;
  }
  private async signature(
    document: SourceDocument,
    position: Position,
    signal: AbortSignal,
  ): Promise<SignatureHelp | null> {
    const help = await this.request<Lsp.SignatureHelp | null>(
      'textDocument/signatureHelp',
      document,
      { position },
      signal,
    );
    return (
      help && {
        activeParameter: help.activeParameter === undefined ? 0 : help.activeParameter,
        activeSignature: help.activeSignature ?? 0,
        signatures: help.signatures.map((item) => ({
          label: item.label,
          documentation: markup(item.documentation),
          activeParameter: item.activeParameter,
          parameters: (item.parameters ?? []).map((parameter) => ({
            label: parameter.label,
            documentation: markup(parameter.documentation),
          })),
        })),
      }
    );
  }
  private async folding(document: SourceDocument, signal: AbortSignal) {
    return (await this.request<Lsp.FoldingRange[] | null>('textDocument/foldingRange', document, {}, signal)) ?? [];
  }
  /** Close the transport, clear open-document state, and remove diagnostic/log/feature subscriptions. */
  dispose(): void {
    this.semanticFeatures.dispose();
    this.connection.dispose();
    this.documents.clear();
    this.diagnostics.clear();
    this.logs.clear();
  }
}
function symbolKind(kind: Lsp.SymbolKind): DocumentSymbol['kind'] {
  switch (kind) {
    case Lsp.SymbolKind.Function:
      return 'function';
    case Lsp.SymbolKind.Class:
    case Lsp.SymbolKind.Enum:
    case Lsp.SymbolKind.Interface:
    case Lsp.SymbolKind.Struct:
    case Lsp.SymbolKind.TypeParameter:
      return 'type';
    case Lsp.SymbolKind.Module:
    case Lsp.SymbolKind.Namespace:
    case Lsp.SymbolKind.Package:
      return 'module';
    case Lsp.SymbolKind.Method:
    case Lsp.SymbolKind.Property:
    case Lsp.SymbolKind.Constructor:
      return 'member';
    default:
      return 'variable';
  }
}
function completionKind(kind: Lsp.CompletionItemKind | undefined): Completion['kind'] {
  switch (kind) {
    case Lsp.CompletionItemKind.Field:
    case Lsp.CompletionItemKind.Property:
      return 'field';
    case Lsp.CompletionItemKind.Constructor:
      return 'constructor';
    case Lsp.CompletionItemKind.Module:
      return 'module';
    case Lsp.CompletionItemKind.Keyword:
      return 'keyword';
    case Lsp.CompletionItemKind.Snippet:
      return 'snippet';
    case Lsp.CompletionItemKind.Function:
    case Lsp.CompletionItemKind.Method:
      return 'function';
    case Lsp.CompletionItemKind.Class:
    case Lsp.CompletionItemKind.Interface:
    case Lsp.CompletionItemKind.Struct:
    case Lsp.CompletionItemKind.TypeParameter:
      return 'type';
    default:
      return 'variable';
  }
}
