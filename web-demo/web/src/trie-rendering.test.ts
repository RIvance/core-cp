import { describe, expect, it } from "vitest";
import type { TrieNode, TrieSnapshot } from "./model";
import { renderPaperResponse, renderPaperTrie } from "./trie-rendering";

describe("paper-style FiTrie rendering", () => {
  it("renders fold routes and payload-free unfolding requests", () => {
    const snapshot: TrieSnapshot = {
      root: 0,
      nodes: [
        node(0, {
          responses: [{ kind: "index", label: "unfold", receiver: 1, requests: [{ kind: "unfold" }] }]
        }),
        node(1, { routes: [{ label: "κᵘⁿᶠᵒˡᵈ", notation: "unfold", target: 2 }] }),
        node(2, { terminations: [{ key: "int", value: "42" }] })
      ]
    };

    expect(renderPaperTrie(snapshot, snapshot.root)).toBe("{{unfold ↦ {int ↦ 42}} ◁ ⟨unfold⟩}");
  });

  it("includes complete route and response children", () => {
    const snapshot: TrieSnapshot = {
      root: 0,
      nodes: [
        node(0, {
          routes: [{ label: "κᵃᵖᵖ", notation: "appₓ", target: 1 }]
        }),
        node(1, {
          responses: [
            {
              kind: "filter",
              label: "filter ⟨int⟩",
              receiver: 2,
              selectedRootKeys: "⟨int⟩",
              selectedRootKeyCount: 1
            },
            {
              kind: "filter",
              label: "filter ⟨bool⟩",
              receiver: 3,
              selectedRootKeys: "⟨bool⟩",
              selectedRootKeyCount: 1
            }
          ]
        }),
        node(2, { terminations: [{ key: "int", value: "1" }] }),
        node(3, {
          responses: [{ kind: "local-variable", label: "x", notation: "x" }]
        })
      ]
    };

    expect(renderPaperTrie(snapshot, snapshot.root)).toBe(`{
  appₓ ↦ {
    {int ↦ 1} ▷ ⟨int⟩
    {x} ▷ ⟨bool⟩
  }
}`);
  });

  it("renders a cyclic child as an explicit node reference", () => {
    const snapshot: TrieSnapshot = {
      root: 4,
      nodes: [node(4, {
        routes: [{ label: "κᵖʳᵒʲ_self", notation: "proj_self", target: 4 }]
      })]
    };

    expect(renderPaperTrie(snapshot, snapshot.root)).toBe("{proj_self ↦ N4}");
  });

  it("indents nested application requests at their delimiters", () => {
    const snapshot: TrieSnapshot = {
      root: 0,
      nodes: [
        node(0, { responses: [indexResponse(1, 2, "42")] }),
        node(1, { responses: [indexResponse(3, 4, "20")] }),
        node(2, { responses: [filterResponse(6)] }),
        node(3, {
          responses: [{
            kind: "global",
            label: "global Main::maximum",
            notation: "global Main::maximum"
          }]
        }),
        node(4, { responses: [filterResponse(5)] }),
        node(5, { terminations: [{ key: "int", value: "20" }] }),
        node(6, { terminations: [{ key: "int", value: "42" }] })
      ]
    };

    expect(renderPaperTrie(snapshot, snapshot.root)).toBe(`{
  {global Main::maximum}
  ◁ ⟨app[{{int ↦ 20} ▷ ⟨int⟩}]⟩
  ◁ ⟨app[{{int ↦ 42} ▷ ⟨int⟩}]⟩
}`);
  });

  it("retains an index receiver wrapper that owns another component", () => {
    const snapshot: TrieSnapshot = {
      root: 0,
      nodes: [
        node(0, { responses: [indexResponse(1, 2, "42")] }),
        node(1, {
          responses: [indexResponse(3, 4, "20")],
          terminations: [{ key: "bool", value: "true" }]
        }),
        node(2, { terminations: [{ key: "int", value: "42" }] }),
        node(3, {
          responses: [{
            kind: "global",
            label: "global Main::maximum",
            notation: "global Main::maximum"
          }]
        }),
        node(4, { terminations: [{ key: "int", value: "20" }] })
      ]
    };

    expect(renderPaperTrie(snapshot, snapshot.root)).toContain("bool ↦ true");
  });

  it("aligns both binary operands with the operator", () => {
    const snapshot: TrieSnapshot = {
      root: 0,
      nodes: [
        node(0),
        node(1, { terminations: [
          { key: "int", value: "20" },
          { key: "bool", value: "true" }
        ] }),
        node(2, { terminations: [
          { key: "int", value: "42" },
          { key: "bool", value: "false" }
        ] })
      ]
    };
    const response: TrieNode["responses"][number] = {
      kind: "primitive-operation",
      label: "primitive *",
      operator: "*",
      left: 1,
      right: 2
    };

    expect(renderPaperResponse(snapshot, 0, response)).toBe(`(
  {
    int ↦ 20
    bool ↦ true
  }
  *
  {
    int ↦ 42
    bool ↦ false
  }
)`);
  });
});

function indexResponse(receiver: number, argument: number, value: string): TrieNode["responses"][number] {
  return {
    kind: "index",
    label: `index ⟨app[int ↦ ${value}]⟩`,
    receiver,
    requests: [{ kind: "application", argument }]
  };
}

function filterResponse(receiver: number): TrieNode["responses"][number] {
  return {
    kind: "filter",
    label: "filter ⟨int⟩",
    receiver,
    selectedRootKeys: "⟨int⟩",
    selectedRootKeyCount: 1
  };
}

function node(id: number, overrides: Partial<TrieNode> = {}): TrieNode {
  return {
    id,
    responses: [],
    routes: [],
    terminations: [],
    ...overrides
  };
}
