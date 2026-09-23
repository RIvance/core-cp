import type { ExecutionInput, ExecutionResult, LanguageRuntime, OutputChunk } from '../core/contracts';
import { MessageConnection, type MessageEndpoint } from './rpc';
import { executionResultSchema, outputChunkSchema } from './protocol';

/**
 * LanguageRuntime using the runtime/execute request and runtime/output notifications over JSON-RPC.
 * Every run owns a fresh endpoint; completion, failure, or abort terminates it. Responses are schema-validated.
 */
export class WorkerRuntime implements LanguageRuntime {
  /** createWorker must return a new endpoint for each call, with no mutable execution state shared between runs. */
  constructor(private readonly createWorker: () => MessageEndpoint) {}
  /** Execute one snapshot. Abort terminates the worker even during synchronous loops; invalid payloads reject the run. */
  async execute(
    input: ExecutionInput,
    emit: (chunk: OutputChunk) => void,
    signal: AbortSignal,
  ): Promise<ExecutionResult> {
    const connection = new MessageConnection(this.createWorker(), 65_000);
    connection.onNotification((method, params) => {
      if (method === 'runtime/output') emit(outputChunkSchema.parse(params));
    });
    const abort = () => connection.dispose(new DOMException('Execution stopped', 'AbortError'));
    signal.addEventListener('abort', abort, { once: true });
    try {
      if (signal.aborted) throw new DOMException('Execution stopped', 'AbortError');
      return executionResultSchema.parse(await connection.request<unknown>('runtime/execute', input));
    } finally {
      signal.removeEventListener('abort', abort);
      connection.dispose();
    }
  }
}
