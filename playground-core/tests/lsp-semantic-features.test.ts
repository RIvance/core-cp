import { describe, expect, it, vi } from 'vitest';
import { LspLanguageService } from '../src/adapters/lsp/service';
import { decodeSemanticTokens } from '../src/adapters/lsp/semantic-tokens';
import type { AnalysisUpdate } from '../src/core/contracts';
import { MessageConnection } from '../src/transport/rpc';
import { TestEndpoint } from './support/endpoint';

const document = { uri: 'file:///workspace/example.other', languageId: 'other', text: 'source', version: 7 };
const range = { start: { line: 0, character: 0 }, end: { line: 0, character: 6 } };
const signal = new AbortController().signal;

async function start(capabilities: object) {
  const endpoint = new TestEndpoint();
  const service = new LspLanguageService(new MessageConnection(endpoint), 'file:///workspace');
  const starting = service.start();
  endpoint.deliver({ jsonrpc: '2.0', id: 1, result: { capabilities } });
  await starting;
  return { service, endpoint };
}

describe('optional LSP editor capabilities', () => {
  it('does not advertise providers the server has not supplied', async () => {
    const { service } = await start({});
    expect(service.capabilities.inlayHints).toBeUndefined();
    expect(service.capabilities.semanticTokens).toBeUndefined();
    expect(service.capabilities.codeActions).toBeUndefined();
    service.dispose();
  });

  it('retains rich hints, opaque resolution data, text edits, and advertised commands', async () => {
    const { service, endpoint } = await start({
      inlayHintProvider: { resolveProvider: true },
      executeCommandProvider: { commands: ['explain'] },
    });
    const pending = service.capabilities.inlayHints!.provide(document, range, signal);
    expect(endpoint.sent.at(-1)).toMatchObject({ method: 'textDocument/inlayHint', params: { range } });
    const hint = { position: range.end, label: [{ value: ': Type' }], kind: 1, data: { node: 12 } };
    endpoint.deliver({ jsonrpc: '2.0', id: 2, result: [hint] });
    const hints = await pending;
    expect(hints[0]).toMatchObject({ label: [{ value: ': Type' }], kind: 'type' });
    const resolving = hints[0]!.resolve!(signal);
    expect(endpoint.sent.at(-1)).toMatchObject({ method: 'inlayHint/resolve', params: hint });
    endpoint.deliver({
      jsonrpc: '2.0',
      id: 3,
      result: {
        ...hint,
        label: [
          {
            value: ': Type',
            tooltip: { kind: 'plaintext', value: 'A type' },
            location: { uri: document.uri, range },
            command: { title: 'Explain', command: 'explain', arguments: [12] },
          },
        ],
        textEdits: [{ range, newText: 'typed source' }],
        paddingLeft: true,
      },
    });
    const resolved = await resolving;
    expect(resolved).toMatchObject({ textEdits: [{ range, text: 'typed source' }], paddingLeft: true });
    if (typeof resolved.label === 'string') throw new Error('Expected label parts');
    expect(resolved.label[0]).toMatchObject({ tooltip: 'A type', location: { uri: document.uri, range } });
    const executing = resolved.label[0]!.command!.execute(signal);
    expect(endpoint.sent.at(-1)).toMatchObject({
      method: 'workspace/executeCommand',
      params: { command: 'explain', arguments: [12] },
    });
    endpoint.deliver({ jsonrpc: '2.0', id: 4, result: null });
    await executing;
    service.dispose();
  });

  it.each([true, false])(
    'supports full or range semantic tokens without requiring delta support (full=%s)',
    async (full) => {
      const legend = { tokenTypes: ['custom-type', 'variable'], tokenModifiers: ['readonly'] };
      const { service, endpoint } = await start({ semanticTokensProvider: { legend, full, range: true } });
      const provider = service.capabilities.semanticTokens!;
      const pending = provider.provide(document, signal);
      expect(endpoint.sent.at(-1)).toMatchObject({
        method: full ? 'textDocument/semanticTokens/full' : 'textDocument/semanticTokens/range',
        params: { textDocument: { uri: document.uri }, ...(full ? {} : { range }) },
      });
      endpoint.deliver({ jsonrpc: '2.0', id: 2, result: { data: [0, 0, 6, 0, 1] } });
      expect(await pending).toEqual([{ range, type: 'custom-type', modifiers: ['readonly'] }]);
      service.dispose();
    },
  );

  it('refreshes providers after current analysis and releases subscriptions on disposal', async () => {
    const { service, endpoint } = await start({
      inlayHintProvider: true,
      semanticTokensProvider: { full: true, legend: { tokenTypes: [], tokenModifiers: [] } },
    });
    service.synchronize(document);
    const hints = vi.fn();
    const tokens = vi.fn();
    const unsubscribe = service.capabilities.inlayHints!.onDidChange!(hints);
    service.capabilities.semanticTokens!.onDidChange!(tokens);
    const publish = (version: number) =>
      endpoint.deliver({
        jsonrpc: '2.0',
        method: 'textDocument/publishDiagnostics',
        params: { uri: document.uri, version, diagnostics: [] },
      });
    publish(6);
    expect(hints).not.toHaveBeenCalled();
    publish(7);
    expect(hints).toHaveBeenCalledTimes(1);
    expect(tokens).toHaveBeenCalledTimes(1);
    unsubscribe();
    publish(7);
    expect(hints).toHaveBeenCalledTimes(1);
    expect(tokens).toHaveBeenCalledTimes(2);
    service.dispose();
    publish(7);
    expect(tokens).toHaveBeenCalledTimes(2);
  });

  it('round-trips diagnostic context and resolves versioned quick fixes', async () => {
    const { service, endpoint } = await start({
      codeActionProvider: { codeActionKinds: ['quickfix'], resolveProvider: true },
    });
    let update: AnalysisUpdate | undefined;
    service.onDiagnostics((value) => {
      update = value;
    });
    const diagnostic = { range, severity: 2, source: 'other', code: 41, message: 'Needs a fix', data: { node: 3 } };
    endpoint.deliver({
      jsonrpc: '2.0',
      method: 'textDocument/publishDiagnostics',
      params: {
        uri: document.uri,
        version: 7,
        diagnostics: [diagnostic],
      },
    });
    const pending = service.capabilities.codeActions!.provide(
      document,
      range,
      {
        diagnostics: update!.diagnostics,
        only: ['quickfix'],
        trigger: 'invoked',
      },
      signal,
    );
    expect(endpoint.sent.at(-1)).toMatchObject({
      method: 'textDocument/codeAction',
      params: {
        range,
        context: { diagnostics: [diagnostic], only: ['quickfix'], triggerKind: 1 },
      },
    });
    const action = {
      title: 'Fix source',
      kind: 'quickfix',
      isPreferred: true,
      data: { action: 4 },
      diagnostics: [diagnostic],
    };
    endpoint.deliver({ jsonrpc: '2.0', id: 2, result: [action] });
    const actions = await pending;
    expect(actions[0]).toMatchObject({ title: action.title, diagnostics: update!.diagnostics, isPreferred: true });
    const resolving = actions[0]!.resolve!(signal);
    expect(endpoint.sent.at(-1)).toMatchObject({ method: 'codeAction/resolve', params: action });
    endpoint.deliver({
      jsonrpc: '2.0',
      id: 3,
      result: {
        ...action,
        edit: {
          documentChanges: [
            {
              textDocument: { uri: document.uri, version: 7 },
              edits: [{ range, newText: 'fixed' }],
            },
          ],
        },
      },
    });
    expect(await resolving).toMatchObject({
      edit: [{ uri: document.uri, version: 7, edits: [{ range, text: 'fixed' }] }],
    });
    service.dispose();
  });

  it('accepts legacy commands, reports unsupported commands, and disables whole actions with file operations', async () => {
    const { service, endpoint } = await start({ codeActionProvider: true });
    const pending = service.capabilities.codeActions!.provide(
      document,
      range,
      { diagnostics: [], trigger: 'automatic' },
      signal,
    );
    endpoint.deliver({
      jsonrpc: '2.0',
      id: 2,
      result: [
        { title: 'Legacy command', command: 'not-advertised' },
        {
          title: 'Create file',
          edit: {
            documentChanges: [
              { textDocument: { uri: document.uri, version: 7 }, edits: [{ range, newText: 'partial' }] },
              { kind: 'create', uri: 'file:///workspace/new.other' },
            ],
          },
        },
        { title: 'Unavailable', disabled: { reason: 'Requires a selected expression' } },
      ],
    });
    const actions = await pending;
    await expect(actions[0]!.command!.execute(signal)).rejects.toThrow('Unsupported language service command');
    expect(actions[1]!.disabled).toContain('file operations');
    expect(actions[1]!.edit).toBeUndefined();
    expect(actions[2]!.disabled).toBe('Requires a selected expression');
    service.dispose();
  });

  it('propagates request cancellation', async () => {
    const { service, endpoint } = await start({ inlayHintProvider: true });
    const cancellation = new AbortController();
    const pending = service.capabilities.inlayHints!.provide(document, range, cancellation.signal);
    const rejected = expect(pending).rejects.toThrow();
    cancellation.abort();
    await rejected;
    expect(endpoint.sent.at(-1)).toMatchObject({ method: '$/cancelRequest', params: { id: 2 } });
    service.dispose();
  });
});

