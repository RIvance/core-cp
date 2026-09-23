import type { Diagnostic, ExecutionInput, ExecutionResult } from "@language-playground/ide/api";
import type { CpTrieWorkbench } from "scala-core";
import type { CompilationResult, WorkbenchFailure } from "./model";

function sourcePath(uri: string): string {
  return decodeURIComponent(new URL(uri).pathname);
}

/** The workspace supplies source identities; CP still owns module naming and import resolution. */
export function compileWorkspace(workbench: CpTrieWorkbench, input: ExecutionInput): CompilationResult {
  if (input.entryPoint !== "main") {
    return inputFailure("CP runs the `main` definition of the entry file. Set the entry point to main.");
  }
  if (input.stdin !== "") {
    return inputFailure("CP does not read standard input. Clear the Input panel before running.");
  }
  const files = input.documents
    .map((document) => ({ fileName: sourcePath(document.uri), source: document.text }))
    .filter((file) => file.fileName.endsWith(".cp"));
  return workbench.compile(files, sourcePath(input.entryDocumentUri));
}

export function compilationDiagnostic(failure: WorkbenchFailure, input: ExecutionInput): Diagnostic {
  const error = failure.error;
  const document = input.documents.find((document) => sourcePath(document.uri) === error.fileName);
  const location = document && error.line !== null && error.column !== null &&
    error.endLine !== null && error.endColumn !== null ? {
      uri: document.uri,
      range: {
        start: { line: error.line - 1, character: error.column - 1 },
        end: { line: error.endLine - 1, character: error.endColumn - 1 }
      }
    } : undefined;
  return { severity: "error", source: `CP ${error.phase}`, message: error.message, location };
}

export function executeWorkspace(workbench: CpTrieWorkbench, input: ExecutionInput): ExecutionResult {
  const compiled = compileWorkspace(workbench, input);
  if (!compiled.ok) {
    return { status: "error", diagnostics: [compilationDiagnostic(compiled, input)], artifacts: [] };
  }
  const artifacts = [{
    name: `${compiled.entryPoint}.fiobs`,
    mediaType: "text/plain",
    content: compiled.elaboratedMainTerm
  }];
  const evaluated = workbench.evaluate();
  return evaluated.ok
    ? { status: "success", value: evaluated.value, diagnostics: [], artifacts }
    : { status: "error", diagnostics: [{ severity: "error", source: "Fᵢᵒᵇˢ", message: evaluated.message }], artifacts };
}

function inputFailure(message: string): WorkbenchFailure {
  return {
    ok: false,
    error: { phase: "input", message, fileName: null, line: null, column: null, endLine: null, endColumn: null }
  };
}
