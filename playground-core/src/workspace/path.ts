/** Canonical, case-sensitive path relative to a workspace root. Construct with parseWorkspacePath. */
export type WorkspacePath = string & { readonly __workspacePath: unique symbol };

/** A path rejected before it can identify a workspace entry. The original input is retained for diagnostics. */
export class WorkspacePathError extends Error {
  /** The rejected path is never normalized into another file's identity. */
  constructor(readonly path: string) {
    super(`Invalid workspace path “${path}”. Use relative paths separated by /, without empty, . or .. segments.`);
    this.name = 'WorkspacePathError';
  }
}

/**
 * Validate a relative POSIX path without changing its spelling. Paths contain at most 1,024 UTF-16 units;
 * each segment contains 1–120. Absolute paths, drive prefixes, backslashes, control characters, unpaired
 * surrogates, and . or .. segments are rejected. Case and Unicode spelling remain significant.
 * The empty string denotes the root only in APIs that explicitly accept it; it is not an entry path.
 * @throws WorkspacePathError for an invalid path.
 */
export function parseWorkspacePath(value: string): WorkspacePath {
  const segments = value.split('/');
  if (
    value.length > 1_024 ||
    /^[a-z]:/i.test(value) ||
    value.includes('\\') ||
    Array.from(value).some((character) => character.charCodeAt(0) < 32 || character.charCodeAt(0) === 127) ||
    segments.some((segment) => !segment.length || segment.length > 120 || segment === '.' || segment === '..')
  )
    throw new WorkspacePathError(value);
  try {
    encodeURIComponent(value);
  } catch {
    throw new WorkspacePathError(value);
  }
  return value as WorkspacePath;
}

/** Return the last segment of a validated path. */
export function pathName(path: WorkspacePath): string {
  return path.slice(path.lastIndexOf('/') + 1);
}

/** Return the parent path, or the empty string for an entry directly under the workspace root. */
export function parentPath(path: WorkspacePath): WorkspacePath | '' {
  const separator = path.lastIndexOf('/');
  return separator < 0 ? '' : (path.slice(0, separator) as WorkspacePath);
}

/** True for the directory itself and its descendants; segment boundaries prevent matching sibling prefixes. */
export function withinPath(path: WorkspacePath, directory: WorkspacePath): boolean {
  return path === directory || path.startsWith(`${directory}/`);
}

/**
 * Append an RFC 3986 encoded relative path to an absolute root URI. Each segment is encoded independently;
 * only unreserved characters remain literal. Directory separators are preserved. The root must have no query or fragment.
 */
export function workspaceDocumentUri(rootUri: string, path: WorkspacePath): string {
  const root = new URL(rootUri);
  if (root.search || root.hash) throw new TypeError('A workspace root URI cannot contain a query or fragment.');
  const encoded = path
    .split('/')
    .map((segment) =>
      encodeURIComponent(segment).replace(
        /[!'()*]/g,
        (character) => `%${character.charCodeAt(0).toString(16).toUpperCase()}`,
      ),
    )
    .join('/');
  return `${root.href.replace(/\/$/, '')}/${encoded}`;
}