describe('semantic token wire validation', () => {
  const legend = { tokenTypes: ['variable'], tokenModifiers: ['readonly'] };
  it('decodes UTF-16 coordinates and line resets', () => {
    expect(
      decodeSemanticTokens({ data: [0, 2, 2, 0, 1, 1, 1, 3, 0, 0] }, legend, { ...document, text: '🌍ab\r\n xyz' }),
    ).toEqual([
      {
        range: { start: { line: 0, character: 2 }, end: { line: 0, character: 4 } },
        type: 'variable',
        modifiers: ['readonly'],
      },
      { range: { start: { line: 1, character: 1 }, end: { line: 1, character: 4 } }, type: 'variable', modifiers: [] },
    ]);
    expect(decodeSemanticTokens(null, legend, document)).toEqual([]);
  });
  it.each([
    [0, 0],
    [0, 0, 0, 0, 0],
    [0, 0, 7, 0, 0],
    [0, 0, 1, 1, 0],
    [0, 0, 1, 0, 2],
    [0, 0, 3, 0, 0, 0, 1, 2, 0, 0],
    [1, 0, 1, 0, 0],
    [-1, 0, 1, 0, 0],
    [0, 0, 1.5, 0, 0],
  ])('rejects malformed or overlapping data: %j', (...data) => {
    expect(() => decodeSemanticTokens({ data }, legend, document)).toThrow();
  });
});
