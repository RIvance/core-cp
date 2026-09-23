import type { ExecutionInput } from "@language-playground/ide/api";
import { expect, test } from "vitest";
import { CpTrieWorkbench } from "scala-core";
import { executeWorkspace } from "./cp-runtime";

function workspace(files: Record<string, string>, entry = "Main.cp"): ExecutionInput {
  const uri = (path: string) => `file:///test/${path.split("/").map(encodeURIComponent).join("/")}`;
  return {
    documents: Object.entries(files).map(([path, text]) => ({ uri: uri(path), languageId: "cp", version: 1, text })),
    entryDocumentUri: uri(entry), entryPoint: "main", stdin: ""
  };
}

test("runs the selected module with imports and exposes the elaborated term", () => {
  const result = executeWorkspace(new CpTrieWorkbench(), workspace({
    "Main.cp": "def main = false",
    "app/Application.cp": "module App\nimport Shared::*\ndef main: Int = twice(21)",
    "lib/Library.cp": "module Shared\ndef twice(value: Int): Int = value + value",
    "notes.txt": "This workspace also contains a text file."
  }, "app/Application.cp"));
  expect(result.status).toBe("success");
  expect(result.value).toBe("42");
  expect(result.artifacts).toEqual([{ name: "App::main.fiobs", mediaType: "text/plain", content: expect.any(String) }]);
  expect(result.artifacts[0]?.content).toContain("twice");
});

test("returns imported-file diagnostics with their URI and UTF-16 range", () => {
  const line = 'def value = let text = "🌍" in 1 ,, 2;';
  const result = executeWorkspace(new CpTrieWorkbench(), workspace({
    "Main.cp": "import Library::*\ndef main = value",
    "資料/Library.cp": line
  }));
  expect(result.status).toBe("error");
  expect(result.diagnostics[0]?.location).toEqual({
    uri: "file:///test/%E8%B3%87%E6%96%99/Library.cp",
    range: {
      start: { line: 0, character: line.indexOf("1 ,, 2") },
      end: { line: 0, character: line.indexOf("1 ,, 2") + 6 }
    }
  });
});

test("reports unsupported execution inputs explicitly", () => {
  const input = workspace({ "Main.cp": "def main = 42" });
  for (const variant of [{ ...input, entryPoint: "other" }, { ...input, stdin: "text" }]) {
    expect(executeWorkspace(new CpTrieWorkbench(), variant).status).toBe("error");
  }
});

test("direct evaluation failures preserve compilation artifacts", () => {
  const result = executeWorkspace(new CpTrieWorkbench(), workspace({ "Main.cp": "def main: Int = 1 / 0" }));
  expect(result.status).toBe("error");
  expect(result.diagnostics[0]?.message).toContain("division by zero");
  expect(result.artifacts).toHaveLength(1);
});
