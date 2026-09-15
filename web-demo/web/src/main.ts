import * as monaco from "monaco-editor/editor/editor.main.js";
import EditorWorker from "monaco-editor/editor/editor.worker.js?worker";
import { CpTrieWorkbench } from "scala-core";
import { registerCpLanguage } from "./cp-language";
import { examples } from "./examples";
import type {
  FiobsEvaluationResult,
  TrieSnapshot,
  WorkbenchFailure,
  WorkbenchSuccess
} from "./model";
import { diffSnapshots, initialDiff } from "./trie-diff";
import { TrieGraph } from "./trie-graph";

self.MonacoEnvironment = {
  getWorker: () => new EditorWorker()
};

registerCpLanguage(monaco);

const elements = {
  editor: requiredElement("editor"),
  exampleSelect: requiredElement<HTMLSelectElement>("example-select"),
  compileButton: requiredElement<HTMLButtonElement>("compile-button"),
  stepButton: requiredElement<HTMLButtonElement>("step-button"),
  restartButton: requiredElement<HTMLButtonElement>("restart-button"),
  fitButton: requiredElement<HTMLButtonElement>("fit-button"),
  layoutButton: requiredElement<HTMLButtonElement>("layout-button"),
  diagnostic: requiredElement("diagnostic"),
  diagnosticText: requiredElement("diagnostic-text"),
  compilerOutput: requiredElement("compiler-output"),
  compilerOutputKind: requiredElement("compiler-output-kind"),
  compilerOutputSubject: requiredElement("compiler-output-subject"),
  compilerOutputContent: requiredElement("compiler-output-content"),
  evaluationResult: requiredElement("evaluation-result"),
  evaluationResultKind: requiredElement("evaluation-result-kind"),
  evaluationResultContent: requiredElement("evaluation-result-content"),
  graph: requiredElement("trie-graph"),
  graphEmpty: requiredElement("graph-empty"),
  inspector: requiredElement("inspector"),
  inspectorClose: requiredElement<HTMLButtonElement>("inspector-close"),
  inspectorTitle: requiredElement("inspector-title"),
  inspectorContent: requiredElement("inspector-content"),
  stepNumber: requiredElement("step-number"),
  runtimeMessage: requiredElement("runtime-message"),
  runtimeCounts: requiredElement("runtime-counts")
};

const workbench = new CpTrieWorkbench();
let currentSnapshot: TrieSnapshot | null = null;

const editor = monaco.editor.create(elements.editor, {
  value: examples[0]?.source ?? "def main: Int = 42\n",
  language: "cp",
  theme: "vs-dark",
  automaticLayout: true,
  // Monaco accessibility mode intercepts Ctrl+Arrow in Firefox on Windows (microsoft/monaco-editor#4892).
  accessibilitySupport: "off",
  fontFamily: "IBM Plex Mono, JetBrains Mono, monospace",
  fontSize: 14,
  lineHeight: 22,
  minimap: { enabled: false },
  padding: { top: 18, bottom: 18 },
  scrollBeyondLastLine: false,
  renderLineHighlight: "gutter",
  overviewRulerBorder: false,
  bracketPairColorization: { enabled: true },
  guides: { bracketPairs: true },
  wordSeparators: "`~!@#$%^&*()-=+[{]}\\|;:'\",.<>/?",
  tabSize: 2
});

const graph = new TrieGraph(elements.graph, (selection) => {
  elements.inspectorTitle.textContent = selection.title;
  elements.inspectorContent.textContent = selection.notation;
  elements.inspector.classList.add("visible");
});

for (const [index, example] of examples.entries()) {
  const option = document.createElement("option");
  option.value = String(index);
  option.textContent = example.name;
  elements.exampleSelect.append(option);
}

elements.exampleSelect.addEventListener("change", () => {
  const selected = examples[Number(elements.exampleSelect.value)];
  if (selected !== undefined) {
    editor.setValue(selected.source);
  }
});

editor.onDidChangeModelContent(resetUi);

elements.compileButton.addEventListener("click", compile);
elements.stepButton.addEventListener("click", step);
elements.restartButton.addEventListener("click", restart);
elements.fitButton.addEventListener("click", () => graph.fit());
elements.layoutButton.addEventListener("click", () => graph.relayout());
elements.inspectorClose.addEventListener("click", () => elements.inspector.classList.remove("visible"));

editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.Enter, compile);
window.addEventListener("keydown", (event) => {
  if (event.key === "F10") {
    event.preventDefault();
    if (!elements.stepButton.disabled) {
      step();
    }
  }
});

function compile(): void {
  setBusy(elements.compileButton, true);
  try {
    const result = workbench.compile(editor.getValue(), "Main.cp");
    if (!result.ok) {
      showFailure(result, true);
      return;
    }
    currentSnapshot = result.snapshot;
    graph.render(result.snapshot, initialDiff(result.snapshot), false);
    showElaboratedMain(result);
    showFiobsResult(result.fiobsResult);
    showSuccess(result, result.entryPoint === "" ? "main" : result.entryPoint, "Compiled");
    elements.graphEmpty.classList.add("hidden");
    elements.stepButton.disabled = result.complete;
    elements.restartButton.disabled = false;
    monaco.editor.setModelMarkers(editor.getModel()!, "cp", []);
  } finally {
    setBusy(elements.compileButton, false);
  }
}

function step(): void {
  if (currentSnapshot === null) {
    return;
  }
  setBusy(elements.stepButton, true);
  try {
    const previous = currentSnapshot;
    const result = workbench.step();
    if (!result.ok) {
      showFailure(result, false);
      return;
    }
    const diff = diffSnapshots(previous, result.snapshot);
    currentSnapshot = result.snapshot;
    graph.render(result.snapshot, diff, true);
    const changeSummary = `${diff.changedNodeIds.size} changed · ${diff.removedNodeIds.size} retired`;
    showSuccess(result, changeSummary, result.complete ? "Normal form" : "Reduced");
    elements.stepButton.disabled = result.complete;
  } finally {
    if (!elements.stepButton.disabled) {
      setBusy(elements.stepButton, false);
    } else {
      elements.stepButton.classList.remove("busy");
    }
  }
}

function restart(): void {
  const previous = currentSnapshot;
  const result = workbench.restart();
  if (!result.ok) {
    showFailure(result, false);
    return;
  }
  currentSnapshot = result.snapshot;
  graph.render(
    result.snapshot,
    previous === null ? initialDiff(result.snapshot) : diffSnapshots(previous, result.snapshot),
    true
  );
  showSuccess(result, "Entry restored", "Restarted");
  elements.stepButton.disabled = result.complete;
}

function showSuccess(result: WorkbenchSuccess, detail: string, state: string): void {
  elements.stepNumber.textContent = String(result.step);
  elements.runtimeMessage.textContent = `${state} · ${detail}`;
  const responseCount = result.snapshot.nodes.reduce((sum, node) => sum + node.responses.length, 0);
  elements.runtimeCounts.textContent = `${result.snapshot.nodes.length} trie nodes · ${responseCount} responses`;
  elements.diagnostic.className = "diagnostic success";
  elements.diagnosticText.textContent = result.complete
    ? `Evaluation reached normal form after ${result.step} steps.`
    : `FiTrie state ready at step ${result.step}.`;
}

function showFailure(result: WorkbenchFailure, clearGraph: boolean): void {
  elements.diagnostic.className = "diagnostic error";
  elements.diagnosticText.textContent = `${result.error.phase}: ${result.error.message}`;
  elements.runtimeMessage.textContent = "Compilation or evaluation stopped";
  elements.stepButton.disabled = true;
  elements.restartButton.disabled = clearGraph;
  if (clearGraph) {
    showCompilerFailure(result);
    if (result.fiobsResult === null) {
      showUnavailableResult("Compilation did not produce a Fiobs program to evaluate.");
    } else {
      showFiobsResult(result.fiobsResult);
    }
    currentSnapshot = null;
    graph.clear();
    elements.graphEmpty.classList.remove("hidden");
    elements.runtimeCounts.textContent = "0 nodes · 0 responses";
  }

  const model = editor.getModel();
  if (model !== null && result.error.line !== null && result.error.column !== null) {
    const startLine = clampLine(result.error.line, model);
    const startColumn = clampColumn(result.error.column, startLine, model);
    const requestedEndLine = result.error.endLine ?? startLine;
    const requestedEndColumn = result.error.endColumn ?? startColumn + 1;
    const endLine = Math.max(clampLine(requestedEndLine, model), startLine);
    const endColumn = endLine === startLine
      ? Math.max(clampColumn(requestedEndColumn, endLine, model), startColumn + 1)
      : clampColumn(requestedEndColumn, endLine, model);
    const range = {
      startLineNumber: startLine,
      startColumn,
      endLineNumber: endLine,
      endColumn: Math.min(endColumn, model.getLineMaxColumn(endLine))
    };
    monaco.editor.setModelMarkers(model, "cp", [{
      ...range,
      severity: monaco.MarkerSeverity.Error,
      message: result.error.message
    }]);
    editor.setSelection(range);
    editor.revealRangeInCenter(range);
  }
}

