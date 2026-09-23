import { describe, expect, it, vi } from 'vitest';
import type { ExecutionInput, ExecutionResult, OutputChunk } from '../src/core/contracts';
import type { RpcMessage } from '../src/transport/protocol';
import { RpcError } from '../src/transport/rpc';
import { serveExecution, serveJsonRpc, type WorkerScope } from '../src/transport/worker-host';

class TestWorkerScope implements WorkerScope {
  readonly messages: unknown[] = [];
  readonly failures: unknown[] = [];
  private readonly events = new EventTarget();
  postMessage(message: unknown): void {
    this.messages.push(structuredClone(message));
  }
  reportError(error: unknown): void {
    this.failures.push(error);
  }
  addEventListener(type: 'message', listener: (event: MessageEvent<unknown>) => void): void {
    this.events.addEventListener(type, listener as EventListener);
  }
  removeEventListener(type: 'message', listener: (event: MessageEvent<unknown>) => void): void {
    this.events.removeEventListener(type, listener as EventListener);
  }
  deliver(message: unknown): void {
    this.events.dispatchEvent(new MessageEvent('message', { data: structuredClone(message) }));
  }
}
const input: ExecutionInput = {
  documents: [{ uri: 'file:///source.txt', languageId: 'plain', version: 1, text: '🌍' }],
  entryDocumentUri: 'file:///source.txt',
  entryPoint: 'main',
  stdin: 'input',
};
const result: ExecutionResult = { status: 'success', value: '🌍', diagnostics: [], artifacts: [] };
const request = (id = 1, params: unknown = input) => ({ jsonrpc: '2.0', id, method: 'runtime/execute', params });

describe('language-neutral execution worker host', () => {
  it.each([false, true])(
    'streams output and validates a %s asynchronous result without Scala.js',
    async (asynchronous) => {
      const scope = new TestWorkerScope();
      serveExecution(scope, {
        execute(snapshot, emit) {
          expect(snapshot).toEqual(input);
          emit({ channel: 'stdout', text: snapshot.documents[0]?.text ?? '' });
          emit({ channel: 'stderr', text: snapshot.stdin });
          emit({ channel: 'stdout', text: '' });
          return asynchronous ? Promise.resolve(result) : result;
        },
      });
      scope.deliver(request());
      await vi.waitFor(() => expect(scope.messages).toHaveLength(3));
      expect(scope.messages).toEqual([
        { jsonrpc: '2.0', method: 'runtime/output', params: { channel: 'stdout', text: '🌍' } },
        { jsonrpc: '2.0', method: 'runtime/output', params: { channel: 'stderr', text: 'input' } },
        { jsonrpc: '2.0', id: 1, result },
      ]);
      expect(scope.failures).toEqual([]);
    },
  );
  it('rejects invalid inputs and unsupported methods without invoking the compiler', async () => {
    const scope = new TestWorkerScope();
    const execute = vi.fn(() => result);
    serveExecution(scope, { execute });
    scope.deliver(request(1, { ...input, entryDocumentUri: 'file:///missing.txt' }));
    scope.deliver(request(2, { ...input, documents: [...input.documents, ...input.documents] }));
    scope.deliver({ jsonrpc: '2.0', id: 3, method: 'missing' });
    await vi.waitFor(() => expect(scope.messages).toHaveLength(3));
    expect(scope.messages).toMatchObject([
      { id: 1, error: { code: -32602 } },
      { id: 2, error: { code: -32602 } },
      { id: 3, error: { code: -32601 } },
    ]);
    expect(execute).not.toHaveBeenCalled();
  });
  it.each(['result', 'output', 'throw', 'reject'])(
    'reports invalid %s as a correlated infrastructure failure',
    async (kind) => {
      const scope = new TestWorkerScope();
      serveExecution(scope, {
        execute(_input, emit) {
          if (kind === 'throw') throw new Error('Compiler failed');
          if (kind === 'reject') return Promise.reject(new Error('Compiler failed'));
          if (kind === 'output') emit({ channel: 'invalid', text: 'text' } as unknown as OutputChunk);
          return { ...result, status: 'invalid' } as unknown as ExecutionResult;
        },
      });
      scope.deliver(request());
      await vi.waitFor(() => expect(scope.messages).toHaveLength(1));
      expect(scope.messages[0]).toMatchObject({ id: 1, error: { code: -32603 } });
    },
  );
  it('rejects concurrent executions and suppresses output/results after disposal', async () => {
    const scope = new TestWorkerScope();
    let finish: (value: ExecutionResult) => void = () => {};
    const pending = new Promise<ExecutionResult>((resolve) => {
      finish = resolve;
    });
    let emitOutput: ((chunk: OutputChunk) => void) | undefined;
    const execute = vi.fn((_input: ExecutionInput, emit: (chunk: OutputChunk) => void) => {
      emitOutput = emit;
      return pending;
    });
    const dispose = serveExecution(scope, { execute });
    scope.deliver(request(1));
    scope.deliver(request(2));
    await vi.waitFor(() => expect(scope.messages).toHaveLength(1));
    expect(scope.messages[0]).toMatchObject({ id: 2, error: { code: -32000 } });
    dispose();
    dispose();
    emitOutput?.({ channel: 'stdout', text: 'late' });
    finish(result);
    await pending;
    scope.deliver(request(3));
    expect(scope.messages).toHaveLength(1);
    expect(execute).toHaveBeenCalledOnce();
  });
  it('does not emit output retained by a backend after its run settles', async () => {
    const scope = new TestWorkerScope();
    let emitOutput: ((chunk: OutputChunk) => void) | undefined;
    serveExecution(scope, {
      execute(_input, emit) {
        emitOutput = emit;
        return result;
      },
    });
    scope.deliver(request());
    await vi.waitFor(() => expect(scope.messages).toHaveLength(1));
    emitOutput?.({ channel: 'stdout', text: 'late' });
    expect(scope.messages).toHaveLength(1);
  });
});

