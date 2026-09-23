import type { DocumentEdits, SourceDocument } from '../core/contracts';
import { monaco } from './monaco';

/** @internal Look up live editor models and the corresponding source snapshots using the same URI identity. */
export interface WorkspaceEditContext {
  /** False for protected documents. Every target is checked before any part of an edit can be applied. */
  canEdit(uri: string): boolean;
  model(uri: string): monaco.editor.ITextModel | undefined;
  document(uri: string): SourceDocument | undefined;
}
/** No edits are applied by conversion. A rejected result contains no partial edit transaction. */
export type WorkspaceEditResult =
  | { readonly kind: 'ready'; readonly edit: monaco.languages.WorkspaceEdit }
  | { readonly kind: 'rejected'; readonly error: WorkspaceEditError };

/** A rejected editor transaction retains its failure category and target URI. */
export class WorkspaceEditError extends Error {
  constructor(
    readonly code: 'unknown-document' | 'read-only' | 'stale-document' | 'invalid-range' | 'overlapping-edits',
    readonly uri: string,
    message: string,
  ) {
    super(message);
    this.name = 'WorkspaceEditError';
  }
}
function rejected(code: WorkspaceEditError['code'], uri: string, message: string): WorkspaceEditResult {
  return { kind: 'rejected', error: new WorkspaceEditError(code, uri, message) };
}

/**
 * @internal Validate all targets, source versions, coordinates, and overlaps before constructing a Monaco transaction.
 * Source versions are checked against context.document; emitted versionId values come from context.model.
 * Multiple insertions at the same position retain their input order. A failure rejects the complete operation.
 */
export function projectWorkspaceEdits(
  changes: readonly DocumentEdits[],
  context: WorkspaceEditContext,
): WorkspaceEditResult {
  const edits: monaco.languages.IWorkspaceTextEdit[] = [];
  const ranges = new Map<string, monaco.Range[]>();
  for (const change of changes) {
    const model = context.model(change.uri);
    const document = context.document(change.uri);
    if (!model || model.isDisposed() || !document)
      return rejected('unknown-document', change.uri, 'This action requires a file outside the current workspace.');
    if (change.edits.length && !context.canEdit(change.uri))
      return rejected('read-only', change.uri, 'This action changes a read-only file.');
    if (change.version !== undefined && change.version !== document.version)
      return rejected(
        'stale-document',
        change.uri,
        'The source changed after this action was computed. Request the action again.',
      );
    for (const edit of change.edits) {
      const { start, end } = edit.range;
      if (
        [start.line, start.character, end.line, end.character].some(
          (value) => !Number.isSafeInteger(value) || value < 0,
        )
      )
        return rejected('invalid-range', change.uri, 'The language service supplied an invalid edit range.');
      const range = new monaco.Range(start.line + 1, start.character + 1, end.line + 1, end.character + 1);
      if (
        !range.equalsRange(model.validateRange(range)) ||
        end.line < start.line ||
        (end.line === start.line && end.character < start.character)
      )
        return rejected(
          'invalid-range',
          change.uri,
          'The language service supplied an edit outside its source document.',
        );
      const previous = ranges.get(change.uri) ?? [];
      previous.push(range);
      ranges.set(change.uri, previous);
      edits.push({ resource: model.uri, versionId: model.getVersionId(), textEdit: { range, text: edit.text } });
    }
  }
  for (const [uri, documentRanges] of ranges) {
    documentRanges.sort((left, right) => monaco.Range.compareRangesUsingStarts(left, right));
    for (let index = 1; index < documentRanges.length; index += 1) {
      const previous = documentRanges[index - 1]!;
      const current = documentRanges[index]!;
      if (current.getStartPosition().isBefore(previous.getEndPosition()))
        return rejected('overlapping-edits', uri, 'The language service supplied overlapping edits.');
    }
  }
  return { kind: 'ready', edit: { edits } };
}