function showElaboratedMain(result: WorkbenchSuccess): void {
  elements.compilerOutput.className = "output-pane compiler-output";
  elements.compilerOutputKind.textContent = "Fᵢᵒᵇˢ term";
  elements.compilerOutputSubject.textContent = result.entryPoint === "" ? "main" : result.entryPoint;
  elements.compilerOutputContent.textContent = result.elaboratedMainTerm;
}

function showFiobsResult(result: FiobsEvaluationResult): void {
  if (result.ok) {
    elements.evaluationResult.className = "output-pane evaluation-result success";
    elements.evaluationResultKind.textContent = "Result";
    elements.evaluationResultContent.textContent = result.value;
  } else {
    elements.evaluationResult.className = "output-pane evaluation-result error";
    elements.evaluationResultKind.textContent = "Evaluation error";
    elements.evaluationResultContent.textContent = result.message;
  }
}

function showUnavailableResult(message: string): void {
  elements.evaluationResult.className = "output-pane evaluation-result error";
  elements.evaluationResultKind.textContent = "Result unavailable";
  elements.evaluationResultContent.textContent = message;
}

function showCompilerFailure(result: WorkbenchFailure): void {
  const location = result.error.line === null || result.error.column === null
    ? ""
    : ` at ${result.error.line}:${result.error.column}`;
  elements.compilerOutput.className = "output-pane compiler-output error";
  elements.compilerOutputKind.textContent = "Compile error";
  elements.compilerOutputSubject.textContent = result.error.phase;
  elements.compilerOutputContent.textContent = `${result.error.phase}${location}\n${result.error.message}`;
}

function resetUi(): void {
  currentSnapshot = null;
  graph.clear();
  elements.stepButton.disabled = true;
  elements.restartButton.disabled = true;
  elements.graphEmpty.classList.remove("hidden");
  elements.inspector.classList.remove("visible");
  elements.compilerOutput.className = "output-pane compiler-output";
  elements.compilerOutputKind.textContent = "Compiler output";
  elements.compilerOutputSubject.textContent = "main";
  elements.compilerOutputContent.textContent = "Compile to inspect the elaborated Fᵢᵒᵇˢ term.";
  elements.evaluationResult.className = "output-pane evaluation-result";
  elements.evaluationResultKind.textContent = "Result";
  elements.evaluationResultContent.textContent = "Compile to evaluate main directly with Fiobs.";
  elements.diagnostic.className = "diagnostic";
  elements.diagnosticText.textContent = "Source changed. Compile to create a new trie.";
  elements.runtimeMessage.textContent = "Waiting for compilation";
  elements.runtimeCounts.textContent = "0 nodes · 0 responses";
  elements.stepNumber.textContent = "0";
  const model = editor.getModel();
  if (model !== null) {
    monaco.editor.setModelMarkers(model, "cp", []);
  }
}

function setBusy(button: HTMLButtonElement, busy: boolean): void {
  button.classList.toggle("busy", busy);
  button.disabled = busy;
}

function clampLine(line: number, model: monaco.editor.ITextModel): number {
  return Math.min(Math.max(line, 1), model.getLineCount());
}

function clampColumn(column: number, line: number, model: monaco.editor.ITextModel): number {
  return Math.min(Math.max(column, 1), model.getLineMaxColumn(line));
}

function requiredElement<T extends HTMLElement = HTMLElement>(id: string): T {
  const element = document.getElementById(id);
  if (element === null) {
    throw new Error(`Missing required element #${id}`);
  }
  return element as T;
}
