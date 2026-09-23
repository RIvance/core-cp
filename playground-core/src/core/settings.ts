import { z } from 'zod';

/** @internal Workspace policy shared by settings controls, configuration changes and stored settings. */
export const playgroundSettingsSchema = z.object({
  fontSize: z.number().int().min(11).max(24),
  tabSize: z.number().int().min(1).max(8),
  wordWrap: z.boolean(),
  minimap: z.boolean(),
  lineNumbers: z.boolean(),
  inlayHints: z.boolean().default(true),
  semanticHighlighting: z.boolean().default(true),
  autoRun: z.boolean(),
  timeout: z.number().int().min(1).max(60),
});
/** Editor and execution policy. Defaults apply to omitted creation options, not subsequent partial updates. */
export interface PlaygroundSettings {
  /** Font size in CSS pixels, integer 11–24. Default 14. */
  readonly fontSize: number;
  /** Spaces per indent, integer 1–8. Default 2. */
  readonly tabSize: number;
  /** Wrap long lines to editor width. Default false. */
  readonly wordWrap: boolean;
  /** Show Monaco's minimap. Default false. */
  readonly minimap: boolean;
  /** Show line numbers. Default true. */
  readonly lineNumbers: boolean;
  /** Display inlay hints when the language service supplies them. Default true. */
  readonly inlayHints: boolean;
  /** Display semantic tokens when the service supplies them. Default true. */
  readonly semanticHighlighting: boolean;
  /** Run after 1,000 ms without execution-input changes. Default false. Selecting a tab does not rerun. */
  readonly autoRun: boolean;
  /** Execution time limit in seconds, integer 1–60. Default 10. Cancellation is sent to the runtime. */
  readonly timeout: number;
}
/** Immutable defaults used by both the full application and embedded editors. */
export const defaultPlaygroundSettings: PlaygroundSettings = Object.freeze({
  fontSize: 14,
  tabSize: 2,
  wordWrap: false,
  minimap: false,
  lineNumbers: true,
  inlayHints: true,
  semanticHighlighting: true,
  autoRun: false,
  timeout: 10,
});
