import type { editor } from 'monaco-editor/editor/editor.api';
import type { PlaygroundTheme } from '../appearance/themes';

/** Translate shared color roles into Monaco's lexical/semantic categories and widget color names. */
export function editorTheme(theme: PlaygroundTheme): editor.IStandaloneThemeData {
  const { colors, syntax } = theme;
  const rules: editor.ITokenThemeRule[] = [
    { token: '', foreground: colors.text.slice(1) },
    ...Object.entries({ ...syntax }).map(([token, color]) => ({ token, foreground: color.slice(1) })),
    { token: 'comment', foreground: syntax.comment.slice(1), fontStyle: 'italic' },
    { token: 'typeParameter', foreground: syntax.type.slice(1), fontStyle: 'italic' },
    ...['type.identifier', 'class', 'interface', 'enum', 'struct'].map((token) => ({
      token,
      foreground: syntax.type.slice(1),
    })),
    { token: 'method', foreground: syntax.function.slice(1) },
    { token: 'identifier', foreground: syntax.variable.slice(1) },
    { token: 'enumMember', foreground: syntax.constant.slice(1) },
    { token: 'regexp', foreground: syntax.string.slice(1) },
    { token: 'modifier', foreground: syntax.keyword.slice(1) },
    { token: 'invalid', foreground: colors.error.slice(1) },
  ];
  return {
    base:
      theme.colorScheme === 'dark'
        ? theme.highContrast
          ? 'hc-black'
          : 'vs-dark'
        : theme.highContrast
          ? 'hc-light'
          : 'vs',
    inherit: true,
    rules,
    colors: {
      focusBorder: colors.accent,
      foreground: colors.text,
      'widget.border': colors.border,
      contrastBorder: theme.highContrast ? colors.border : '#00000000',
      'selection.background': colors.selection,
      'textLink.foreground': colors.accent,
      'textLink.activeForeground': colors.accent,
      'editor.background': colors.surface,
      'editor.foreground': colors.text,
      'editorLineNumber.foreground': colors.muted,
      'editorLineNumber.activeForeground': colors.text,
      'editor.selectionBackground': colors.selection,
      'editor.inactiveSelectionBackground': colors.selection,
      'editor.selectionHighlightBackground': `${colors.accent}20`,
      'editor.lineHighlightBackground': colors.raised,
      'editor.lineHighlightBorder': theme.highContrast ? colors.border : '#00000000',
      'editorCursor.foreground': colors.accent,
      'editorWhitespace.foreground': colors.border,
      'editorIndentGuide.background1': colors.border,
      'editorIndentGuide.activeBackground1': colors.muted,
      'editorWidget.background': colors.raised,
      'editorWidget.foreground': colors.text,
      'editorWidget.border': colors.border,
      'editorHoverWidget.background': colors.raised,
      'editorHoverWidget.foreground': colors.text,
      'editorHoverWidget.border': colors.border,
      'editorSuggestWidget.background': colors.raised,
      'editorSuggestWidget.foreground': colors.text,
      'editorSuggestWidget.border': colors.border,
      'editorSuggestWidget.selectedBackground': colors.selection,
      'editorSuggestWidget.selectedForeground': colors.text,
      'editorSuggestWidget.highlightForeground': colors.accent,
      'editorSuggestWidget.focusHighlightForeground': colors.accent,
      'editorInlayHint.background': colors.raised,
      'editorInlayHint.foreground': colors.muted,
      'editorError.foreground': colors.error,
      'editorWarning.foreground': colors.warning,
      'editorInfo.foreground': colors.info,
      'editorHint.foreground': colors.muted,
      'input.background': colors.surface,
      'input.foreground': colors.text,
      'input.border': colors.border,
      'input.placeholderForeground': colors.muted,
      'inputOption.activeBackground': colors.selection,
      'inputOption.activeBorder': colors.accent,
      'inputOption.activeForeground': colors.text,
      'list.activeSelectionBackground': colors.selection,
      'list.activeSelectionForeground': colors.text,
      'list.inactiveSelectionBackground': colors.hover,
      'list.inactiveSelectionForeground': colors.text,
      'list.focusBackground': colors.selection,
      'list.focusForeground': colors.text,
      'list.hoverBackground': colors.hover,
      'list.hoverForeground': colors.text,
      'menu.background': colors.raised,
      'menu.foreground': colors.text,
      'menu.selectionBackground': colors.selection,
      'menu.selectionForeground': colors.text,
      'menu.separatorBackground': colors.border,
      'quickInput.background': colors.raised,
      'quickInput.foreground': colors.text,
      'scrollbarSlider.background': `${colors.muted}50`,
      'scrollbarSlider.hoverBackground': `${colors.muted}80`,
      'scrollbarSlider.activeBackground': `${colors.muted}a0`,
      'minimap.background': colors.surface,
    },
  };
}
