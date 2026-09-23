import type { MessageEndpoint } from '../../src/transport/rpc';
import { rpcMessageSchema, type RpcMessage } from '../../src/transport/protocol';

/** Test transport only: observes and delivers messages through the public endpoint contract. */
export class TestEndpoint implements MessageEndpoint {
  readonly sent: RpcMessage[] = [];
  terminated = false;
  private readonly events = new EventTarget();
  postMessage(message: unknown): void {
    this.sent.push(rpcMessageSchema.parse(message));
  }
  addEventListener(
    type: 'message' | 'error',
    listener: ((event: MessageEvent) => void) | ((event: ErrorEvent) => void),
  ): void {
    this.events.addEventListener(type, listener as EventListener);
  }
  removeEventListener(
    type: 'message' | 'error',
    listener: ((event: MessageEvent) => void) | ((event: ErrorEvent) => void),
  ): void {
    this.events.removeEventListener(type, listener as EventListener);
  }
  deliver(message: unknown): void {
    this.events.dispatchEvent(new MessageEvent('message', { data: message }));
  }
  terminate(): void {
    this.terminated = true;
  }
}
