import { z } from 'zod';
import palettes from './palettes.json';
import type { PlaygroundTheme, ThemeColor } from './themes';

const color = z
  .string()
  .regex(/^#[\da-f]{6}$/i)
  .transform((value) => value as ThemeColor);
export const themeIdSchema = z
  .string()
  .regex(/^[a-z][a-z\d-]*$/)
  .max(80);
const themeSchema: z.ZodType<PlaygroundTheme> = z.strictObject({
  id: themeIdSchema,
  label: z
    .string()
    .min(1)
    .max(80)
    .refine((label) => label.trim().length > 0),
  colorScheme: z.enum(['dark', 'light']),
  highContrast: z.boolean().optional(),
  colors: z.strictObject({
    background: color,
    surface: color,
    raised: color,
    hover: color,
    border: color,
    text: color,
    muted: color,
    accent: color,
    onAccent: color,
    selection: color,
    error: color,
    warning: color,
    success: color,
    info: color,
  }),
  syntax: z.strictObject({
    comment: color,
    keyword: color,
    type: color,
    function: color,
    string: color,
    number: color,
    variable: color,
    parameter: color,
    property: color,
    constant: color,
    namespace: color,
  }),
});

function readTheme(value: unknown): PlaygroundTheme {
  const result = themeSchema.safeParse(value);
  if (!result.success) throw new TypeError('Invalid color theme definition.', { cause: result.error });
  return Object.freeze({
    ...result.data,
    colors: Object.freeze(result.data.colors),
    syntax: Object.freeze(result.data.syntax),
  });
}

/** Validate the whole collection before any caller installs a theme or changes application state. */
export function readThemes(values: readonly unknown[]): readonly [PlaygroundTheme, ...PlaygroundTheme[]] {
  const [first, ...remaining] = values.map(readTheme);
  if (!first) throw new TypeError('At least one color theme is required.');
  const themes: [PlaygroundTheme, ...PlaygroundTheme[]] = [first, ...remaining];
  const ids = new Set<string>();
  for (const theme of themes) {
    if (ids.has(theme.id)) throw new TypeError(`Duplicate color theme ID: ${theme.id}`);
    ids.add(theme.id);
  }
  return Object.freeze(themes);
}

export const builtinThemes = readThemes(palettes);

/** Mount-owned, validated theme definitions. No registrations are shared between host configurations. */
export class ThemeCatalog {
  readonly themes: readonly PlaygroundTheme[];
  readonly defaultTheme: PlaygroundTheme;

  constructor(additions: readonly PlaygroundTheme[] = [], defaultId = builtinThemes[0].id) {
    this.themes = readThemes([...builtinThemes, ...additions]);
    this.defaultTheme = this.get(defaultId);
  }

  find(id: string): PlaygroundTheme | undefined {
    return this.themes.find((theme) => theme.id === id);
  }

  get(id: string): PlaygroundTheme {
    const theme = this.find(id);
    if (!theme) throw new RangeError(`Unknown color theme: ${id}`);
    return theme;
  }
}
