/** A failed conversion at a text-file boundary. The path is diagnostic context, not a decoder setting. */
export class WorkspaceTextError extends Error {
  constructor(
    readonly path: string,
    options?: ErrorOptions,
  ) {
    super(`“${path}” is not a UTF-8 text file.`, options);
    this.name = 'WorkspaceTextError';
  }
}

/** Decode losslessly, preserving BOMs and line endings. NUL-containing files are treated as binary input. */
export function decodeWorkspaceText(bytes: Uint8Array, path: string): string {
  try {
    const text = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true }).decode(bytes);
    if (text.includes('\0')) throw new TypeError('NUL byte in source');
    return text;
  } catch (cause) {
    throw new WorkspaceTextError(path, { cause });
  }
}

/** Reject unpaired surrogates instead of silently replacing them during a UTF-8 export. */
export function encodeWorkspaceText(text: string, path: string): Uint8Array<ArrayBuffer> {
  const bytes = new TextEncoder().encode(text);
  if (decodeWorkspaceText(bytes, path) !== text) throw new WorkspaceTextError(path);
  return bytes;
}
