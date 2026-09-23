import type { PlaygroundTheme } from "@language-playground/ide/themes";
import cytoscape, {
  type Core,
  type ElementDefinition,
  type EventObject,
  type Layouts,
  type StylesheetJson
} from "cytoscape";
import type { TrieDiff } from "./trie-diff";
import type {
  TrieNode,
  TrieRequest,
  TrieResponse,
  TrieSnapshot,
  TrieTarget
} from "./model";
import {
  renderPaperResponse,
  renderPaperTermination,
  renderPaperTrie
} from "./trie-rendering";

export interface GraphSelection {
  readonly title: string;
  readonly notation: string;
}

interface GraphPosition {
  readonly x: number;
  readonly y: number;
}

export class TrieGraph {
  private readonly graph: Core;
  private snapshot: TrieSnapshot | null = null;
  private activeLayout: Layouts | null = null;

  constructor(
    container: HTMLElement,
    onSelection: (selection: GraphSelection) => void,
    theme: PlaygroundTheme
  ) {
    this.graph = cytoscape({
      container,
      minZoom: 0.18,
      maxZoom: 2.6,
      wheelSensitivity: 0.16,
      style: graphStyles(theme)
    });

    this.graph.on("tap", "node", (event: EventObject) => {
      const selection = this.selection(event.target.data() as SelectionData);
      if (selection !== null) {
        onSelection(selection);
      }
    });
  }

  render(snapshot: TrieSnapshot, diff: TrieDiff, animate: boolean): void {
    this.finishActiveLayout();
    const positions = new Map(
      this.graph.nodes().map((node) => [node.id(), { ...node.position() }])
    );
    this.snapshot = snapshot;
    this.graph.elements().remove();
    this.graph.add(buildGraphElements(snapshot, diff));
    this.restoreTransitionStartPositions(positions);
    this.layout(animate, positions.size === 0, positions.size === 0);
  }

  setTheme(theme: PlaygroundTheme): void {
    this.graph.style(graphStyles(theme));
  }

  resize(): void { this.graph.resize(); }

  dispose(): void {
    this.finishActiveLayout();
    this.graph.destroy();
  }

  fit(): void {
    this.graph.animate({ fit: { eles: this.graph.elements(), padding: 54 }, duration: 260 });
  }

  clear(): void {
    this.finishActiveLayout();
    this.snapshot = null;
    this.graph.elements().remove();
  }

  relayout(): void {
    this.finishActiveLayout();
    this.layout(true, true, true);
  }

  private layout(animate: boolean, randomize: boolean, fit: boolean): void {
    if (this.snapshot === null || this.graph.nodes().empty()) {
      return;
    }
    const startPositions = new Map(
      this.graph.nodes().map((node) => [node.id(), { ...node.position() }])
    );
    this.graph.layout({
      name: "cose",
      animate: false,
      randomize,
      fit: !animate && fit,
      padding: 58,
      nodeRepulsion: () => 7200,
      idealEdgeLength: () => 120,
      edgeElasticity: () => 90,
      gravity: 0.22,
      numIter: 700
    }).run();
    if (!animate) {
      return;
    }

    const finalPositions = Object.fromEntries(
      this.graph.nodes().map((node) => [node.id(), { ...node.position() }])
    );
    for (const node of this.graph.nodes()) {
      node.position(startPositions.get(node.id())!);
    }
    const layout = this.graph.layout({
      name: "preset",
      positions: finalPositions,
      animate: true,
      animationDuration: 520,
      animationEasing: "ease-in-out",
      fit,
      padding: 58
    });
    this.activeLayout = layout;
    layout.one("layoutstop", () => {
      if (this.activeLayout === layout) {
        this.activeLayout = null;
      }
    });
    layout.run();
  }

  private finishActiveLayout(): void {
    const layout = this.activeLayout;
    this.activeLayout = null;
    this.graph.nodes().stop(true, false);
    this.graph.stop(true, false);
    layout?.stop();
  }

