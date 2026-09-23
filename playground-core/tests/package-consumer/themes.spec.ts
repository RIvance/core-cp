import { expect, test, type Page } from '@playwright/test';
import { getBuiltinThemes } from '@language-playground/ide/themes';

function rgb(hex: string): string {
  return `rgb(${[1, 3, 5].map((offset) => parseInt(hex.slice(offset, offset + 2), 16)).join(', ')})`;
}

async function command(page: Page, label: string): Promise<void> {
  await page.keyboard.press('Control+Shift+p');
  await page.getByRole('combobox', { name: 'Search commands' }).fill(label);
  await page.getByRole('option', { name: label }).click();
  await expect(page.getByRole('dialog')).toHaveCount(0);
}

test('applies every palette to source, panels, controls, and portals without replacing editor state', async ({
  page,
}) => {
  const failures: string[] = [];
  page.on('pageerror', (error) => failures.push(error.message));
  await page.goto('./');
  await page.evaluate(() => window.consumerHarness.mount(true));
  const source = page.getByRole('textbox', { name: /Source code editor/ });
  await source.focus();
  await page.keyboard.press('Control+End');
  await page.keyboard.insertText(' edited');
  const appearance = page.getByRole('button', { name: 'Color theme', exact: true });
  for (const theme of getBuiltinThemes()) {
    await appearance.click();
    await page.getByRole('combobox', { name: 'Search color themes' }).fill(theme.label);
    await page.getByRole('option', { name: theme.label, exact: true }).click();
    await expect(page.locator('.workbench')).toHaveAttribute('data-theme', theme.id);
    await expect(page.locator('.language-playground')).toHaveAttribute('data-mantine-color-scheme', theme.colorScheme);
    await expect(page.locator('.workbench')).toHaveCSS('background-color', rgb(theme.colors.background));
    await expect(page.locator('.tool-panel')).toHaveCSS('background-color', rgb(theme.colors.surface));
    await expect(page.locator('.monaco-editor-background').first()).toHaveCSS(
      'background-color',
      rgb(theme.colors.surface),
    );
    await expect(page.getByRole('button', { name: /^Run/ })).toHaveCSS('background-color', rgb(theme.colors.accent));
    await expect(page.getByRole('button', { name: /^Run/ })).toHaveCSS('color', rgb(theme.colors.onAccent));
    await appearance.hover();
    await expect(appearance).toHaveCSS('color', rgb(theme.colors.muted));
    await expect(appearance).not.toHaveCSS('background-color', rgb(theme.colors.muted));
    const firstToken = page
      .locator('.view-line span')
      .filter({ hasText: /^independent$/ })
      .last();
    await expect(firstToken).toHaveCSS('color', rgb(theme.syntax.keyword));
    await expect(page.locator('.view-lines')).toContainText('independent package edited');
    await page.getByRole('button', { name: 'Workspace settings', exact: true }).click();
    await expect(page.getByRole('dialog')).toHaveCSS('background-color', rgb(theme.colors.raised));
    const fontSize = page.getByRole('textbox', { name: 'Font size', exact: true });
    await expect(fontSize).toHaveCSS('background-color', rgb(theme.colors.surface));
    await expect(fontSize).toHaveCSS('color', rgb(theme.colors.text));
    await page.keyboard.press('Escape');
    await expect(page.getByRole('dialog')).toHaveCount(0);
  }
  await source.focus();
  await page.keyboard.press('Control+z');
  await expect(page.locator('.view-lines')).toContainText('independent package');
  await expect(page.locator('.view-lines')).not.toContainText('edited');
  expect(
    await page.evaluate(() => [window.consumerHarness.installations, window.consumerHarness.serviceDisposals]),
  ).toEqual([1, 0]);
  expect(failures).toEqual([]);
});

test('saves command-palette selections across a reload and preserves old light preferences', async ({ page }) => {
  await page.goto('./');
  await page.evaluate(() => {
    window.localStorage.setItem(
      'playground.settings.v1',
      JSON.stringify({
        fontSize: 18,
        tabSize: 4,
        wordWrap: true,
        minimap: false,
        lineNumbers: true,
        autoRun: false,
        theme: 'light',
        timeout: 10,
      }),
    );
    window.consumerHarness.mount();
  });
  await expect(page.locator('.workbench')).toHaveAttribute('data-theme', 'light');
  const next = getBuiltinThemes().find((theme) => theme.id === 'ocean')!;
  await command(page, `Use ${next.label} theme`);
  await page.reload();
  await page.evaluate(() => window.consumerHarness.mount(false, false, { defaultTheme: 'dark' }));
  await expect(page.locator('.workbench')).toHaveAttribute('data-theme', next.id);
  await page.getByRole('button', { name: 'Workspace settings', exact: true }).click();
  await expect(page.getByRole('textbox', { name: 'Font size' })).toHaveValue('18 px');
  await expect(page.getByRole('combobox', { name: 'Indentation' })).toHaveValue('4 spaces');
});

test('registers a host theme through public APIs and reports a missing saved palette on remount', async ({ page }) => {
  const base = getBuiltinThemes()[0];
  const custom = {
    ...base,
    id: 'consumer-palette',
    label: 'Consumer palette',
    colors: { ...base.colors, surface: '#172c31' as const, accent: '#91e1d2' as const },
    syntax: { ...base.syntax, keyword: '#f9c5e4' as const },
  };
  await page.goto('./');
  await page.evaluate(
    (theme) => window.consumerHarness.mount(false, false, { themes: [theme], defaultTheme: theme.id }),
    custom,
  );
  await expect(page.locator('.workbench')).toHaveAttribute('data-theme', custom.id);
  await expect(page.locator('.monaco-editor-background').first()).toHaveCSS(
    'background-color',
    rgb(custom.colors.surface),
  );
  await page.getByRole('button', { name: 'Workspace settings', exact: true }).click();
  await page.getByRole('textbox', { name: 'Font size' }).fill('19');
  await page.keyboard.press('Escape');
  await page.getByRole('button', { name: 'Color theme', exact: true }).click();
  await page.getByRole('combobox', { name: 'Search color themes' }).fill(custom.label);
  await expect(page.getByRole('option', { name: custom.label, exact: true })).toBeVisible();
  await page.evaluate(() => {
    window.consumerHarness.dispose();
    window.consumerHarness.mount(false, false, { defaultTheme: 'sand' });
  });
  await expect(page.locator('.workbench')).toHaveAttribute('data-theme', 'sand');
  await expect(page.getByText(/The saved color theme “consumer-palette” is unavailable/)).toBeVisible();
  await page.getByRole('button', { name: 'Workspace settings', exact: true }).click();
  await expect(page.getByRole('textbox', { name: 'Font size' })).toHaveValue('19 px');
});

test('rejects invalid host configuration before mounting or creating editor registrations', async ({ page }) => {
  await page.goto('./');
  const theme = getBuiltinThemes()[0];
  await expect(
    page.evaluate((duplicate) => window.consumerHarness.mount(false, false, { themes: [duplicate] }), theme),
  ).rejects.toThrow('Duplicate color theme ID');
  await expect(
    page.evaluate(() => window.consumerHarness.mount(false, false, { defaultTheme: 'missing' })),
  ).rejects.toThrow('Unknown color theme');
  await expect(page.locator('#host')).toBeEmpty();
  expect(await page.evaluate(() => window.consumerHarness.installations)).toBe(0);
  await page.evaluate(() => window.consumerHarness.mount());
  await expect(page.getByTestId('source-editor')).toBeVisible();
});
