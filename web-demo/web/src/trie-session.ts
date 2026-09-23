import type { ExecutionInput } from "@language-playground/ide/api";
import { MessageConnection, type MessageEndpoint } from "@language-playground/ide/transport";
import type { WorkbenchResult, WorkbenchSuccess } from "./model";

export type TrieSessionState =
  | { readonly kind: "empty"; readonly message: string }
  | { readonly kind: "loading" }
  | { readonly kind: "ready"; readonly operation: "start" | "step" | "restart"; readonly result: WorkbenchSuccess }
  | { readonly kind: "stepping"; readonly result: WorkbenchSuccess }
  | { readonly kind: "failed"; readonly message: string; readonly result: WorkbenchSuccess | null };

/** Owns one run's interactive evaluator. A new run replaces it; editor changes do not rewrite that snapshot. */
export class TrieSession {
  private connection: MessageConnection | undefined;
  private state: TrieSessionState = { kind: "empty", message: "Run a CP program to explore its trie." };
  private readonly listeners = new Set<() => void>();
  private disposed = false;

  constructor(private readonly createWorker: () => MessageEndpoint) {}

  getSnapshot(): TrieSessionState { return this.state; }

  subscribe(listener: () => void): () => void {
    this.listeners.add(listener);
    return () => { this.listeners.delete(listener); };
  }

  async load(input: ExecutionInput, signal: AbortSignal): Promise<void> {
    if (this.disposed || signal.aborted) return;
    this.connection?.dispose();
    this.connection = undefined;
    this.publish({ kind: "loading" });
    let connection: MessageConnection;
    try {
      connection = new MessageConnection(this.createWorker());
      this.connection = connection;
      connection.onFailure((error) => {
        if (this.connection === connection) {
          const result = "result" in this.state ? this.state.result : null;
          this.fail(connection, error.message, result);
        }
      });
    } catch (error) {
      this.publish({ kind: "failed", message: errorMessage(error), result: null });
      return;
    }
    const stop = () => { if (this.connection === connection) this.cancel(); };
    signal.addEventListener("abort", stop, { once: true });
    try {
      await this.request(connection, "start", input, null);
    } finally {
      signal.removeEventListener("abort", stop);
    }
  }

  async step(): Promise<void> {
    if (this.state.kind !== "ready" || this.state.result.complete || !this.connection) return;
    const previous = this.state.result;
    this.publish({ kind: "stepping", result: previous });
    await this.request(this.connection, "step", null, previous);
  }

  async restart(): Promise<void> {
    if (this.state.kind !== "ready" || !this.connection) return;
    const previous = this.state.result;
    this.publish({ kind: "stepping", result: previous });
    await this.request(this.connection, "restart", null, previous);
  }

  cancel(): void {
    this.connection?.dispose();
    this.connection = undefined;
    this.publish({ kind: "empty", message: "Trie evaluation stopped. Run the program to start again." });
  }

  dispose(): void {
    if (this.disposed) return;
    this.disposed = true;
    this.connection?.dispose();
    this.connection = undefined;
    this.listeners.clear();
  }

  private async request(
    connection: MessageConnection,
    operation: "start" | "step" | "restart",
    params: unknown,
    previous: WorkbenchSuccess | null
  ): Promise<void> {
    try {
      const result = await connection.request<WorkbenchResult>(`trie/${operation}`, params);
      if (this.connection !== connection) return;
      if (result.ok) this.publish({ kind: "ready", operation, result });
      else this.fail(connection, `${result.error.phase}: ${result.error.message}`, previous);
    } catch (error) {
      if (this.connection === connection) this.fail(connection, errorMessage(error), previous);
    }
  }

  private fail(connection: MessageConnection, message: string, result: WorkbenchSuccess | null): void {
    connection.dispose();
    this.connection = undefined;
    this.publish({ kind: "failed", message, result });
  }

  private publish(state: TrieSessionState): void {
    this.state = state;
    for (const listener of this.listeners) listener();
  }
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
