import type { Diagnostic } from './contracts';
import type { PlaygroundState } from './controller';

/** Live analysis and execution may report the same issue through their shared diagnostic contract. */
export function currentDiagnostics(state: PlaygroundState): Diagnostic[] {
  const diagnostics = [
    ...state.documents.flatMap((document) => document.diagnostics),
    ...(state.execution.kind === 'finished' && state.execution.revision === state.revision
      ? state.execution.result.diagnostics
      : []),
  ].sort(compareDiagnostics);
  return diagnostics.filter((diagnostic, index) => {
    const previous = diagnostics[index - 1];
    return !previous || compareDiagnostics(previous, diagnostic) !== 0;
  });
}

function compare<T extends string | number>(left: T | undefined, right: T | undefined): number {
  if (left === right) return 0;
  if (left === undefined) return -1;
  if (right === undefined) return 1;
  if (typeof left !== typeof right) return typeof left === 'number' ? -1 : 1;
  return left < right ? -1 : 1;
}
function compareDiagnostics(left: Diagnostic, right: Diagnostic): number {
  return (
    compare(left.location?.uri, right.location?.uri) ||
    compare(left.location?.range.start.line, right.location?.range.start.line) ||
    compare(left.location?.range.start.character, right.location?.range.start.character) ||
    compare(left.location?.range.end.line, right.location?.range.end.line) ||
    compare(left.location?.range.end.character, right.location?.range.end.character) ||
    compare(left.severity, right.severity) ||
    compare(left.message, right.message) ||
    compare(left.source, right.source) ||
    compare(left.code, right.code)
  );
}
