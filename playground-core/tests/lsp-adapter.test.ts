import { describe, expect, it, vi } from 'vitest';
import { LspLanguageService } from '../src/adapters/lsp/service';
import { MessageConnection } from '../src/transport/rpc';
import { TestEndpoint } from './support/endpoint';

const document = { uri: 'file:///custom/source.other', languageId: 'another-language', version: 1, text: 'source' };
describe('generic LSP adapter', () => {
  it('preserves completion edits, incomplete lists, resolution and accepted commands', async () => {
    const endpoint = new TestEndpoint();
    const service = new LspLanguageService(new MessageConnection(endpoint), 'file:///custom');
    const starting = service.start();
    endpoint.deliver({
      jsonrpc: '2.0',
      id: 1,
      result: {
        capabilities: {
          completionProvider: { resolveProvider: true },
          executeCommandProvider: { commands: ['completeImport'] },
        },
      },
    });
    await starting;
    const signal = new AbortController().signal;
    const pending = service.capabilities.completions?.provide(document, { line: 0, character: 3 }, signal);
    const insert = { start: { line: 0, character: 0 }, end: { line: 0, character: 3 } };
    const replace = { ...insert, end: { line: 0, character: 6 } };
    const item = {
      label: 'source',
      kind: 3,
      insertTextFormat: 2,
      textEdit: { newText: 'source(${1:value})', insert, replace },
      data: { identity: 'server-owned-identity' },
    };
    endpoint.deliver({ jsonrpc: '2.0', id: 2, result: { isIncomplete: true, items: [item] } });
    const batch = await pending;
    expect(batch?.isIncomplete).toBe(true);
    const completion = batch?.items[0];
    expect(completion).toMatchObject({
      label: 'source',
      insertText: 'source(${1:value})',
      range: { insert, replace },
      snippet: true,
    });
    const resolving = completion?.resolve?.(signal);
    expect(endpoint.sent.at(-1)).toMatchObject({ method: 'completionItem/resolve', params: item });
    endpoint.deliver({
      jsonrpc: '2.0',
      id: 3,
      result: {
        ...item,
        documentation: { kind: 'plaintext', value: 'Resolved documentation' },
        additionalTextEdits: [{ range: insert, newText: 'import source' }],
        command: { title: 'Complete import', command: 'completeImport', arguments: ['source'] },
      },
    });
    const resolved = await resolving;
    expect(resolved).toMatchObject({
      documentation: 'Resolved documentation',
      additionalEdits: [{ range: insert, text: 'import source' }],
    });
    const accepting = resolved?.onAccepted?.();
    expect(endpoint.sent.at(-1)).toMatchObject({
      method: 'workspace/executeCommand',
      params: { command: 'completeImport', arguments: ['source'] },
    });
    endpoint.deliver({ jsonrpc: '2.0', id: 4, result: null });
    await accepting;
    service.dispose();
  });
  it('derives operations and trigger characters from server capabilities for an arbitrary language', async () => {
    const endpoint = new TestEndpoint();
    const service = new LspLanguageService(new MessageConnection(endpoint), 'file:///custom');
    const starting = service.start();
    expect(endpoint.sent[0]).toMatchObject({ method: 'initialize', params: { rootUri: 'file:///custom' } });
    endpoint.deliver({
      jsonrpc: '2.0',
      id: 1,
      result: {
        capabilities: {
          textDocumentSync: 2,
          hoverProvider: true,
          completionProvider: { triggerCharacters: ['?'] },
          signatureHelpProvider: { triggerCharacters: [';'], retriggerCharacters: [':'] },
        },
      },
    });
    await starting;
    expect(service.capabilities.rename).toBeUndefined();
    expect(service.capabilities.format).toBeUndefined();
    expect(service.capabilities.inlayHints).toBeUndefined();
    expect(service.capabilities.semanticTokens).toBeUndefined();
    expect(service.capabilities.codeActions).toBeUndefined();
    expect(service.capabilities.completions?.triggerCharacters).toEqual(['?']);
    expect(service.capabilities.signature?.triggerCharacters).toEqual([';']);
    expect(service.capabilities.signature?.retriggerCharacters).toEqual([':']);

    const hover = service.capabilities.hover?.(document, { line: 0, character: 1 }, new AbortController().signal);
    expect(endpoint.sent.at(-2)).toMatchObject({ method: 'textDocument/didOpen', params: { textDocument: document } });
    endpoint.deliver({ jsonrpc: '2.0', id: 2, result: { contents: { kind: 'plaintext', value: 'A real signature' } } });
    expect(await hover).toEqual({ text: 'A real signature', range: undefined });
    service.synchronize({ ...document, version: 2, text: 'updated' });
    expect(endpoint.sent.at(-1)).toMatchObject({
      method: 'textDocument/didChange',
      params: {
        textDocument: { uri: document.uri, version: 2 },
        contentChanges: [{ text: 'updated' }],
      },
    });
    const count = endpoint.sent.length;
    service.synchronize(document);
    expect(endpoint.sent).toHaveLength(count);
    service.close(document.uri);
    expect(endpoint.sent.at(-1)).toMatchObject({ method: 'textDocument/didClose' });
    service.dispose();
  });

  it('preserves diagnostics and rejects incompatible position encodings', async () => {
    const endpoint = new TestEndpoint();
    const service = new LspLanguageService(new MessageConnection(endpoint), 'file:///custom');
    const diagnostics = vi.fn();
    service.onDiagnostics(diagnostics);
    endpoint.deliver({
      jsonrpc: '2.0',
      method: 'textDocument/publishDiagnostics',
      params: {
        uri: document.uri,
        version: 3,
        diagnostics: [
          {
            range: { start: { line: 2, character: 4 }, end: { line: 2, character: 8 } },
            severity: 2,
            code: 'warning-code',
            source: 'other-compiler',
            message: 'A warning',
          },
        ],
      },
    });
    expect(diagnostics).toHaveBeenCalledWith({
      uri: document.uri,
      version: 3,
      diagnostics: [
        {
          message: 'A warning',
          severity: 'warning',
          code: 'warning-code',
          source: 'other-compiler',
          location: { uri: document.uri, range: { start: { line: 2, character: 4 }, end: { line: 2, character: 8 } } },
        },
      ],
    });
    const starting = service.start();
    const rejected = expect(starting).rejects.toThrow('UTF-16');
    endpoint.deliver({ jsonrpc: '2.0', id: 1, result: { capabilities: { positionEncoding: 'utf-8' } } });
    await rejected;
    service.dispose();
  });
});