  private restoreTransitionStartPositions(
    previousPositions: ReadonlyMap<string, GraphPosition>
  ): void {
    const positionedNodeIds = new Set<string>();
    for (const node of this.graph.nodes()) {
      const previousPosition = previousPositions.get(node.id());
      if (previousPosition !== undefined) {
        node.position(previousPosition);
        positionedNodeIds.add(node.id());
      }
    }

    if (positionedNodeIds.size === 0) {
      return;
    }

    const unresolved = new Set(
      this.graph.nodes()
        .filter((node) => !positionedNodeIds.has(node.id()))
        .map((node) => node.id())
    );
    while (unresolved.size > 0) {
      let positionedInPass = false;
      for (const nodeId of unresolved) {
        const node = this.graph.getElementById(nodeId);
        const neighborPositions: GraphPosition[] = [];
        for (const neighbor of node.neighborhood("node").nodes()) {
          if (positionedNodeIds.has(neighbor.id())) {
            neighborPositions.push(neighbor.position());
          }
        }
        if (neighborPositions.length === 0) {
          continue;
        }
        node.position(offsetPosition(averagePosition(neighborPositions), nodeId));
        positionedNodeIds.add(nodeId);
        unresolved.delete(nodeId);
        positionedInPass = true;
      }
      if (!positionedInPass) {
        break;
      }
    }

    const fallbackPosition = averagePosition(
      [...positionedNodeIds].map((nodeId) => this.graph.getElementById(nodeId).position())
    );
    for (const nodeId of unresolved) {
      this.graph.getElementById(nodeId).position(offsetPosition(fallbackPosition, nodeId));
    }
  }

  private selection(data: SelectionData): GraphSelection | null {
    if (this.snapshot === null) {
      return null;
    }

    const owner = this.snapshot.nodes.find((node) => node.id === data.ownerId);
    if (owner === undefined) {
      return null;
    }

    switch (data.selectionKind) {
      case "trie":
        return { title: data.title, notation: renderPaperTrie(this.snapshot, owner.id) };
      case "response": {
        const response = owner.responses[data.entryIndex];
        return response === undefined
          ? null
          : {
              title: data.title,
              notation: renderPaperResponse(this.snapshot, owner.id, response)
            };
      }
      case "termination": {
        const termination = owner.terminations[data.entryIndex];
        return termination === undefined
          ? null
          : { title: data.title, notation: renderPaperTermination(termination) };
      }
    }
  }
}

function averagePosition(positions: readonly GraphPosition[]): GraphPosition {
  const sum = positions.reduce(
    (current, position) => ({
      x: current.x + position.x,
      y: current.y + position.y
    }),
    { x: 0, y: 0 }
  );
  return { x: sum.x / positions.length, y: sum.y / positions.length };
}

function offsetPosition(position: GraphPosition, nodeId: string): GraphPosition {
  let hash = 0;
  for (const character of nodeId) {
    hash = (hash * 31 + character.codePointAt(0)!) >>> 0;
  }
  const angle = (hash % 360) * Math.PI / 180;
  const offset = 24;
  return {
    x: position.x + Math.cos(angle) * offset,
    y: position.y + Math.sin(angle) * offset
  };
}

interface SelectionData {
  readonly selectionKind: "trie" | "response" | "termination";
  readonly ownerId: number;
  readonly entryIndex: number;
  readonly title: string;
}

export function buildGraphElements(
  snapshot: TrieSnapshot,
  diff: TrieDiff
): ElementDefinition[] {
  const result: ElementDefinition[] = [];
  const changed = diff.changedNodeIds;

  result.push(rootElement(snapshot.root));
  result.push({
    group: "edges",
    data: {
      id: "root-edge",
      source: "root",
      target: trieId(snapshot.root),
      label: ""
    },
    classes: "root-edge"
  });

  for (const node of snapshot.nodes) {
    const presentation = nodePresentation(node);
    result.push(representativeElement(node, presentation, changed, snapshot));

    switch (presentation.kind) {
      case "response":
        addResponseTargetEdges(
          result,
          node,
          presentation.response,
          presentation.responseIndex,
          trieId(node.id),
          changed
        );
        break;
      case "termination":
        break;
      case "trie":
        addTrieContents(result, node, changed, snapshot);
        break;
    }
  }

  return result;
}

