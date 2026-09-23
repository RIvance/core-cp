import { BrowserMessageReader, BrowserMessageWriter, createConnection } from "vscode-languageserver/browser";
import { serveCp } from "./server.js";

declare const self: DedicatedWorkerGlobalScope;

/** Start LSP in the current dedicated worker, independently of the client that created it. */
export function startBrowserLanguageServer(): void {
  serveCp(createConnection(new BrowserMessageReader(self), new BrowserMessageWriter(self)));
}
