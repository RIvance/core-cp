import { rpcMessageSchema } from './protocol';

/** A worker-compatible message transport owned by MessageConnection. Each message contains one JSON-RPC object. */
export interface MessageEndpoint {
  /** Send a message to the peer. Throw when delivery cannot be started. */
  postMessage(message: unknown): void;
  /** Subscribe to incoming messages. event.data contains the decoded object, not stdio framing or JSON text. */
  addEventListener(type: 'message', listener: (event: MessageEvent) => void): void;
  /** Subscribe to terminal transport failures. */
  addEventListener(type: 'error', listener: (event: ErrorEvent) => void): void;
  /** Remove a previously registered message callback. */
  removeEventListener(type: 'message', listener: (event: MessageEvent) => void): void;
  /** Remove a previously registered failure callback. */
  removeEventListener(type: 'error', listener: (event: ErrorEvent) => void): void;
  /** Close the underlying transport and stop its worker or other owned resources. */
  terminate(): void;
}
/** A remote JSON-RPC error, preserving the peer's numeric code and optional data. */
export class RpcError extends Error {
  constructor(
    message: string,
    readonly code: number,
    readonly data?: unknown,
  ) {
    super(message);
  }
}
interface Pending {
  resolve(value: unknown): void;
  reject(error: Error): void;
  cleanup(): void;
}

/**
 * JSON-RPC request correlation, cancellation, and notifications over an owned endpoint.
 * Invalid envelopes fail the connection. Result payload validation belongs to the caller.
 * Pending requests reject on disposal or transport failure; unsupported peer requests receive MethodNotFound.
 */
export class MessageConnection {
  private sequence = 0;
  private pending = new Map<number, Pending>();
  private listeners = new Set<(method: string, params: unknown) => void>();
  private failures = new Set<(error: Error) => void>();
  private closed = false;
  /** Take ownership of endpoint. timeout is the per-request deadline in milliseconds, defaulting to 30 seconds. */
  constructor(
    private readonly endpoint: MessageEndpoint,
    private readonly timeout: number = 30_000,
  ) {
    endpoint.addEventListener('message', this.receive);
    endpoint.addEventListener('error', this.failed);
  }
  /** Subscribe to peer notifications. The returned function removes the listener. */
  onNotification(listener: (method: string, params: unknown) => void): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }
  /** Subscribe to terminal failures, before the endpoint is disposed. Return value unsubscribes. */
  onFailure(listener: (error: Error) => void): () => void {
    this.failures.add(listener);
    return () => this.failures.delete(listener);
  }
  /** Send without expecting a reply. Throws when the connection is closed or the transport rejects delivery. */
  notify(method: string, params: unknown): void {
    if (this.closed) throw new Error('The language connection is closed.');
    this.endpoint.postMessage({ jsonrpc: '2.0', method, params });
  }
  /**
   * Send a correlated request. Reject on a remote RpcError, deadline, transport failure, disposal, or cancellation.
   * Cancellation/deadline also sends $/cancelRequest. T describes the expected payload; it is not runtime validation.
   */
  request<T>(method: string, params: unknown, signal?: AbortSignal): Promise<T> {
    if (signal?.aborted) return Promise.reject(new DOMException('Cancelled', 'AbortError'));
    if (this.closed) return Promise.reject(new Error('The language connection is closed.'));
    const id = ++this.sequence;
    return new Promise<T>((resolve, reject) => {
      const cancel = () => {
        settle(new DOMException('Cancelled', 'AbortError'));
        cancelRemote();
      };
      const cancelRemote = () => {
        try {
          this.notify('$/cancelRequest', { id });
        } catch (error) {
          this.fail(error instanceof Error ? error : new Error(String(error)));
        }
      };
      const settle = (error: Error) => {
        const pending = this.pending.get(id);
        if (!pending) return;
        pending.cleanup();
        this.pending.delete(id);
        reject(error);
      };
      const timer = setTimeout(() => {
        settle(new Error(`Language request timed out: ${method}`));
        cancelRemote();
      }, this.timeout);
      const cleanup = () => {
        clearTimeout(timer);
        signal?.removeEventListener('abort', cancel);
      };
      this.pending.set(id, { resolve: (value) => resolve(value as T), reject, cleanup });
      signal?.addEventListener('abort', cancel, { once: true });
      try {
        this.endpoint.postMessage({ jsonrpc: '2.0', id, method, params });
      } catch (error) {
        settle(error instanceof Error ? error : new Error(String(error)));
      }
    });
  }
  private receive = (event: MessageEvent<unknown>) => {
    const decoded = rpcMessageSchema.safeParse(event.data);
    if (!decoded.success) {
      this.fail(new Error('The worker sent an invalid JSON-RPC message.'));
      return;
    }
    const message = decoded.data;
    if ('method' in message) {
      // Unsupported server-to-client requests receive an explicit response instead of hanging.
      if ('id' in message) {
        this.endpoint.postMessage({
          jsonrpc: '2.0',
          id: message.id,
          error: { code: -32601, message: `Unsupported client method: ${message.method}` },
        });
      } else {
        try {
          for (const listener of this.listeners) listener(message.method, message.params);
        } catch (error) {
          this.fail(error instanceof Error ? error : new Error(String(error)));
        }
      }
      return;
    }
    if (typeof message.id !== 'number') return;
    const pending = this.pending.get(message.id);
    if (!pending) return;
    this.pending.delete(message.id);
    pending.cleanup();
    if ('error' in message) pending.reject(new RpcError(message.error.message, message.error.code, message.error.data));
    else pending.resolve(message.result);
  };
  private failed = (event: ErrorEvent) => this.fail(new Error(event.message || 'The language worker stopped.'));
  private fail(error: Error): void {
    try {
      for (const listener of this.failures) listener(error);
    } finally {
      this.dispose(error);
    }
  }
  /** Idempotently close the endpoint, remove listeners/timers, and reject all pending requests with reason. */
  dispose(reason: Error = new Error('The language connection was closed.')): void {
    if (this.closed) return;
    this.closed = true;
    this.endpoint.removeEventListener('message', this.receive);
    this.endpoint.removeEventListener('error', this.failed);
    this.endpoint.terminate();
    for (const pending of this.pending.values()) {
      pending.cleanup();
      pending.reject(reason);
    }
    this.pending.clear();
    this.listeners.clear();
    this.failures.clear();
  }
}
