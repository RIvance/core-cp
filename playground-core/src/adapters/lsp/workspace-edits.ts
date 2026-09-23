import type * as Lsp from 'vscode-languageserver-protocol';
import type { DocumentEdits } from '../../core/contracts';

export type WorkspaceEditConversion =
  | { readonly kind: 'supported'; readonly changes: readonly DocumentEdits[] }
  | { readonly kind: 'unsupported'; readonly reason: string };

/** The client accepts ordinary text edits. Unsupported operations reject the entire transaction. */
export function convertWorkspaceEdit(edit: Lsp.WorkspaceEdit): WorkspaceEditConversion {
  if (edit.documentChanges) {
    const changes: DocumentEdits[] = [];
    for (const change of edit.documentChanges) {
      if (!('textDocument' in change))
        return {
          kind: 'unsupported',
          reason: 'This action requires file operations that this editor does not support.',
        };
      if (change.edits.some((value) => !('newText' in value) || 'annotationId' in value))
        return {
          kind: 'unsupported',
          reason: 'This action requires snippet edits or annotated edits that this editor does not support.',
        };
      changes.push({
        uri: change.textDocument.uri,
        version: change.textDocument.version ?? undefined,
        edits: change.edits.map((value) => {
          if (!('newText' in value)) throw new Error('Expected a validated text edit.');
          return { range: value.range, text: value.newText };
        }),
      });
    }
    return { kind: 'supported', changes };
  }
  return {
    kind: 'supported',
    changes: Object.entries(edit.changes ?? {}).map(([uri, edits]) => ({
      uri,
      edits: edits.map((value) => ({ range: value.range, text: value.newText })),
    })),
  };
}
