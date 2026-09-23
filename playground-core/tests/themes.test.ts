import { describe, expect, it } from 'vitest';
import { getBuiltinThemes, type PlaygroundTheme } from '../src/appearance/themes';
import { ThemeCatalog } from '../src/appearance/catalog';

function luminance(hex: string): number {
  const linear = [1, 3, 5].map((offset) => {
    const channel = parseInt(hex.slice(offset, offset + 2), 16) / 255;
    return channel <= 0.04045 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4;
  });
  return linear[0]! * 0.2126 + linear[1]! * 0.7152 + linear[2]! * 0.0722;
}

function contrast(first: string, second: string): number {
  const a = luminance(first);
  const b = luminance(second);
  return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
}

describe('theme catalog', () => {
  it('snapshots custom definitions and keeps catalogs independent', () => {
    const base = getBuiltinThemes()[0];
    const custom = { ...base, id: 'custom', label: 'Custom', colors: { ...base.colors, accent: '#abcdef' as const } };
    const catalog = new ThemeCatalog([custom], custom.id);
    custom.colors.text = '#123456';
    custom.label = 'Changed';
    expect(catalog.defaultTheme).toMatchObject({ id: 'custom', label: 'Custom', colors: { text: base.colors.text } });
    expect(Object.isFrozen(catalog.themes)).toBe(true);
    expect(Object.isFrozen(catalog.defaultTheme.colors)).toBe(true);
    expect(new ThemeCatalog().find(custom.id)).toBeUndefined();
    expect(getBuiltinThemes().some((theme) => theme.id === custom.id)).toBe(false);
  });

  it('rejects duplicate IDs and unknown defaults without overwriting the bundled catalog', () => {
    const base = getBuiltinThemes()[0];
    expect(() => new ThemeCatalog([base])).toThrow(TypeError);
    const custom = { ...base, id: 'duplicate' };
    expect(() => new ThemeCatalog([custom, custom])).toThrow(TypeError);
    expect(() => new ThemeCatalog([], 'not-installed')).toThrow(RangeError);
    expect(new ThemeCatalog().defaultTheme.id).toBe(base.id);
  });

  it.each([
    { id: 'invalid id' },
    { id: 'Uppercase' },
    { label: '   ' },
    { colorScheme: 'unknown' },
    { colors: {} },
    { syntax: {} },
    { unexpected: true },
    ...['red', '#abc', '#12345678', 'var(--accent)', '#12gg45'].map((accent) => ({
      colors: { ...getBuiltinThemes()[0].colors, accent },
    })),
  ])('rejects incomplete or malformed definitions: %j', (change) => {
    const invalid = { ...getBuiltinThemes()[0], id: 'custom', ...change } as PlaygroundTheme;
    expect(() => new ThemeCatalog([invalid])).toThrow(TypeError);
    try {
      new ThemeCatalog([invalid]);
    } catch (error) {
      expect(error).toHaveProperty('cause');
    }
  });

  for (const theme of getBuiltinThemes()) {
    it(`${theme.label} has readable text, source tokens, and accent controls`, () => {
      const { colors, syntax } = theme;
      const minimum = theme.highContrast ? 7 : 4.5;
      for (const role of ['text', 'muted', 'accent', 'error', 'warning', 'success', 'info'] as const) {
        for (const background of ['background', 'surface', 'raised', 'hover'] as const)
          expect(contrast(colors[role], colors[background]), `${role} on ${background}`).toBeGreaterThanOrEqual(
            minimum,
          );
      }
      for (const [role, color] of Object.entries({ ...syntax })) {
        expect(contrast(color, colors.surface), `${role} source token`).toBeGreaterThanOrEqual(minimum);
        expect(contrast(color, colors.selection), `selected ${role} source token`).toBeGreaterThanOrEqual(minimum);
      }
      expect(contrast(colors.onAccent, colors.accent), 'filled accent control').toBeGreaterThanOrEqual(minimum);
      if (theme.highContrast) {
        expect(contrast(colors.border, colors.background), 'visible chrome border').toBeGreaterThanOrEqual(7);
        expect(contrast(colors.text, colors.selection), 'selected source').toBeGreaterThanOrEqual(7);
      }
    });
  }
});
