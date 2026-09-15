import type { TrieNode, TrieSnapshot } from "./model";

export interface TrieDiff {
  readonly addedNodeIds: ReadonlySet<number>;
  readonly changedNodeIds: ReadonlySet<number>;
  readonly removedNodeIds: ReadonlySet<number>;
}

export function diffSnapshots(previous: TrieSnapshot, next: TrieSnapshot): TrieDiff {
  const previousNodes = indexNodes(previous.nodes);
  const nextNodes = indexNodes(next.nodes);
  const addedNodeIds = new Set<number>();
  const changedNodeIds = new Set<number>();
  const removedNodeIds = new Set<number>();

  for (const [id, node] of nextNodes) {
    const previousNode = previousNodes.get(id);
    if (previousNode === undefined) {
      addedNodeIds.add(id);
      changedNodeIds.add(id);
    } else if (!sameNode(previousNode, node)) {
      changedNodeIds.add(id);
    }
  }

  for (const id of previousNodes.keys()) {
    if (!nextNodes.has(id)) {
      removedNodeIds.add(id);
    }
  }

  return { addedNodeIds, changedNodeIds, removedNodeIds };
}

export function initialDiff(snapshot: TrieSnapshot): TrieDiff {
  const nodeIds = new Set(snapshot.nodes.map((node) => node.id));
  return {
    addedNodeIds: nodeIds,
    changedNodeIds: nodeIds,
    removedNodeIds: new Set()
  };
}

function indexNodes(nodes: readonly TrieNode[]): Map<number, TrieNode> {
  return new Map(nodes.map((node) => [node.id, node]));
}

function sameNode(left: TrieNode, right: TrieNode): boolean {
  return JSON.stringify(left) === JSON.stringify(right);
}
