import { describe, expect, it } from "vitest";
import type { ElementDefinition } from "cytoscape";
import type { TrieSnapshot } from "./model";
import { initialDiff } from "./trie-diff";
import { buildGraphElements } from "./trie-graph";

describe("FiTrie graph projection", () => {
  it("compacts singleton response and termination wrappers into their contents", () => {
    const snapshot: TrieSnapshot = {
      root: 0,
      nodes: [
        {
          id: 0,
          responses: [{
            kind: "filter",
            label: "filter ⟨int⟩",
            receiver: 1,
            selectedRootKeys: "⟨int⟩",
            selectedRootKeyCount: 1
          }],
          routes: [],
          terminations: []
        },
        {
          id: 1,
          responses: [],
          routes: [],
          terminations: [{ key: "int", value: "42" }]
        }
      ]
    };

    const elements = buildGraphElements(snapshot, initialDiff(snapshot));
    const nodes = elements.filter((element) => element.group === "nodes");
    const edges = elements.filter((element) => element.group === "edges");

    expect(nodes.map(elementId)).toEqual(["root", "trie:0", "trie:1"]);
    expect(nodes[0]?.classes).toContain("root-node");
    expect(nodes[1]?.classes).toContain("response-node");
    expect(nodes[1]?.classes).toContain("response-filter");
    expect(nodes[1]?.classes).not.toContain("trie-node");
    expect(nodes[1]?.data).toMatchObject({ label: "▷ ⟨int⟩" });
    expect(nodes[2]?.classes).toContain("termination-node");
    expect(nodes[2]?.classes).not.toContain("trie-node");
    expect(edges).toHaveLength(2);
    expect(edges[1]?.data).toMatchObject({
      source: "trie:0",
      target: "trie:1",
      label: "receiver"
    });
  });

  it("retains a trie wrapper when the node owns more than one component", () => {
    const snapshot: TrieSnapshot = {
      root: 2,
      nodes: [{
        id: 2,
        responses: [{
          kind: "global",
          label: "global Main::answer",
          notation: "global Main::answer"
        }],
        routes: [],
        terminations: [{ key: "int", value: "42" }]
      }]
    };

    const elements = buildGraphElements(snapshot, initialDiff(snapshot));
    const nodes = elements.filter((element) => element.group === "nodes");

    expect(nodes.map(elementId)).toEqual([
      "root",
      "trie:2",
      "response:2:0",
      "termination:2:int"
    ]);
    expect(nodes[1]?.classes).toContain("trie-node");
    expect(nodes[1]?.data).toMatchObject({ label: "1 pending\nint ↦ 42" });
  });

  it("uses concise labels and distinct classes for response families", () => {
    const snapshot: TrieSnapshot = {
      root: 0,
      nodes: [
        {
          id: 0,
          responses: [
            {
              kind: "filter",
              label: "filter ⟨int, bool, string⟩",
              receiver: 1,
              selectedRootKeys: "⟨int, bool, string⟩",
              selectedRootKeyCount: 3
            },
            {
              kind: "index",
              label: "index",
              receiver: 1,
              requests: [
                { kind: "application", argument: 1 },
                { kind: "application", argument: 2 }
              ]
            },
            {
              kind: "primitive-operation",
              label: "primitive *",
              operator: "*",
              left: 1,
              right: 1
            },
            {
              kind: "conditional",
              label: "if / then / else",
              condition: 1,
              whenTrue: 1,
              whenFalse: 1
            }
          ],
          routes: [],
          terminations: []
        },
        {
          id: 1,
          responses: [],
          routes: [],
          terminations: [{ key: "int", value: "42" }]
        },
        {
          id: 2,
          responses: [],
          routes: [],
          terminations: [
            { key: "int", value: "20" },
            { key: "bool", value: "true" }
          ]
        }
      ]
    };

    const elements = buildGraphElements(snapshot, initialDiff(snapshot));
    const byId = new Map(elements.map((element) => [elementId(element), element]));

    expect(byId.get("trie:0")?.data).toMatchObject({ label: "4 pending" });
    expect(byId.get("response:0:0")?.data).toMatchObject({ label: "▷ ⟨…⟩" });
    expect(byId.get("response:0:0")?.classes).toContain("response-filter");
    expect(byId.get("response:0:1")?.data).toMatchObject({
      label: "◁ ωᵃᵖᵖ[{int ↦ 42}] · ωᵃᵖᵖ[…]"
    });
    expect(byId.get("response:0:1")?.classes).toContain("response-index");
    expect(byId.get("response:0:2")?.data).toMatchObject({ label: "*" });
    expect(byId.get("response:0:2")?.classes).toContain("response-primitive");
    expect(byId.get("response:0:3")?.classes).toContain("response-primitive");
  });
});

function elementId(element: ElementDefinition): string | undefined {
  return element.data?.id;
}
