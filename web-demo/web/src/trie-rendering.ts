import type {
  TrieRequest,
  TrieResponse,
  TrieSnapshot,
  TrieTermination
} from "./model";

const maximumLineWidth = 46;
const indentationWidth = 2;
type IndexResponse = Extract<TrieResponse, { readonly kind: "index" }>;

/** Renders one reachable runtime node using the notation used by the FiTrie paper. */
export function renderPaperTrie(snapshot: TrieSnapshot, nodeId: number): string {
  const renderer = new PaperTrieRenderer(snapshot);
  return renderDocument(renderer.trieDocument(nodeId, new Set()), maximumLineWidth);
}

/** Renders a selected response while retaining all of its reachable trie operands. */
export function renderPaperResponse(
  snapshot: TrieSnapshot,
  ownerId: number,
  response: TrieResponse
): string {
  const renderer = new PaperTrieRenderer(snapshot);
  return renderDocument(
    renderer.responseDocument(response, new Set([ownerId])),
    maximumLineWidth
  );
}

export function renderPaperTermination(termination: TrieTermination): string {
  return `{${termination.key} ↦ ${termination.value}}`;
}

class PaperTrieRenderer {
  private readonly nodes: ReadonlyMap<number, TrieSnapshot["nodes"][number]>;

  constructor(snapshot: TrieSnapshot) {
    this.nodes = new Map(snapshot.nodes.map((node) => [node.id, node]));
  }

  trieDocument(nodeId: number, ancestors: ReadonlySet<number>): Document {
    if (ancestors.has(nodeId)) {
      return text(`N${nodeId}`);
    }

    const node = this.nodes.get(nodeId);
    if (node === undefined) {
      return text(`N${nodeId}`);
    }

    const nestedAncestors = new Set(ancestors).add(nodeId);
    const entries = [
      ...node.responses.map((response) => this.responseDocument(response, nestedAncestors)),
      ...node.routes.map((route) => concatenate(
        text(`${route.notation} ↦ `),
        this.trieDocument(route.target, nestedAncestors)
      )),
      ...node.terminations.map((termination) =>
        text(`${termination.key} ↦ ${termination.value}`)
      )
    ];

    if (entries.length === 0) {
      return text("{}");
    }
    return delimited(
      "{",
      entries,
      "}",
      empty,
      entries.length > 1 ? hardLine : line
    );
  }

  responseDocument(response: TrieResponse, ancestors: ReadonlySet<number>): Document {
    switch (response.kind) {
      case "local-variable":
      case "global":
        return text(response.notation);
      case "structural-reference":
        return text(`ref N${response.target}`);
      case "index":
        return this.indexChainDocument(response, ancestors);
      case "filter":
        return trailingOperand(
          this.trieDocument(response.receiver, ancestors),
          "▷",
          text(response.selectedRootKeys)
        );
      case "primitive-operation":
        return delimited("(", [
          this.trieDocument(response.left, ancestors),
          text(response.operator),
          this.trieDocument(response.right, ancestors)
        ], ")");
      case "conditional":
        return group(concatenate(
          text("if"),
          indent(indentationWidth, concatenate(line, this.trieDocument(response.condition, ancestors))),
          line,
          text("then"),
          indent(indentationWidth, concatenate(line, this.trieDocument(response.whenTrue, ancestors))),
          line,
          text("else"),
          indent(indentationWidth, concatenate(line, this.trieDocument(response.whenFalse, ancestors)))
        ));
    }
  }

  private indexChainDocument(
    response: IndexResponse,
    ancestors: ReadonlySet<number>
  ): Document {
    let receiver = response.receiver;
    const requestSets: (readonly TrieRequest[])[] = [response.requests];
    const omittedWrapperIds = new Set<number>();

    while (!ancestors.has(receiver) && !omittedWrapperIds.has(receiver)) {
      const nested = this.singletonIndexResponse(receiver);
      if (nested === undefined) {
        break;
      }
      omittedWrapperIds.add(receiver);
      receiver = nested.receiver;
      requestSets.unshift(nested.requests);
    }

    const nestedAncestors = new Set(ancestors);
    omittedWrapperIds.forEach((nodeId) => nestedAncestors.add(nodeId));
    return requestSets.reduce(
      (document, requests) => trailingOperand(
        document,
        "◁",
        this.requestSetDocument(requests, nestedAncestors)
      ),
      this.trieDocument(receiver, nestedAncestors)
    );
  }

  private singletonIndexResponse(nodeId: number): IndexResponse | undefined {
    const node = this.nodes.get(nodeId);
    if (
      node === undefined ||
      node.responses.length !== 1 ||
      node.routes.length !== 0 ||
      node.terminations.length !== 0
    ) {
      return undefined;
    }
    const response = node.responses[0]!;
    return response.kind === "index" ? response : undefined;
  }

  private requestSetDocument(
    requests: readonly TrieRequest[],
    ancestors: ReadonlySet<number>
  ): Document {
    return delimited(
      "⟨",
      requests.map((request) => this.requestDocument(request, ancestors)),
      "⟩",
      text(",")
    );
  }

