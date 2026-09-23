import { DiagnosticSeverity, TextDocuments, TextDocumentSyncKind } from "vscode-languageserver";
import type { Connection, Diagnostic } from "vscode-languageserver";
import { TextDocument } from "vscode-languageserver-textdocument";
import { CpAnalysis } from "../../../scalajs/main.js";

/** The server owns a virtual source workspace. It never reads client files from disk. */
export function serveCp(connection: Connection): void {
  const documents = new TextDocuments({
    create: TextDocument.create,
    update: (document: TextDocument, changes, version) => version > document.version
      ? TextDocument.update(document, changes, version) : document
  });
  const compiler = new CpAnalysis();
  let pending: ReturnType<typeof setTimeout> | undefined;
  let stopped = false;

  connection.onInitialize(() => ({
    capabilities: { textDocumentSync: TextDocumentSyncKind.Full, positionEncoding: "utf-16" },
    serverInfo: { name: "CP language server", version: "0.1.0" }
  }));

  function analyze(): void {
    pending = undefined;
    const sources = documents.all().filter((document) => new URL(document.uri).pathname.endsWith(".cp"));
    const files = sources.map((document) => ({
      fileName: decodeURIComponent(new URL(document.uri).pathname),
      source: document.getText()
    }));
    const issue = files.length > 0 ? compiler.check(files) : null;
    const diagnostics = new Map<string, Diagnostic[]>();
    if (issue) {
      const index = files.findIndex((file) => file.fileName === issue.fileName);
      const document = sources[index];
      if (document && issue.line !== null && issue.column !== null &&
          issue.endLine !== null && issue.endColumn !== null) {
        diagnostics.set(document.uri, [{
          severity: DiagnosticSeverity.Error,
          source: `CP ${issue.phase}`,
          message: issue.message,
          range: {
            start: { line: issue.line - 1, character: issue.column - 1 },
            end: { line: issue.endLine - 1, character: issue.endColumn - 1 }
          }
        }]);
      } else {
        // Module-level errors without source spans stay unlocated instead of marking an arbitrary token.
        connection.console.error(issue.message);
      }
    }
    for (const document of documents.all()) {
      void connection.sendDiagnostics({
        uri: document.uri,
        version: document.version,
        diagnostics: diagnostics.get(document.uri) ?? []
      });
    }
  }

  function scheduleAnalysis(): void {
    if (stopped) return;
    if (pending !== undefined) clearTimeout(pending);
    pending = setTimeout(analyze, 0);
  }

  documents.onDidChangeContent(scheduleAnalysis);
  documents.onDidClose(({ document }) => {
    void connection.sendDiagnostics({ uri: document.uri, diagnostics: [] });
    scheduleAnalysis();
  });
  connection.onShutdown(() => {
    stopped = true;
    if (pending !== undefined) clearTimeout(pending);
  });
  documents.listen(connection);
  connection.listen();
}
