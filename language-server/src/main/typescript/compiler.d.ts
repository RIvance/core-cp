declare module "*scalajs/main.js" {
  export interface CompilerIssue {
    readonly phase: string;
    readonly message: string;
    readonly fileName: string | null;
    readonly line: number | null;
    readonly column: number | null;
    readonly endLine: number | null;
    readonly endColumn: number | null;
  }

  export class CpAnalysis {
    check(files: readonly { readonly fileName: string; readonly source: string }[]): CompilerIssue | null;
  }
}
