import { describe, expect, it } from "vitest";
import type { TrieNode, TrieSnapshot } from "./model";
import { diffSnapshots } from "./trie-diff";

const node = (id: number, value?: string): TrieNode => ({
  id,
  responses: [],
  routes: [],
  terminations: value === undefined ? [] : [{ key: "int", value }]
});

const snapshot = (root: number, nodes: readonly TrieNode[]): TrieSnapshot => ({ root, nodes });

describe("diffSnapshots", () => {
  it("preserves stable runtime node identities and highlights only new structure", () => {
    const result = diffSnapshots(
      snapshot(0, [node(0), node(1, "retained")]),
      snapshot(2, [node(1, "retained"), node(2, "42")])
    );

    expect([...result.addedNodeIds]).toEqual([2]);
    expect([...result.changedNodeIds]).toEqual([2]);
    expect([...result.removedNodeIds]).toEqual([0]);
  });

  it("detects a changed node even when its stable identity remains", () => {
    const result = diffSnapshots(
      snapshot(0, [node(0, "1")]),
      snapshot(0, [node(0, "2")])
    );

    expect([...result.addedNodeIds]).toEqual([]);
    expect([...result.changedNodeIds]).toEqual([0]);
    expect([...result.removedNodeIds]).toEqual([]);
  });
});
