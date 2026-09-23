import { afterEach, expect, test, vi } from "vitest";
import type { ExecutionInput } from "@language-playground/ide/api";
import type { MessageEndpoint } from "@language-playground/ide/transport";
import { TrieSession } from "./trie-session";

class TestWorker implements MessageEndpoint {
  terminated = false;
  private requestId: number | undefined;
  private readonly listeners = new Set<(event: MessageEvent) => void>();
  postMessage(message: { id?: number }): void { this.requestId = message.id; }
  addEventListener(type: "message", listener: (event: MessageEvent) => void): void;
  addEventListener(type: "error", listener: (event: ErrorEvent) => void): void;
  addEventListener(type: string, listener: ((event: MessageEvent) => void) | ((event: ErrorEvent) => void)): void {
    if (type === "message") this.listeners.add(listener as (event: MessageEvent) => void);
  }
  removeEventListener(type: "message", listener: (event: MessageEvent) => void): void;
  removeEventListener(type: "error", listener: (event: ErrorEvent) => void): void;
  removeEventListener(type: string, listener: ((event: MessageEvent) => void) | ((event: ErrorEvent) => void)): void {
    if (type === "message") this.listeners.delete(listener as (event: MessageEvent) => void);
  }
  terminate(): void { this.terminated = true; }
  succeed(entryPoint: string): void {
    const result = {
      ok: true, entryPoint, step: 1, complete: false,
      snapshot: { root: 1, nodes: [{ id: 1, responses: [], routes: [], terminations: [] }] }
    };
    for (const listener of this.listeners) {
      listener(new MessageEvent("message", { data: { jsonrpc: "2.0", id: this.requestId, result } }));
    }
  }
}

const input: ExecutionInput = {
  documents: [{ uri: "file:///Main.cp", languageId: "cp", version: 1, text: "def main = 42" }],
  entryDocumentUri: "file:///Main.cp", entryPoint: "main", stdin: ""
};

afterEach(() => vi.useRealTimers());

test("a replacement run terminates the old worker and ignores its result", async () => {
  const workers: TestWorker[] = [];
  const session = new TrieSession(() => {
    const worker = new TestWorker();
    workers.push(worker);
    return worker;
  });
  const first = session.load(input, new AbortController().signal);
  const second = session.load(input, new AbortController().signal);
  expect(workers[0]!.terminated).toBe(true);
  workers[0]!.succeed("Old::main");
  workers[1]!.succeed("New::main");
  await Promise.all([first, second]);
  expect(session.getSnapshot()).toMatchObject({ kind: "ready", result: { entryPoint: "New::main" } });
  session.dispose();
  expect(workers[1]!.terminated).toBe(true);
});

test("aborting compilation terminates synchronous worker work", async () => {
  const worker = new TestWorker();
  const session = new TrieSession(() => worker);
  const cancellation = new AbortController();
  const loading = session.load(input, cancellation.signal);
  cancellation.abort();
  await loading;
  expect(worker.terminated).toBe(true);
  expect(session.getSnapshot()).toMatchObject({ kind: "empty", message: expect.stringContaining("stopped") });
  session.dispose();
});

test("a timed out trie request terminates its worker and reports the failure", async () => {
  vi.useFakeTimers();
  const worker = new TestWorker();
  const session = new TrieSession(() => worker);
  const loading = session.load(input, new AbortController().signal);
  await vi.advanceTimersByTimeAsync(30_000);
  await loading;
  expect(worker.terminated).toBe(true);
  expect(session.getSnapshot()).toMatchObject({ kind: "failed", message: expect.stringContaining("timed out") });
  session.dispose();
});
