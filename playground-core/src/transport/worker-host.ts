import type { ExecutionInput, ExecutionResult, OutputChunk } from '../core/contracts';
import { executionInputSchema, executionResultSchema, outputChunkSchema, rpcMessageSchema } from './protocol';
import type { RpcMessage } from './protocol';
import { RpcError } from './rpc';

/** The worker side of a message connection. A DedicatedWorkerGlobalScope satisfies this contract. */
export interface WorkerScope {
  /** Send one structured-cloneable message to the owning thread. Throw if delivery fails. */
  postMessage(message: unknown): void;
  /** Subscribe to requests from the owning thread. */
  addEventListener(type: 'message', listener: (event: MessageEvent<unknown>) => void): void;
  /** Remove the same listener previously registered with addEventListener. */
  removeEventListener(type: 'message', listener: (event: MessageEvent<unknown>) => void): void;
  /** Report a terminal failure through the worker's error event, so the owning connection can close. */
  reportError(error: unknown): void;
}

/**
 * An execution implementation inside a worker. It has no dependency on a compiler implementation or LSP.
 * Cancellation belongs to the owner, which terminates the worker to interrupt synchronous code.
 */
export interface ExecutionBackend {
  /**
   * Execute a validated snapshot, emitting ordered output before the result settles.
   * Return status 'error' for source/program failures; throw or reject for infrastructure failures.
   * The host validates output and results. Output after settlement or disposal is ignored.
   */
  execute(input: ExecutionInput, emit: (chunk: OutputChunk) => void): ExecutionResult | Promise<ExecutionResult>;
}

/** An in-process JSON-RPC server. Language servers can use this contract without adopting the execution API. */
export interface JsonRpcBackend {
  /**
   * Receive one validated envelope, including notifications and responses to server requests.
   * Invocations begin in arrival order; returned promises may settle independently. The backend owns state ordering.
   * Emit replies and unsolicited notifications through the callback passed to its factory.
   * Throw/reject to send an RPC error for a request or fail the connection for a notification/response.
   */
  receive(message: RpcMessage): void | Promise<void>;
  /** Release subscriptions or other resources on explicit host disposal. Worker termination stops the entire context. */
  dispose?(): void;
}

/**
 * Serve runtime/execute and runtime/output using the neutral worker protocol. At most one execution is active.
 * Invalid input receives InvalidParams; unsupported requests receive MethodNotFound; overlapping runs are rejected.
 * Return an idempotent disposer that removes the listener and suppresses pending results/output.
 * Disposal detaches this host; only terminating the worker can stop synchronous execution.
 */
export function serveExecution(scope: WorkerScope, backend: ExecutionBackend): () => void {
  let running = false;
  const host = new WorkerHost(scope);
  host.start(async (message) => {
    if (!('method' in message) || !('id' in message)) return;
    if (message.method !== 'runtime/execute') throw new RpcError('Unknown runtime method.', -32601);
    const decoded = executionInputSchema.safeParse(message.params);
    if (!decoded.success) throw new RpcError('Invalid execution input.', -32602);
    if (running) throw new RpcError('An execution is already in progress.', -32000);
    running = true;
    let emitting = true;
    try {
      const result = executionResultSchema.parse(
        await backend.execute(decoded.data, (chunk) => {
          if (!emitting || host.closed) return;
          const output = outputChunkSchema.parse(chunk);
          if (output.text.length > 0) host.send({ jsonrpc: '2.0', method: 'runtime/output', params: output });
        }),
      );
      host.send({ jsonrpc: '2.0', id: message.id, result });
    } finally {
      emitting = false;
      running = false;
    }
  });
  return () => host.dispose();
}

/**
 * Connect an in-process server to a worker. The factory receives a validated outbound-message sink, which it may
 * use during initialization or later for unsolicited notifications. Method dispatch belongs to the backend.
 * Malformed envelopes or delivery failures close the host and report a worker error. Request failures retain RpcError
 * code/data; other failures use InternalError. The returned disposer also releases the backend, once.
 */
export function serveJsonRpc(
  scope: WorkerScope,
  createBackend: (emit: (message: RpcMessage) => void) => JsonRpcBackend,
): () => void {
  const host = new WorkerHost(scope);
  try {
    const backend = createBackend((message) => host.send(message));
    host.start(
      (message) => backend.receive(message),
      () => backend.dispose?.(),
    );
  } catch (error) {
    host.dispose();
    throw error;
  }
  return () => host.dispose();
}

class WorkerHost {
  closed = false;
  private receive: ((message: RpcMessage) => void | Promise<void>) | undefined;
  private release: (() => void) | undefined;
  constructor(private readonly scope: WorkerScope) {}
  start(receive: (message: RpcMessage) => void | Promise<void>, release?: () => void) {
    if (this.closed) {
      release?.();
      return;
    }
    this.receive = receive;
    this.release = release;
    this.scope.addEventListener('message', this.listen);
  }
  send(message: RpcMessage): void {
    if (this.closed) return;
    try {
      this.scope.postMessage(rpcMessageSchema.parse(message));
    } catch (error) {
      this.fail(error);
    }
  }
  private listen = (event: MessageEvent<unknown>): void => {
    if (this.closed) return;
    const decoded = rpcMessageSchema.safeParse(event.data);
    if (!decoded.success) {
      this.fail(new Error('The host received an invalid JSON-RPC message.', { cause: decoded.error }));
      return;
    }
    void this.dispatch(decoded.data);
  };
  private async dispatch(message: RpcMessage): Promise<void> {
    try {
      await this.receive?.(message);
    } catch (error) {
      if ('method' in message && 'id' in message) {
        this.send({
          jsonrpc: '2.0',
          id: message.id,
          error: {
            code: error instanceof RpcError ? error.code : -32603,
            message: error instanceof Error ? error.message : String(error),
            ...(error instanceof RpcError && error.data !== undefined ? { data: error.data } : {}),
          },
        });
      } else {
        this.fail(error);
      }
    }
  }
  private fail(error: unknown): void {
    if (this.closed) return;
    let failure = error;
    try {
      this.dispose();
    } catch (cleanupError) {
      failure = new AggregateError([error, cleanupError], 'The worker failed and could not release its backend.', {
        cause: error,
      });
    }
    this.scope.reportError(failure);
  }
  dispose(): void {
    if (this.closed) return;
    this.closed = true;
    this.scope.removeEventListener('message', this.listen);
    this.release?.();
  }
}