function rootElement(rootId: number): ElementDefinition {
  return {
    group: "nodes",
    data: selectionData("root", "root", "Root trie", "trie", rootId, 0),
    classes: "root-node"
  };
}

type NodePresentation =
  | { readonly kind: "trie" }
  | {
      readonly kind: "response";
      readonly response: TrieResponse;
      readonly responseIndex: number;
    }
  | {
      readonly kind: "termination";
      readonly terminationIndex: number;
    };

function nodePresentation(node: TrieNode): NodePresentation {
  if (node.responses.length === 1 && node.routes.length === 0 && node.terminations.length === 0) {
    return { kind: "response", response: node.responses[0]!, responseIndex: 0 };
  }
  if (node.responses.length === 0 && node.routes.length === 0 && node.terminations.length === 1) {
    return { kind: "termination", terminationIndex: 0 };
  }
  return { kind: "trie" };
}

function representativeElement(
  node: TrieNode,
  presentation: NodePresentation,
  changed: ReadonlySet<number>,
  snapshot: TrieSnapshot
): ElementDefinition {
  const changedClass = changed.has(node.id) ? "changed" : "";

  switch (presentation.kind) {
    case "trie":
      return {
        group: "nodes",
        data: selectionData(
          trieId(node.id),
          nodeLabel(node),
          `Trie node N${node.id}`,
          "trie",
          node.id,
          0
        ),
        classes: classes("trie-node", changedClass)
      };
    case "response":
      return {
        group: "nodes",
        data: selectionData(
          trieId(node.id),
          responseNodeLabel(presentation.response, snapshot),
          presentation.response.kind,
          "response",
          node.id,
          presentation.responseIndex
        ),
        classes: classes(
          "response-node",
          responseKindClass(presentation.response),
          "compacted-node",
          changedClass
        )
      };
    case "termination": {
      const termination = node.terminations[presentation.terminationIndex]!;
      return {
        group: "nodes",
        data: selectionData(
          trieId(node.id),
          `${termination.key} ↦ ${termination.value}`,
          `${termination.key} termination`,
          "termination",
          node.id,
          presentation.terminationIndex
        ),
        classes: classes("termination-node", "compacted-node", changedClass)
      };
    }
  }
}

function addTrieContents(
  result: ElementDefinition[],
  node: TrieNode,
  changed: ReadonlySet<number>,
  snapshot: TrieSnapshot
): void {
  const changedClass = changed.has(node.id) ? "changed" : "";

  node.routes.forEach((route, routeIndex) => {
    result.push({
      group: "edges",
      data: {
        id: `route:${node.id}:${routeIndex}`,
        source: trieId(node.id),
        target: trieId(route.target),
        label: route.label
      },
      classes: classes("route-edge", changedClass)
    });
  });

  node.responses.forEach((response, responseIndex) => {
    const responseId = `response:${node.id}:${responseIndex}`;
    result.push({
      group: "nodes",
      data: selectionData(
        responseId,
        responseNodeLabel(response, snapshot),
        response.kind,
        "response",
        node.id,
        responseIndex
      ),
      classes: classes("response-node", responseKindClass(response), changedClass)
    });
    result.push({
      group: "edges",
      data: {
        id: `response-owner:${node.id}:${responseIndex}`,
        source: trieId(node.id),
        target: responseId,
        label: "C"
      },
      classes: classes("response-edge", changedClass)
    });
    addResponseTargetEdges(result, node, response, responseIndex, responseId, changed);
  });

  node.terminations.forEach((termination, terminationIndex) => {
    const terminationId = `termination:${node.id}:${termination.key}`;
    result.push({
      group: "nodes",
      data: selectionData(
        terminationId,
        `${termination.key} ↦ ${termination.value}`,
        `${termination.key} termination`,
        "termination",
        node.id,
        terminationIndex
      ),
      classes: classes("termination-node", changedClass)
    });
    result.push({
      group: "edges",
      data: {
        id: `termination-owner:${node.id}:${termination.key}`,
        source: trieId(node.id),
        target: terminationId,
        label: "Θ"
      },
      classes: changedClass
    });
  });
}

