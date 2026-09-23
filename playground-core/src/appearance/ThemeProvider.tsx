import { useId, useLayoutEffect, useSyncExternalStore, type ReactNode } from 'react';
import {
  MantineProvider,
  Input,
  Portal,
  colorsTuple,
  createTheme,
  defaultVariantColorsResolver,
  type CSSVariablesResolver,
  type MantineColorSchemeManager,
} from '@mantine/core';
import { getTheme, onDidChangeTheme } from './page-theme';
import type { PlaygroundTheme, ThemeColors } from './themes';

// The page palette controls Mantine; only the full application owns preference persistence.
const controlledColorScheme: MantineColorSchemeManager = {
  get: (fallback) => fallback,
  set: () => {},
  clear: () => {},
  subscribe: () => {},
  unsubscribe: () => {},
};

function controlColors(colors: ThemeColors) {
  return {
    accent: colors.accent,
    error: colors.error,
    warning: colors.warning,
    success: colors.success,
    info: colors.info,
    neutral: colors.muted,
  };
}

function cssVariables(palette: PlaygroundTheme): ReturnType<CSSVariablesResolver> {
  const { colors, syntax } = palette;
  const shared = {
    '--mantine-color-body': colors.raised,
    '--mantine-color-text': colors.text,
    '--mantine-color-bright': colors.text,
    '--mantine-color-dimmed': colors.muted,
    '--mantine-color-placeholder': colors.muted,
    '--mantine-color-anchor': colors.accent,
    '--mantine-color-error': colors.error,
    '--mantine-color-success': colors.success,
    '--mantine-color-default': colors.surface,
    '--mantine-color-default-hover': colors.hover,
    '--mantine-color-default-color': colors.text,
    '--mantine-color-default-border': colors.border,
    '--mantine-color-disabled': colors.raised,
    '--mantine-color-disabled-color': colors.muted,
    '--mantine-color-disabled-border': colors.border,
    '--mantine-primary-color-contrast': colors.onAccent,
    // Each role has one foreground, not a ten-shade ramp. Give light/subtle controls their own backgrounds.
    ...Object.fromEntries(
      Object.entries(controlColors(colors)).flatMap(([role, color]) => [
        [
          `--mantine-color-${role}-light`,
          palette.highContrast ? colors.background : `color-mix(in srgb, ${color} 6%, ${colors.background})`,
        ],
        [
          `--mantine-color-${role}-light-hover`,
          palette.highContrast ? colors.hover : `color-mix(in srgb, ${color} 10%, ${colors.background})`,
        ],
        [`--mantine-color-${role}-light-color`, color],
      ]),
    ),
  };
  return {
    variables: {
      ...Object.fromEntries(Object.entries({ ...colors }).map(([role, color]) => [`--pg-${role}`, color])),
      ...Object.fromEntries(Object.entries({ ...syntax }).map(([role, color]) => [`--pg-syntax-${role}`, color])),
      '--pg-subtle': palette.highContrast
        ? colors.background
        : `color-mix(in srgb, ${colors.accent} 10%, ${colors.background})`,
    },
    light: shared,
    dark: shared,
  };
}

/** Theme context and scoped CSS variables for shared playground components. */
export interface ThemeProviderProps {
  /** Connected element containing this view and its portals. The provider adds/restores its CSS scope attribute. */
  readonly host: HTMLElement;
  /** Shared components to render in the host. Import styles.css and give the host a height. */
  readonly children: ReactNode;
}

/** Follow the page theme without remounting editor models; portals are contained by host. */
export function ThemeProvider({ host, children }: ThemeProviderProps): ReactNode {
  const scope = useId();
  useLayoutEffect(() => {
    const previous = host.dataset.playgroundThemeScope;
    const hadClass = host.classList.contains('language-playground');
    host.dataset.playgroundThemeScope = scope;
    host.classList.add('language-playground');
    return () => {
      if (previous === undefined) delete host.dataset.playgroundThemeScope;
      else host.dataset.playgroundThemeScope = previous;
      if (!hadClass) host.classList.remove('language-playground');
    };
  }, [host, scope]);
  const palette = useSyncExternalStore(onDidChangeTheme, getTheme);
  const { colors } = palette;
  const theme = createTheme({
    primaryColor: 'accent',
    autoContrast: true,
    defaultRadius: 'md',
    fontFamily: 'Inter, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif',
    colors: {
      // Some Mantine components address neutral ramp slots directly instead of using semantic CSS variables.
      dark: [
        colors.text,
        colors.muted,
        colors.muted,
        colors.border,
        colors.border,
        colors.hover,
        colors.surface,
        colors.raised,
        colors.background,
        colors.background,
      ],
      gray: [
        colors.hover,
        colors.raised,
        colors.border,
        colors.border,
        colors.border,
        colors.muted,
        colors.muted,
        colors.text,
        colors.text,
        colors.text,
      ],
      ...Object.fromEntries(Object.entries(controlColors(colors)).map(([role, color]) => [role, colorsTuple(color)])),
    },
    variantColorResolver(input) {
      const resolved = defaultVariantColorsResolver(input);
      if (input.variant === 'filled' && (input.color ?? input.theme.primaryColor) === 'accent')
        return { ...resolved, color: colors.onAccent };
      if (palette.highContrast && (input.variant === 'light' || input.variant === 'outline'))
        return {
          ...resolved,
          background: colors.background,
          hover: colors.hover,
          border: `1px solid ${colors.border}`,
        };
      return resolved;
    },
    components: {
      Portal: Portal.extend({ defaultProps: { target: host } }),
      Input: Input.extend({
        styles: (_theme, props) =>
          props.variant === undefined || props.variant === 'default'
            ? { wrapper: { '--input-bg': colors.surface } }
            : {},
      }),
    },
  });
  return (
    <MantineProvider
      theme={theme}
      forceColorScheme={palette.colorScheme}
      defaultColorScheme={palette.colorScheme}
      colorSchemeManager={controlledColorScheme}
      getRootElement={() => host}
      cssVariablesSelector={`[data-playground-theme-scope="${scope}"]`}
      cssVariablesResolver={() => cssVariables(palette)}
      deduplicateCssVariables={false}
    >
      {children}
    </MantineProvider>
  );
}
