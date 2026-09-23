import { afterEach, describe, expect, it, vi } from 'vitest';
import { MessageConnection } from '../src/transport/rpc';
import { TestEndpoint } from './support/endpoint';
import { rpcMessageSchema } from '../src/transport/protocol';

afterEach(() => vi.useRealTimers());
describe('message connection lifecycle', () => {
  it.each([
    { jsonrpc: '2.0', id: 1 },
    { jsonrpc: '2.0', id: 1, error: {} },
    { jsonrpc: '2.0', id: 1, method: 5 },
    { jsonrpc: '2.0', id: 1, result: null, error: { code: -1, message: 'ambiguous' } },
    { jsonrpc: '2.0', id: null, method: 'request', params: {} },
    { jsonrpc: '2.0', id: 1, method: 'request', result: 'ambiguous' },
  ])('rejects ambiguous or incomplete protocol envelopes: %j', (message) => {
    expect(rpcMessageSchema.safeParse(message).success).toBe(false);
  });
  it('accepts an explicit null result', () => {
    expect(rpcMessageSchema.safeParse({ jsonrpc: '2.0', id: 1, result: null }).success).toBe(true);
  });
  it.each([
    { jsonrpc: '2.0', id: 1, method: 'shutdown' },
    { jsonrpc: '2.0', method: 'exit' },
  ])('accepts parameterless requests and notifications: %j', (message) => {
    expect(rpcMessageSchema.parse(message)).toEqual(message);
  });
  it('correlates out-of-order responses and dispatches notifications independently', async () => {
    const endpoint = new TestEndpoint();
    const connection = new MessageConnection(endpoint);
    const notification = vi.fn();
    connection.onNotification(notification);
    const first = connection.request('first', {});
    const second = connection.request('second', {});
    endpoint.deliver({ jsonrpc: '2.0', id: 2, result: 'second result' });
    endpoint.deliver({ jsonrpc: '2.0', method: 'progress', params: { ready: true } });
    endpoint.deliver({ jsonrpc: '2.0', id: 1, result: 'first result' });
    expect(await first).toBe('first result');
    expect(await second).toBe('second result');
    expect(notification).toHaveBeenCalledWith('progress', { ready: true });
    connection.dispose();
  });
  it('propagates cancellation, ignores late responses, and settles pending work on disposal', async () => {
    const endpoint = new TestEndpoint();
    const connection = new MessageConnection(endpoint);
    const cancellation = new AbortController();
    const cancelled = connection.request('cancelled', {}, cancellation.signal);
    const rejected = expect(cancelled).rejects.toMatchObject({ name: 'AbortError' });
    cancellation.abort();
    await rejected;
    expect(endpoint.sent.at(-1)).toMatchObject({ method: '$/cancelRequest', params: { id: 1 } });
    endpoint.deliver({ jsonrpc: '2.0', id: 1, result: 'late response' });
    const pending = connection.request('pending', {});
    const closed = expect(pending).rejects.toThrow('closed');
    connection.dispose();
    await closed;
    expect(endpoint.terminated).toBe(true);
    await expect(connection.request('new request', {})).rejects.toThrow('closed');
  });
  it('times out unresponsive requests and reports malformed worker messages as failures', async () => {
    vi.useFakeTimers();
    const endpoint = new TestEndpoint();
    const connection = new MessageConnection(endpoint, 100);
    const timedOut = expect(connection.request('slow', {})).rejects.toThrow('timed out');
    await vi.advanceTimersByTimeAsync(101);
    await timedOut;
    const failure = vi.fn();
    connection.onFailure(failure);
    const pending = expect(connection.request('bad response', {})).rejects.toThrow('invalid JSON-RPC');
    endpoint.deliver({ not: 'a protocol message' });
    await pending;
    expect(failure).toHaveBeenCalledOnce();
    expect(endpoint.terminated).toBe(true);
  });
  it('answers unsupported server requests instead of leaving the server waiting', () => {
    const endpoint = new TestEndpoint();
    const connection = new MessageConnection(endpoint);
    endpoint.deliver({ jsonrpc: '2.0', id: 'server-request', method: 'unsupported', params: {} });
    expect(endpoint.sent.at(-1)).toMatchObject({ id: 'server-request', error: { code: -32601 } });
    connection.dispose();
  });
});