describe('language-neutral JSON-RPC worker host', () => {
  it('forwards envelopes and unsolicited notifications while a prior request is pending', async () => {
    const scope = new TestWorkerScope();
    let finish: () => void = () => {};
    const pending = new Promise<void>((resolve) => {
      finish = resolve;
    });
    const received: RpcMessage[] = [];
    let emitMessage: ((message: RpcMessage) => void) | undefined;
    const disposeBackend = vi.fn();
    const dispose = serveJsonRpc(scope, (emit) => {
      emitMessage = emit;
      return {
        receive(message) {
          received.push(message);
          return 'id' in message && message.id === 1 ? pending : undefined;
        },
        dispose: disposeBackend,
      };
    });
    scope.deliver({ jsonrpc: '2.0', id: 1, method: 'slow' });
    scope.deliver({ jsonrpc: '2.0', method: '$/cancelRequest', params: { id: 1 } });
    scope.deliver({ jsonrpc: '2.0', id: 'server-request', result: null });
    expect(received).toHaveLength(3);
    emitMessage?.({ jsonrpc: '2.0', method: 'analysis', params: { version: 3 } });
    expect(scope.messages).toMatchObject([{ method: 'analysis', params: { version: 3 } }]);
    dispose();
    dispose();
    finish();
    await pending;
    emitMessage?.({ jsonrpc: '2.0', id: 1, result: null });
    scope.deliver({ jsonrpc: '2.0', id: 2, method: 'after-disposal' });
    expect(scope.messages).toHaveLength(1);
    expect(received).toHaveLength(3);
    expect(disposeBackend).toHaveBeenCalledOnce();
  });
  it('preserves structured request errors and fails the connection for notification failures', async () => {
    const scope = new TestWorkerScope();
    const dispose = vi.fn();
    serveJsonRpc(scope, () => ({
      receive() {
        throw new RpcError('Unavailable', -32042, { retry: false });
      },
      dispose,
    }));
    scope.deliver({ jsonrpc: '2.0', id: 'request', method: 'query' });
    await vi.waitFor(() => expect(scope.messages).toHaveLength(1));
    expect(scope.messages[0]).toMatchObject({
      id: 'request',
      error: { code: -32042, message: 'Unavailable', data: { retry: false } },
    });
    scope.deliver({ jsonrpc: '2.0', method: 'change' });
    await vi.waitFor(() => expect(scope.failures).toHaveLength(1));
    expect(dispose).toHaveBeenCalledOnce();
  });
  it.each(['incoming', 'outgoing'])('fails once for a malformed %s envelope and detaches the host', (direction) => {
    const scope = new TestWorkerScope();
    let emitMessage: ((message: RpcMessage) => void) | undefined;
    const receive = vi.fn();
    const dispose = vi.fn();
    serveJsonRpc(scope, (emit) => {
      emitMessage = emit;
      return { receive, dispose };
    });
    if (direction === 'incoming') scope.deliver({ jsonrpc: '2.0', id: 1 });
    else emitMessage?.({ jsonrpc: '2.0', id: 1 } as RpcMessage);
    scope.deliver({ jsonrpc: '2.0', id: 2, method: 'after-failure' });
    expect(scope.failures).toHaveLength(1);
    expect(dispose).toHaveBeenCalledOnce();
    expect(receive).not.toHaveBeenCalled();
    expect(scope.messages).toEqual([]);
  });
  it('releases a backend whose initial notification cannot be delivered', () => {
    const scope = new TestWorkerScope();
    const failure = new Error('Message delivery failed');
    vi.spyOn(scope, 'postMessage').mockImplementation(() => {
      throw failure;
    });
    const receive = vi.fn();
    const disposeBackend = vi.fn();
    const dispose = serveJsonRpc(scope, (emit) => {
      emit({ jsonrpc: '2.0', method: 'ready' });
      return { receive, dispose: disposeBackend };
    });
    scope.deliver({ jsonrpc: '2.0', method: 'after-failure' });
    dispose();
    expect(scope.failures).toEqual([failure]);
    expect(disposeBackend).toHaveBeenCalledOnce();
    expect(receive).not.toHaveBeenCalled();
  });
  it('propagates factory failures without leaving a listener attached', () => {
    const scope = new TestWorkerScope();
    expect(() =>
      serveJsonRpc(scope, () => {
        throw new Error('Initialization failed');
      }),
    ).toThrow('Initialization failed');
    scope.deliver({ jsonrpc: '2.0', id: 1, method: 'after-failure' });
    expect(scope.messages).toEqual([]);
    expect(scope.failures).toEqual([]);
  });
  it('preserves processing and cleanup failures without leaving a rejected dispatch promise', async () => {
    const scope = new TestWorkerScope();
    const failure = new Error('Analysis failed');
    const cleanupFailure = new Error('Cleanup failed');
    serveJsonRpc(scope, () => ({
      receive: () => Promise.reject(failure),
      dispose() {
        throw cleanupFailure;
      },
    }));
    scope.deliver({ jsonrpc: '2.0', method: 'change' });
    await vi.waitFor(() => expect(scope.failures).toHaveLength(1));
    expect(scope.failures[0]).toMatchObject({ cause: failure, errors: [failure, cleanupFailure] });
    expect(scope.messages).toEqual([]);
  });
});