  private requestDocument(request: TrieRequest, ancestors: ReadonlySet<number>): Document {
    switch (request.kind) {
      case "application":
        return group(concatenate(
          text("app["),
          indent(
            indentationWidth,
            concatenate(softLine, this.trieDocument(request.argument, ancestors))
          ),
          softLine,
          text("]")
        ));
      case "type-application":
        return text(`tapp[${request.pathInterface}]`);
      case "projection":
        return text(`proj_${request.label}`);
    }
  }
}

function trailingOperand(left: Document, operator: string, right: Document): Document {
  return group(concatenate(
    left,
    line,
    text(`${operator} `),
    right
  ));
}

function delimited(
  opening: string,
  entries: readonly Document[],
  closing: string,
  separator: Document = empty,
  entryBreak: Document = line,
  forceBreak = false
): Document {
  const separatedEntries = entries.map((entry, index) =>
    index === entries.length - 1 ? entry : concatenate(entry, separator)
  );
  return group(concatenate(
    text(opening),
    indent(
      indentationWidth,
      concatenate(forceBreak ? hardLine : softLine, join(separatedEntries, entryBreak))
    ),
    forceBreak ? hardLine : softLine,
    text(closing)
  ));
}

type Document =
  | { readonly kind: "empty" }
  | { readonly kind: "text"; readonly value: string }
  | { readonly kind: "break"; readonly flattened: string | null }
  | { readonly kind: "concatenation"; readonly left: Document; readonly right: Document }
  | { readonly kind: "indentation"; readonly width: number; readonly document: Document }
  | { readonly kind: "group"; readonly document: Document };

const empty: Document = { kind: "empty" };
const line: Document = { kind: "break", flattened: " " };
const softLine: Document = { kind: "break", flattened: "" };
const hardLine: Document = { kind: "break", flattened: null };

function text(value: string): Document {
  return value === "" ? empty : { kind: "text", value };
}

function concatenate(...documents: readonly Document[]): Document {
  return documents.reduce((combined, document) => {
    if (combined.kind === "empty") {
      return document;
    }
    if (document.kind === "empty") {
      return combined;
    }
    return { kind: "concatenation", left: combined, right: document };
  }, empty);
}

function join(documents: readonly Document[], separator: Document): Document {
  return documents.reduce((combined, document, index) =>
    index === 0 ? document : concatenate(combined, separator, document), empty);
}

function indent(width: number, document: Document): Document {
  return document.kind === "empty" ? empty : { kind: "indentation", width, document };
}

function group(document: Document): Document {
  return document.kind === "empty" ? empty : { kind: "group", document };
}

interface PendingDocument {
  readonly indentation: number;
  readonly mode: "flat" | "broken";
  readonly document: Document;
}

function renderDocument(document: Document, lineWidth: number): string {
  let result = "";
  let column = 0;
  let pending: PendingDocument[] = [{ indentation: 0, mode: "broken", document }];

  while (pending.length > 0) {
    const current = pending[0]!;
    const remaining = pending.slice(1);
    switch (current.document.kind) {
      case "empty":
        pending = remaining;
        break;
      case "text":
        result += current.document.value;
        column += current.document.value.length;
        pending = remaining;
        break;
      case "break":
        if (current.mode === "flat" && current.document.flattened !== null) {
          result += current.document.flattened;
          column += current.document.flattened.length;
        } else {
          result += `\n${" ".repeat(current.indentation)}`;
          column = current.indentation;
        }
        pending = remaining;
        break;
      case "concatenation":
        pending = [
          { ...current, document: current.document.left },
          { ...current, document: current.document.right },
          ...remaining
        ];
        break;
      case "indentation":
        pending = [{
          ...current,
          indentation: current.indentation + current.document.width,
          document: current.document.document
        }, ...remaining];
        break;
      case "group": {
        const flattened = { ...current, mode: "flat" as const, document: current.document.document };
        pending = fits(lineWidth - column, [flattened, ...remaining])
          ? [flattened, ...remaining]
          : [{ ...flattened, mode: "broken" }, ...remaining];
        break;
      }
    }
  }

  return result;
}

function fits(remainingWidth: number, pending: readonly PendingDocument[]): boolean {
  let width = remainingWidth;
  const work = [...pending];
  while (width >= 0 && work.length > 0) {
    const current = work.shift()!;
    switch (current.document.kind) {
      case "empty":
        break;
      case "text":
        width -= current.document.value.length;
        break;
      case "break":
        if (current.mode === "broken") {
          return true;
        }
        if (current.document.flattened === null) {
          return false;
        }
        width -= current.document.flattened.length;
        break;
      case "concatenation":
        work.unshift(
          { ...current, document: current.document.left },
          { ...current, document: current.document.right }
        );
        break;
      case "indentation":
        work.unshift({ ...current, document: current.document.document });
        break;
      case "group":
        work.unshift({ ...current, mode: "flat", document: current.document.document });
        break;
    }
  }
  return width >= 0;
}
