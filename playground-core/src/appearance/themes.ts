import { builtinThemes } from './catalog';

/**
 * Opaque sRGB color written as #RRGGBB (case insensitive). Theme registration validates all six digits.
 * Alpha, CSS variables, named colors, and CSS functions are not accepted: the same values feed CSS and Monaco.
 */
export type ThemeColor = `#${string}`;

/** Colors shared by workspace chrome, editor surfaces, and controls. Every role is required. */
export interface ThemeColors {
  /** Window chrome, sidebar, and status bar background. */
  readonly background: ThemeColor;
  /** Source editor, output panel, and form input background. */
  readonly surface: ThemeColor;
  /** Dialogs, menus, editor widgets, and the current source line. */
  readonly raised: ThemeColor;
  /** Hovered rows and controls. */
  readonly hover: ThemeColor;
  /** Dividers, control outlines, and indentation guides. */
  readonly border: ThemeColor;
  /** Main foreground, including unclassified source text. */
  readonly text: ThemeColor;
  /** Secondary labels, placeholders, and inactive line numbers; must remain readable on all surfaces. */
  readonly muted: ThemeColor;
  /** Links, focus outlines, active indicators, editor cursor, and primary button background. */
  readonly accent: ThemeColor;
  /** Foreground on a solid accent background, including the Run button and selected options. */
  readonly onAccent: ThemeColor;
  /** Source selection background; source token colors remain in use over this color. */
  readonly selection: ThemeColor;
  /** Error diagnostics and failure indicators. */
  readonly error: ThemeColor;
  /** Warning diagnostics and warning indicators. */
  readonly warning: ThemeColor;
  /** Successful operations and available capabilities. */
  readonly success: ThemeColor;
  /** Informational diagnostics and messages. */
  readonly info: ThemeColor;
}

/**
 * Foregrounds for lexical and standard semantic token categories. Colors also identify symbols in the outline.
 * Comments and type parameters use italics. Unknown token categories inherit the selected Monaco base theme.
 * Languages can install additional Monaco rules for their own token categories; these roles contain no language names.
 */
export interface ThemeSyntax {
  /** Comments, including documentation comments. */
  readonly comment: ThemeColor;
  /** Keywords and storage modifiers. */
  readonly keyword: ThemeColor;
  /** Types, classes, interfaces, enums, structs, and type parameters. */
  readonly type: ThemeColor;
  /** Functions and methods. */
  readonly function: ThemeColor;
  /** String literals and regular expressions. */
  readonly string: ThemeColor;
  /** Numeric literals. */
  readonly number: ThemeColor;
  /** Identifiers and variables without a more specific category. */
  readonly variable: ThemeColor;
  /** Function parameters. */
  readonly parameter: ThemeColor;
  /** Properties and fields. */
  readonly property: ThemeColor;
  /** Named constants and enum members. */
  readonly constant: ThemeColor;
  /** Namespaces, modules, and packages. */
  readonly namespace: ThemeColor;
}

/**
 * A complete editor and workbench palette. Hosts may add themes through PlaygroundOptions.themes.
 * Keep id stable across releases: the browser saves the ID, not a copy of the palette. Registration snapshots
 * the definition; mutating the caller's object later does not change a mounted IDE.
 */
export interface PlaygroundTheme {
  /** Unique ID: a lowercase ASCII letter followed by lowercase letters, digits, or hyphens; at most 80 characters. */
  readonly id: string;
  /** Nonblank display name, at most 80 characters. Used by settings and the command palette. */
  readonly label: string;
  /** Base color scheme for browser controls, UI components, and inherited editor colors. */
  readonly colorScheme: 'dark' | 'light';
  /**
   * Use Monaco's matching high-contrast base, visible widget borders, and opaque control highlights.
   * Defaults to false. This flag does not validate contrast ratios; the theme author must check their colors.
   */
  readonly highContrast?: boolean;
  /** Complete workbench and editor surface colors. */
  readonly colors: ThemeColors;
  /** Complete standard source-token foregrounds. */
  readonly syntax: ThemeSyntax;
}

/**
 * Return the bundled themes in settings order. The nonempty array and its definitions are immutable.
 * The first theme is the default for hosts that do not specify defaultTheme. Derive a custom palette with
 * object spreads and a new ID, then pass it in PlaygroundOptions.themes. This function does not touch the DOM
 * or install editor themes, so it is also safe to call outside a browser.
 */
export function getBuiltinThemes(): readonly [PlaygroundTheme, ...PlaygroundTheme[]] {
  return builtinThemes;
}
