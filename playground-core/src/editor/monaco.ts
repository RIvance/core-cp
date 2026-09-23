import * as monaco from 'monaco-editor/editor/editor.api';
import 'monaco-editor/features/register.all';
// The modular feature bundle registers viewport tokens; full-document providers need this contribution too.
import 'monaco-editor/editor/contrib/semanticTokens/browser/documentSemanticTokens';
import EditorWorker from 'monaco-editor/editor/editor.worker?worker';
import type { LanguageDefinition } from '../core/contracts';
import { getBuiltinThemes, type PlaygroundTheme } from '../appearance/themes';
import { readThemes } from '../appearance/catalog';
import { editorTheme } from './theme';

// Monaco's environment is its process-wide worker hook; all workspace and language state remains instance-owned.
self.MonacoEnvironment = { getWorker: () => new EditorWorker() };

export { monaco };
/** Separates this editor's Monaco identity from the language's backend/document identity. */
export interface EditorLanguageRegistration {
  /** Private, instance-scoped ID. Use this ID for every Monaco language, token, configuration, and snippet registration. */
  readonly id: string;
  /** Original plugin metadata. definition.id remains the backend/document language ID. */
  readonly definition: LanguageDefinition;
}

/** Editor-only language installation, supplied alongside the runtime/service plugin at the composition root. */
export interface EditorLanguage {
  /**
   * Install lexical tokens, comments, brackets, and optional syntax snippets under registration.id.
   * Each editor receives its own namespace; use registration.definition for display/extension metadata.
   * Return a disposable releasing every provider/configuration owned by this installation. If installation throws,
   * release resources acquired before the failure. Monaco retains language-ID descriptors; it has no unregister API.
   * Semantic providers come from LanguageService.capabilities; do not register competing providers here.
   */
  install(editor: typeof monaco, registration: EditorLanguageRegistration): monaco.IDisposable;
}

/**
 * Register complete palettes with Monaco under `playground-${theme.id}`. Omitting themes installs the bundled
 * collection. This does not select a theme or style a workbench; mountPlayground manages both automatically.
 * The collection must be nonempty. All definitions and duplicate IDs are validated before any registration;
 * invalid input throws TypeError.
 * Calling again replaces definitions with the same Monaco names. Monaco owns these browser-wide registrations
 * and provides no removal API. Managed playgrounds reinstall their selected palette on a page-theme change.
 */
export function installThemes(themes: readonly PlaygroundTheme[] = getBuiltinThemes()): void {
  for (const theme of readThemes(themes)) monaco.editor.defineTheme(`playground-${theme.id}`, editorTheme(theme));
}