function addResponseTargetEdges(
  result: ElementDefinition[],
  node: TrieNode,
  response: TrieResponse,
  responseIndex: number,
  source: string,
  changed: ReadonlySet<number>
): void {
  responseTargets(response).forEach((target, targetIndex) => {
    result.push({
      group: "edges",
      data: {
        id: `response-target:${node.id}:${responseIndex}:${targetIndex}`,
        source,
        target: trieId(target.target),
        label: target.role
      },
      classes: classes(
        target.role === "ref" ? "reference-edge" : "response-edge",
        changed.has(node.id) ? "changed" : ""
      )
    });
  });
}

function selectionData(
  id: string,
  label: string,
  title: string,
  selectionKind: SelectionData["selectionKind"],
  ownerId: number,
  entryIndex: number
): SelectionData & { readonly id: string; readonly label: string } {
  return { id, label, title, selectionKind, ownerId, entryIndex };
}

function trieId(id: number): string {
  return `trie:${id}`;
}

function nodeLabel(node: TrieNode): string {
  const components: string[] = [];
  if (node.responses.length > 0) {
    components.push(`${node.responses.length} pending`);
  }
  if (node.routes.length > 0) {
    components.push(compactLabels(node.routes.map((route) => route.notation)));
  }
  if (node.terminations.length > 0) {
    components.push(compactLabels(
      node.terminations.map((termination) => `${termination.key} ↦ ${termination.value}`)
    ));
  }
  return components.length === 0 ? "empty trie" : components.join("\n");
}

function responseNodeLabel(response: TrieResponse, snapshot: TrieSnapshot): string {
  switch (response.kind) {
    case "local-variable":
    case "global":
      return response.notation;
    case "structural-reference":
      return "ref ↩";
    case "index":
      return `◁ ${response.requests
        .map((request) => requestLabel(request, snapshot))
        .join(" · ")}`;
    case "filter":
      return `▷ ${response.selectedRootKeyCount !== null && response.selectedRootKeyCount > 2
        ? "⟨…⟩"
        : response.selectedRootKeys}`;
    case "primitive-operation":
      return response.operator;
    case "conditional":
      return "if · then · else";
  }
}

function requestLabel(request: TrieRequest, snapshot: TrieSnapshot): string {
  switch (request.kind) {
    case "unfold":
      return "ωᵘⁿᶠᵒˡᵈ";
    case "application":
      return `ωᵃᵖᵖ[${inlineTrieLabel(snapshot, request.argument)}]`;
    case "type-application":
      return `ωᵗᵃᵖᵖ[${compactText(request.pathInterface, 18)}]`;
    case "projection":
      return `ωᵖʳᵒʲ_${compactText(request.label, 18)}`;
  }
}

function inlineTrieLabel(snapshot: TrieSnapshot, nodeId: number): string {
  const notation = renderPaperTrie(snapshot, nodeId).replace(/\s+/g, " ");
  return compactText(notation, 20);
}

function compactText(value: string, maximumLength: number): string {
  return value.length > maximumLength ? "…" : value;
}

function compactLabels(values: readonly string[]): string {
  const visible = values.slice(0, 2);
  return values.length > visible.length ? `${visible.join(" · ")} · …` : visible.join(" · ");
}

function responseKindClass(response: TrieResponse): string {
  switch (response.kind) {
    case "index":
      return "response-index";
    case "filter":
      return "response-filter";
    case "primitive-operation":
    case "conditional":
      return "response-primitive";
    case "local-variable":
    case "global":
    case "structural-reference":
      return "response-atomic";
  }
}

function responseTargets(response: TrieResponse): readonly TrieTarget[] {
  switch (response.kind) {
    case "local-variable":
    case "global":
      return [];
    case "structural-reference":
      return [{ role: "ref", target: response.target }];
    case "index":
      return [
        { role: "receiver", target: response.receiver },
        ...response.requests.flatMap((request): readonly TrieTarget[] =>
          request.kind === "application"
            ? [{ role: "argument", target: request.argument }]
            : []
        )
      ];
    case "filter":
      return [{ role: "receiver", target: response.receiver }];
    case "primitive-operation":
      return [
        { role: "left", target: response.left },
        { role: "right", target: response.right }
      ];
    case "conditional":
      return [
        { role: "condition", target: response.condition },
        { role: "true", target: response.whenTrue },
        { role: "false", target: response.whenFalse }
      ];
  }
}

