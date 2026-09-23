import { getTheme, onDidChangeTheme } from "@language-playground/ide/themes";
import { diffSnapshots, initialDiff } from "./trie-diff";
import { TrieGraph } from "./trie-graph";
import type { WorkbenchSuccess } from "./model";
import type { TrieSession } from "./trie-session";

/** The graph is a companion to the full IDE, using only its public theme and execution contracts. */
export class TrieExplorer {
  private readonly graph: TrieGraph;
  private readonly release: (() => void)[] = [];
  private previous: WorkbenchSuccess | null = null;
  private readonly actions = new AbortController();

  constructor(private readonly container: HTMLElement, private readonly session: TrieSession) {
    container.innerHTML = `
      <header class="trie-toolbar">
        <div><h2>FiTrie explorer</h2><span id="trie-entry">No run yet</span></div>
        <div class="trie-actions">
          <button id="trie-restart" type="button">Restart</button>
          <button id="trie-step" type="button" title="Next reduction (F10)">Step</button>
          <button id="trie-stop" type="button" hidden>Stop trie</button>
        </div>
      </header>
      <p class="trie-snapshot-note">Snapshot from the last Run. Run again to use edited source.</p>
      <div class="trie-stage">
        <div id="trie-graph" aria-label="Interactive FiTrie graph"></div>
        <p id="trie-empty" role="status"></p>
        <aside id="trie-inspector" hidden aria-label="Trie node inspector">
          <button id="trie-inspector-close" type="button" aria-label="Close trie inspector">×</button>
          <h3 id="trie-inspector-title"></h3><pre id="trie-inspector-content"></pre>
        </aside>
      </div>
      <div class="trie-view-controls">
        <span class="trie-legend" aria-label="Graph legend">
          <span class="trie-root-key">⬡ root</span><span class="trie-node-key">▢ trie</span>
          <span class="trie-filter-key">◇ filter</span><span class="trie-index-key">◇ index</span>
          <span class="trie-primitive-key">◇ primitive</span><span class="trie-value-key">○ value</span>
        </span>
        <button id="trie-fit" type="button">Fit</button>
        <button id="trie-layout" type="button">Layout</button>
      </div>
      <footer class="trie-status" role="status" aria-live="polite">
        <span id="trie-status-message"></span><span id="trie-step-number">Step 0</span>
        <span id="trie-counts"></span>
      </footer>`;
    this.graph = new TrieGraph(this.element("trie-graph"), (selection) => {
      this.element("trie-inspector-title").textContent = selection.title;
      this.element("trie-inspector-content").textContent = selection.notation;
      this.element("trie-inspector").hidden = false;
    }, getTheme());
    const eventOptions = { signal: this.actions.signal };
    this.element("trie-step").addEventListener("click", () => { void session.step(); }, eventOptions);
    this.element("trie-restart").addEventListener("click", () => { void session.restart(); }, eventOptions);
    this.element("trie-stop").addEventListener("click", () => session.cancel(), eventOptions);
    this.element("trie-fit").addEventListener("click", () => this.graph.fit(), eventOptions);
    this.element("trie-layout").addEventListener("click", () => this.graph.relayout(), eventOptions);
    this.element("trie-inspector-close").addEventListener("click", () => {
      this.element("trie-inspector").hidden = true;
    }, eventOptions);
    window.addEventListener("keydown", (event) => {
      if (event.key === "F10" && !event.altKey && !event.ctrlKey && !event.metaKey && !event.shiftKey) {
        event.preventDefault();
        void session.step();
      }
    }, eventOptions);
    const observer = new ResizeObserver(() => this.graph.resize());
    observer.observe(this.element("trie-graph"));
    this.release.push(() => observer.disconnect(), session.subscribe(() => this.render()));
    this.release.push(onDidChangeTheme(() => this.applyTheme()));
    this.applyTheme();
    this.render();
  }

  dispose(): void {
    this.actions.abort();
    for (const release of this.release) release();
    this.graph.dispose();
    this.container.replaceChildren();
  }

  private render(): void {
    const state = this.session.getSnapshot();
    const result = "result" in state ? state.result : null;
    const busy = state.kind === "loading" || state.kind === "stepping";
    this.button("trie-step").disabled = state.kind !== "ready" || state.result.complete;
    this.button("trie-restart").disabled = state.kind !== "ready";
    this.element("trie-stop").hidden = !busy;
    this.button("trie-fit").disabled = result === null;
    this.button("trie-layout").disabled = result === null;
    const empty = this.element("trie-empty");
    empty.hidden = result !== null;
    let message = "";
    switch (state.kind) {
      case "empty": message = state.message; break;
      case "loading": message = "Compiling the workspace for FiTrie…"; break;
      case "stepping": message = "Reducing…"; break;
      case "failed": message = state.message; break;
      case "ready": message = state.result.complete ? "Normal form" : "Ready to step"; break;
    }
    empty.textContent = message;
    this.element("trie-status-message").textContent = message;
    this.container.classList.toggle("trie-failed", state.kind === "failed");
    if (result && result !== this.previous) {
      if (state.kind === "ready" && state.operation === "restart") {
        this.graph.clear();
        this.previous = null;
      }
      const diff = this.previous
        ? diffSnapshots(this.previous.snapshot, result.snapshot) : initialDiff(result.snapshot);
      this.graph.render(result.snapshot, diff, this.previous !== null);
      this.element("trie-inspector").hidden = true;
      this.element("trie-entry").textContent = result.entryPoint;
      this.element("trie-step-number").textContent = `Step ${result.step}`;
      const responses = result.snapshot.nodes.reduce((sum, node) => sum + node.responses.length, 0);
      this.element("trie-counts").textContent = `${result.snapshot.nodes.length} nodes · ${responses} responses`;
      if (this.previous && !result.complete) {
        this.element("trie-status-message").textContent =
          `${diff.changedNodeIds.size} changed · ${diff.removedNodeIds.size} retired`;
      }
    } else if (!result) {
      this.graph.clear();
      this.element("trie-inspector").hidden = true;
      this.element("trie-entry").textContent = "No compiled trie";
      this.element("trie-step-number").textContent = "Step 0";
      this.element("trie-counts").textContent = "";
    }
    this.previous = result;
  }

  private applyTheme(): void {
    const theme = getTheme();
    this.container.style.colorScheme = theme.colorScheme;
    for (const [role, color] of Object.entries(theme.colors)) this.container.style.setProperty(`--trie-${role}`, color);
    for (const [role, color] of Object.entries(theme.syntax)) {
      this.container.style.setProperty(`--trie-syntax-${role}`, color);
    }
    this.graph.setTheme(theme);
  }

  private element(id: string): HTMLElement { return this.container.querySelector<HTMLElement>(`#${id}`)!; }
  private button(id: string): HTMLButtonElement { return this.container.querySelector<HTMLButtonElement>(`#${id}`)!; }
}
