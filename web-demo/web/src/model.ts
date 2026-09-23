export interface TrieSnapshot {
  readonly root: number;
  readonly nodes: readonly TrieNode[];
}

export interface TrieNode {
  readonly id: number;
  readonly responses: readonly TrieResponse[];
  readonly routes: readonly TrieRoute[];
  readonly terminations: readonly TrieTermination[];
}

interface TrieResponseBase {
  readonly label: string;
}

export type TrieResponse =
  | (TrieResponseBase & {
      readonly kind: "local-variable" | "global";
      readonly notation: string;
    })
  | (TrieResponseBase & {
      readonly kind: "structural-reference";
      readonly target: number;
    })
  | (TrieResponseBase & {
      readonly kind: "index";
      readonly receiver: number;
      readonly requests: readonly TrieRequest[];
    })
  | (TrieResponseBase & {
      readonly kind: "filter";
      readonly receiver: number;
      readonly selectedRootKeys: string;
      readonly selectedRootKeyCount: number | null;
    })
  | (TrieResponseBase & {
      readonly kind: "primitive-operation";
      readonly operator: string;
      readonly left: number;
      readonly right: number;
    })
  | (TrieResponseBase & {
      readonly kind: "conditional";
      readonly condition: number;
      readonly whenTrue: number;
      readonly whenFalse: number;
    });

export type TrieRequest =
  | {
      readonly kind: "unfold";
    }
  | {
      readonly kind: "application";
      readonly argument: number;
    }
  | {
      readonly kind: "type-application";
      readonly pathInterface: string;
    }
  | {
      readonly kind: "projection";
      readonly label: string;
    };

export interface TrieTarget {
  readonly role: "ref" | "receiver" | "argument" | "left" | "right" | "condition" | "true" | "false";
  readonly target: number;
}

export interface TrieRoute {
  readonly label: string;
  readonly notation: string;
  readonly target: number;
}

export interface TrieTermination {
  readonly key: string;
  readonly value: string;
}

export interface CompilationSuccess {
  readonly ok: true;
  readonly entryPoint: string;
  readonly elaboratedMainTerm: string;
}

export interface WorkbenchSuccess {
  readonly ok: true;
  readonly entryPoint: string;
  readonly step: number;
  readonly complete: boolean;
  readonly snapshot: TrieSnapshot;
}

export type FiobsEvaluationResult =
  | { readonly ok: true; readonly value: string }
  | { readonly ok: false; readonly message: string };

export interface WorkbenchFailure {
  readonly ok: false;
  readonly error: {
    readonly phase: string;
    readonly message: string;
    readonly fileName: string | null;
    readonly line: number | null;
    readonly column: number | null;
    readonly endLine: number | null;
    readonly endColumn: number | null;
  };
}

export type WorkbenchResult = WorkbenchSuccess | WorkbenchFailure;
export type CompilationResult = CompilationSuccess | WorkbenchFailure;

export interface BrowserSourceFile {
  readonly fileName: string;
  readonly source: string;
}
