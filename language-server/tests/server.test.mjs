import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { once } from "node:events";
import test from "node:test";
import { createMessageConnection, StreamMessageReader, StreamMessageWriter } from "vscode-languageserver/node";

async function client(t) {
  const process = spawn(globalThis.process.execPath, ["dist/main/typescript/node.js", "--stdio"], {
    stdio: ["pipe", "pipe", "pipe"]
  });
  let stderr = "";
  process.stderr.setEncoding("utf8").on("data", (chunk) => { stderr += chunk; });
  const connection = createMessageConnection(
    new StreamMessageReader(process.stdout), new StreamMessageWriter(process.stdin)
  );
  const received = [];
  const waiting = new Set();
  connection.onNotification((method, params) => {
    received.push({ method, params });
    for (const notify of waiting) notify();
  });
  connection.listen();
  t.after(() => { connection.dispose(); process.kill(); });
  function notification(method, predicate) {
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        waiting.delete(check);
        reject(new Error(`No matching ${method} notification. ${stderr}`));
      }, 5000);
      function check() {
        const found = received.find((item) => item.method === method && predicate(item.params));
        if (found) { clearTimeout(timer); waiting.delete(check); resolve(found.params); }
      }
      waiting.add(check);
      check();
    });
  }
  const initialized = await connection.sendRequest("initialize", {
    processId: null,
    rootUri: "file:///independent-client/",
    capabilities: { general: { positionEncodings: ["utf-16"] } }
  });
  assert.equal(initialized.capabilities.textDocumentSync, 1);
  assert.equal(initialized.capabilities.positionEncoding, "utf-16");
  assert.equal(initialized.capabilities.hoverProvider, undefined);
  await connection.sendNotification("initialized", {});
  return { connection, notification, process };
}

test("standalone stdio server checks imports, clears diagnostics, and preserves document versions", async (t) => {
  const { connection, notification, process } = await client(t);
  const main = "file:///independent-client/Application.cp";
  const library = "file:///independent-client/%E8%B3%87%E6%96%99/Library.cp";
  const invalidLine = 'def value = let text = "🌍" in 1 ,, 2;';
  await connection.sendNotification("textDocument/didOpen", {
    textDocument: { uri: main, languageId: "cp", version: 1,
      text: "module App\nimport Shared::Library::*\ndef main: Int = value\n" }
  });
  await connection.sendNotification("textDocument/didOpen", {
    textDocument: { uri: library, languageId: "cp", version: 1, text: `module Shared::Library\n${invalidLine}\n` }
  });
  const failure = await notification("textDocument/publishDiagnostics", (params) =>
    params.uri === library && params.version === 1 && params.diagnostics.length === 1);
  assert.match(failure.diagnostics[0].message, /not disjoint/);
  assert.deepEqual(failure.diagnostics[0].range, {
    start: { line: 1, character: invalidLine.indexOf("1 ,, 2") },
    end: { line: 1, character: invalidLine.indexOf("1 ,, 2") + "1 ,, 2".length }
  });
  await connection.sendNotification("textDocument/didChange", {
    textDocument: { uri: library, version: 2 },
    contentChanges: [{ text: "module Shared::Library\ndef value: Int = 42\ndef loop: Int = loop\n" }]
  });
  await notification("textDocument/publishDiagnostics", (params) =>
    params.uri === library && params.version === 2 && params.diagnostics.length === 0);
  // An obsolete edit cannot replace the accepted source snapshot.
  await connection.sendNotification("textDocument/didChange", {
    textDocument: { uri: library, version: 1 }, contentChanges: [{ text: "def broken =" }]
  });
  await connection.sendNotification("textDocument/didChange", {
    textDocument: { uri: main, version: 2 },
    contentChanges: [{ text: "module App\nimport Shared::Library::*\ndef main: Int = value + 1\n" }]
  });
  await notification("textDocument/publishDiagnostics", (params) =>
    params.uri === main && params.version === 2 && params.diagnostics.length === 0);
  await connection.sendNotification("textDocument/didClose", { textDocument: { uri: library } });
  await notification("window/logMessage", (params) => /not available/.test(params.message));
  const exit = once(process, "exit");
  await connection.sendRequest("shutdown");
  await connection.sendNotification("exit");
  assert.deepEqual(await exit, [0, null]);
});

test("parse errors use the source URI and a complete replacement clears the error", async (t) => {
  const { connection, notification } = await client(t);
  const uri = "untitled:///editor/Main.cp";
  await connection.sendNotification("textDocument/didOpen", {
    textDocument: { uri, languageId: "cp", version: 1, text: 'def main = "\\q";' }
  });
  const update = await notification("textDocument/publishDiagnostics", (params) =>
    params.uri === uri && params.diagnostics.length === 1);
  assert.match(update.diagnostics[0].message, /invalid string escape/);
  assert.deepEqual(update.diagnostics[0].range.start, { line: 0, character: 12 });
  await connection.sendNotification("textDocument/didChange", {
    textDocument: { uri, version: 2 }, contentChanges: [{ text: "def main: Int = 42" }]
  });
  await notification("textDocument/publishDiagnostics", (params) =>
    params.uri === uri && params.version === 2 && params.diagnostics.length === 0);
});
