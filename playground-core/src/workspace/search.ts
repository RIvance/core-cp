import type { Range } from '../core/contracts';
import type { Workspace, WorkspaceFile } from './model';

/** Search all files without interpreting a language, filename extension, or editor state. */
export interface WorkspaceSearchOptions {
  /** Match case exactly. Defaults to false (Unicode case folding). */
  readonly caseSensitive?: boolean;
  /** Require boundaries outside Unicode letters, numbers, combining marks, and underscore. Defaults to false. */
  readonly wholeWord?: boolean;
  /** Interpret the query as a JavaScript Unicode regular expression. Defaults to false (literal text). */
  readonly regularExpression?: boolean;
  /** Maximum returned matches across all files. Positive integer; defaults to 1,000. */
  readonly limit?: number;
}

/** One source match; ranges use zero-based, half-open UTF-16 coordinates in the searched snapshot. */
export interface WorkspaceSearchMatch {
  readonly range: Range;
  /** Bounded single-line preview. Ellipses indicate omitted text, not source characters. */
  readonly preview: { readonly before: string; readonly match: string; readonly after: string };
}

/** Matches in one file, in source order. File identity survives renames. */
export interface WorkspaceFileMatches {
  readonly fileId: string;
  readonly path: WorkspaceFile['path'];
  readonly matches: readonly WorkspaceSearchMatch[];
}

/** Search result in workspace file order. Files without matches are omitted. */
export interface WorkspaceSearchResult {
  readonly files: readonly WorkspaceFileMatches[];
  readonly count: number;
  /** True only when at least one additional match was found beyond the requested limit. */
  readonly truncated: boolean;
}

/** A query rejected before any results are published. */
export class WorkspaceSearchError extends Error {
  constructor(
    readonly code: 'invalid-pattern' | 'invalid-limit',
    message: string,
    options?: ErrorOptions,
  ) {
    super(message, options);
    this.name = 'WorkspaceSearchError';
  }
}

/**
 * Search exact workspace text without mutating it. Matches do not overlap and are confined to one line;
 * CRLF, CR, and LF each delimit one line. Empty queries return no matches. Regular expressions use JavaScript
 * Unicode syntax; invalid patterns throw WorkspaceSearchError. Zero-length matches advance by a code point.
 * This synchronous function does not bound regexp execution time. Browser callers can use WorkspaceSearchPanel,
 * which runs it in a cancellable worker with a time limit.
 */
export function searchWorkspace(
  workspace: Workspace,
  query: string,
  options: WorkspaceSearchOptions = {},
): WorkspaceSearchResult {
  const limit = options.limit ?? 1_000;
  if (!Number.isSafeInteger(limit) || limit < 1)
    throw new WorkspaceSearchError('invalid-limit', 'The search limit must be a positive integer.');
  if (!query) return { files: [], count: 0, truncated: false };
  const source = options.regularExpression ? query : query.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  let pattern: RegExp;
  try {
    const word = '[\\p{L}\\p{N}\\p{M}_]';
    pattern = new RegExp(
      options.wholeWord ? `(?<!${word})(?:${source})(?!${word})` : source,
      options.caseSensitive ? 'gu' : 'giu',
    );
  } catch (cause) {
    throw new WorkspaceSearchError('invalid-pattern', 'Invalid regular expression.', { cause });
  }
  const files: WorkspaceFileMatches[] = [];
  let count = 0;
  for (const file of workspace.files) {
    const matches: WorkspaceSearchMatch[] = [];
    const lines = file.text.split(/\r\n|\r|\n/);
    for (const [line, text] of lines.entries()) {
      for (const match of text.matchAll(pattern)) {
        if (count === limit) {
          if (matches.length) files.push({ fileId: file.id, path: file.path, matches });
          return { files, count, truncated: true };
        }
        const start = match.index;
        const end = start + match[0].length;
        matches.push({
          range: { start: { line, character: start }, end: { line, character: end } },
          preview: preview(text, start, end),
        });
        count++;
      }
    }
    if (matches.length) files.push({ fileId: file.id, path: file.path, matches });
  }
  return { files, count, truncated: false };
}

function preview(text: string, start: number, end: number): WorkspaceSearchMatch['preview'] {
  // Bound work per match, including for many matches on a very long line. Two code units per scalar plus
  // an extra scalar keep the retained slices clear of surrogate pairs cut at the outer window boundary.
  const before = Array.from(text.slice(Math.max(0, start - 82), start))
    .slice(-40)
    .join('');
  const matched = Array.from(text.slice(start, Math.min(end, start + 202)))
    .slice(0, 100)
    .join('');
  const after = Array.from(text.slice(end, end + 162))
    .slice(0, 80)
    .join('');
  return {
    before: (start > before.length ? '…' : '') + before,
    match: matched + (end - start > matched.length ? '…' : ''),
    after: after + (text.length - end > after.length ? '…' : ''),
  };
}
