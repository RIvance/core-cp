import { builtinThemes, readThemes } from './catalog';
import type { PlaygroundTheme } from './themes';

// Monaco has one theme service per window. This store owns the same scope for surrounding controls.
// All other controller and editor state belongs to individual instances.
let current: PlaygroundTheme = builtinThemes[0];
const listeners = new Set<() => void>();

/** Current page palette. The returned definition is immutable. Initially the first built-in theme. */
export function getTheme(): PlaygroundTheme {
  return current;
}

/**
 * Set the palette for all playground editors and controls in this window, matching Monaco's theme scope.
 * Pass a built-in ID or a complete custom definition. Validation finishes before changing the page.
 * Existing editor models, selections, undo stacks, and runs are retained. No preference is persisted here.
 * @throws RangeError for an unknown built-in ID; TypeError for an invalid custom palette.
 */
export function setTheme(theme: string | PlaygroundTheme): void {
  const palette = readPageTheme(theme);
  current = palette;
  for (const listener of listeners) listener();
}

/** Observe subsequent page theme changes. The returned function removes this listener; it is safe to call twice. */
export function onDidChangeTheme(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

/** @internal Resolve and snapshot a requested page palette without applying it. */
export function readPageTheme(theme: string | PlaygroundTheme): PlaygroundTheme {
  const palette = typeof theme === 'string' ? builtinThemes.find((item) => item.id === theme) : readThemes([theme])[0];
  if (!palette) throw new RangeError(`Unknown built-in color theme: ${typeof theme === 'string' ? theme : theme.id}`);
  return palette;
}