function classes(...values: string[]): string {
  return values.filter(Boolean).join(" ");
}

function graphStyles({ colors, syntax }: PlaygroundTheme): StylesheetJson {
  return [
    {
      selector: "node",
      style: {
        label: "data(label)",
        color: colors.text,
        "font-family": "IBM Plex Mono, JetBrains Mono, monospace",
        "font-size": 11,
        "text-wrap": "wrap",
        "text-max-width": "150px",
        "text-valign": "center",
        "text-halign": "center",
        "background-color": colors.raised,
        "border-color": colors.border,
        "border-width": 1.5,
        width: 82,
        height: 48
      }
    },
    {
      selector: "node.trie-node",
      style: {
        shape: "round-rectangle",
        "background-color": colors.raised,
        "border-color": colors.accent,
        "border-width": 2,
        width: 92,
        height: 58,
        "font-weight": 600
      }
    },
    {
      selector: "node.root-node",
      style: {
        shape: "hexagon",
        "background-color": colors.accent,
        "border-color": colors.accent,
        "border-width": 3,
        color: colors.onAccent,
        width: 68,
        height: 54,
        "font-size": 10,
        "font-weight": 700,
        "underlay-color": colors.accent,
        "underlay-opacity": 0.08,
        "underlay-padding": 9
      }
    },
    {
      selector: "node.response-node",
      style: {
        shape: "diamond",
        "background-color": colors.raised,
        "border-color": colors.muted,
        width: 104,
        height: 68,
        "font-size": 9
      }
    },
    {
      selector: "node.response-index",
      style: {
        "background-color": colors.raised,
        "border-color": syntax.function
      }
    },
    {
      selector: "node.response-filter",
      style: {
        "background-color": colors.raised,
        "border-color": syntax.type
      }
    },
    {
      selector: "node.response-primitive",
      style: {
        "background-color": colors.raised,
        "border-color": syntax.keyword
      }
    },
    {
      selector: "node.termination-node",
      style: {
        shape: "ellipse",
        "background-color": colors.raised,
        "border-color": colors.success,
        color: colors.text,
        width: 86,
        height: 46
      }
    },
    {
      selector: "node.changed",
      style: {
        "border-color": colors.warning,
        "border-width": 4,
        "overlay-color": colors.warning,
        "overlay-opacity": 0.12,
        "overlay-padding": 12
      }
    },
    {
      selector: "edge",
      style: {
        width: 1.5,
        "line-color": colors.muted,
        "target-arrow-color": colors.muted,
        "target-arrow-shape": "triangle",
        "curve-style": "bezier",
        label: "data(label)",
        color: colors.muted,
        "font-size": 9,
        "font-family": "IBM Plex Mono, JetBrains Mono, monospace",
        "text-background-color": colors.surface,
        "text-background-opacity": 0.88,
        "text-background-padding": "2px",
        "text-rotation": "autorotate"
      }
    },
    {
      selector: "edge.route-edge",
      style: { "line-color": colors.accent, "target-arrow-color": colors.accent, width: 2.2 }
    },
    {
      selector: "edge.root-edge",
      style: {
        "line-color": colors.accent,
        "target-arrow-color": colors.accent,
        width: 2.5
      }
    },
    {
      selector: "edge.response-edge",
      style: { "line-color": syntax.function, "target-arrow-color": syntax.function }
    },
    {
      selector: "edge.reference-edge",
      style: {
        "line-style": "dashed",
        "line-color": syntax.keyword,
        "target-arrow-color": syntax.keyword,
        "curve-style": "unbundled-bezier",
        "control-point-distances": 70
      }
    },
    {
      selector: "edge.changed",
      style: {
        "line-color": colors.warning,
        "target-arrow-color": colors.warning,
        width: 3.5
      }
    },
    {
      selector: ":selected",
      style: { "overlay-color": colors.accent, "overlay-opacity": 0.15, "overlay-padding": 8 }
    }
  ];
}
