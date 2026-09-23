import { expect, test } from '@playwright/test';

test.beforeEach(async ({ page }) => {
  await page.goto('./editors.html');
  await expect.poll(() => page.evaluate(() => Boolean(window.embeddedHarness))).toBe(true);
});

test('creates independent fixed-file editors with shared page themes and no application controls', async ({ page }) => {
  const failures: string[] = [];
  page.on('pageerror', (error) => failures.push(error.message));
  await page.evaluate(async () => {
    await window.embeddedHarness.mount('first', {
      title: 'First editor',
      files: [
        { path: 'lib/static.txt', value: 'code static', readOnly: true },
        { path: 'main.txt', value: 'code first' },
      ],
      activeFile: 'main.txt',
      entryFile: 'main.txt',
    });
    await window.embeddedHarness.mount('second', { title: 'Second editor', value: 'code second', fontSize: 18 });
  });
  const first = page.locator('#first');
  const second = page.locator('#second');
  await expect(first.getByRole('tablist', { name: 'Source files' }).getByRole('tab')).toHaveCount(2);
  await expect(second.getByRole('tablist', { name: 'Source files' }).getByRole('tab')).toHaveCount(1);
  await expect(
    first.getByRole('button', { name: /Close |Download|New source|Color theme|Commands|Share|Open/ }),
  ).toHaveCount(0);
  await expect(first.getByRole('tab', { name: 'main.txt' })).toHaveAttribute('aria-selected', 'true');
  await first.getByRole('tab', { name: 'lib/static.txt' }).click();
  const editor = first.getByRole('textbox', { name: /Source code editor/ });
  await editor.focus();
  await page.keyboard.press('Control+a');
  await page.keyboard.insertText('forbidden');
  expect(await page.evaluate(() => window.embeddedHarness.get('first').getValue())).toBe('code static');
  await page.evaluate(() => window.embeddedHarness.get('first').setReadOnly(false));
  await editor.focus();
  await page.keyboard.press('Control+a');
  await page.keyboard.insertText('unlocked by host');
  expect(await page.evaluate(() => window.embeddedHarness.get('first').getValue())).toBe('unlocked by host');
  await page.evaluate(() => window.embeddedHarness.get('first').setReadOnly(true));
  await editor.focus();
  await page.keyboard.press('Control+a');
  await page.keyboard.insertText('blocked again');
  expect(await page.evaluate(() => window.embeddedHarness.get('first').getValue())).toBe('unlocked by host');
  await first.getByRole('tab', { name: 'main.txt' }).click();
  await editor.focus();
  await page.keyboard.press('Control+a');
  await page.keyboard.insertText('changed');
  await page.keyboard.press('Control+Enter');
  await expect(first.getByTestId('execution-value')).toHaveText('↳changed');
  expect(await page.evaluate(() => window.embeddedHarness.get('second').getSnapshot().execution.kind)).toBe('idle');
  await page.evaluate(() => window.embeddedHarness.setTheme('sand'));
  await expect(first.locator('.embedded-playground')).toHaveAttribute('data-theme', 'sand');
  await expect(second.locator('.embedded-playground')).toHaveAttribute('data-theme', 'sand');
  expect(await first.locator('.monaco-editor').evaluate((element) => getComputedStyle(element).backgroundColor)).toBe(
    await second.locator('.monaco-editor').evaluate((element) => getComputedStyle(element).backgroundColor),
  );
  await page.evaluate(() => window.embeddedHarness.get('first').updateOptions({ fontSize: 20 }));
  expect(await page.evaluate(() => window.embeddedHarness.get('second').getOptions().fontSize)).toBe(18);
  await editor.focus();
  await page.keyboard.press('F1');
  await page.keyboard.press('Control+Shift+p');
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await expect(page.locator('.quick-input-widget')).not.toBeVisible();
  expect(await page.evaluate(() => Object.keys(localStorage))).toEqual([]);
  expect(await page.evaluate(() => window.embeddedHarness.serviceRoots)).toHaveLength(2);
  expect(await page.evaluate(() => new Set(window.embeddedHarness.serviceRoots).size)).toBe(2);
  // Each root has its own ARIA identifiers, even where local tab/panel names are identical.
  expect(
    await page.evaluate(() => {
      const ids = [...document.querySelectorAll('.language-playground [id]')].map((element) => element.id);
      return new Set(ids).size === ids.length;
    }),
  ).toBe(true);
  await page.evaluate(() => window.embeddedHarness.get('first').dispose());
  expect(await page.evaluate(() => [window.embeddedHarness.modelCount, window.embeddedHarness.installations])).toEqual([
    1, 1,
  ]);
  await second.getByRole('button', { name: /^Run/ }).click();
  await expect(second.getByTestId('execution-value')).toHaveText('↳code second');
  await page.evaluate(() => window.embeddedHarness.get('second').dispose());
  expect(
    await page.evaluate(() => [
      window.embeddedHarness.modelCount,
      window.embeddedHarness.installations,
      window.embeddedHarness.serviceDisposals,
    ]),
  ).toEqual([0, 0, 2]);
  expect(failures).toEqual([]);
});

test('rejects a cross-file rename touching a protected file without applying its writable part', async ({ page }) => {
  await page.evaluate(() =>
    window.embeddedHarness.mount('first', {
      files: [
        { path: 'main.txt', value: 'code main' },
        { path: 'lib/static.txt', value: 'code static', readOnly: true },
      ],
    }),
  );
  await page.evaluate(() => {
    const editor = window.embeddedHarness.get('first').getEditor()!;
    editor.setPosition({ lineNumber: 1, column: 2 });
    editor.focus();
    editor.trigger('test', 'editor.action.rename', {});
  });
  const rename = page.getByRole('textbox', { name: /Rename input/ });
  await expect(rename).toBeVisible();
  await rename.fill('replacement');
  await rename.press('Enter');
  await expect(page.getByText('This action changes a read-only file.', { exact: false })).toBeVisible();
  expect(
    await page.evaluate(() =>
      window.embeddedHarness
        .get('first')
        .getFiles()
        .map((file) => file.value),
    ),
  ).toEqual(['code main', 'code static']);
  await page.evaluate(() => window.embeddedHarness.get('first').dispose());
});

test('preserves undo, selection, and lexical/service registrations when another instance is disposed', async ({
  page,
}) => {
  await page.evaluate(async () => {
    await window.embeddedHarness.mount('first', { value: 'first' });
    await window.embeddedHarness.mount('second', { value: 'code second' });
    const second = window.embeddedHarness.get('second');
    second.setValue('changed second');
    if (second.getEditor()!.getValue() !== 'changed second')
      throw new Error('Native editor did not accept the host update synchronously.');
    second.updateOptions({ panels: ['output', 'problems', 'input', 'inspector', 'logs'], stdin: 'input text' });
  });
  await expect(page.locator('#second .view-lines')).toContainText('changed second');
  await page.evaluate(() => {
    const second = window.embeddedHarness.get('second');
    second.getEditor()!.setPosition({ lineNumber: 1, column: 5 });
    window.embeddedHarness.get('first').dispose();
    second.getEditor()!.trigger('test', 'undo', {});
  });
  await expect.poll(() => page.evaluate(() => window.embeddedHarness.get('second').getValue())).toBe('code second');
  await page.evaluate(() => {
    const editor = window.embeddedHarness.get('second').getEditor()!;
    editor.setPosition({ lineNumber: 1, column: 1 });
    editor.focus();
    editor.trigger('test', 'editor.action.triggerSuggest', {});
  });
  await expect(page.getByRole('option', { name: /completion/ })).toBeVisible();
  expect(
    await page.evaluate(() =>
      window.embeddedHarness.requests.every(
        (request) =>
          request.languageId === 'text' && request.uri.startsWith(window.embeddedHarness.get('second').workspaceUri),
      ),
    ),
  ).toBe(true);
  await page.keyboard.press('Escape');
  await page.evaluate(() => window.embeddedHarness.get('second').run());
  await expect(page.locator('#second').getByTestId('execution-value')).toHaveText('↳code second');
  await expect(page.locator('#second .console-output')).toContainText('input text');
  await page.getByRole('textbox', { name: 'Host input' }).focus();
  await page.evaluate(() => window.embeddedHarness.get('second').clearOutput());
  await page.keyboard.press('Control+Enter');
  expect(await page.evaluate(() => window.embeddedHarness.get('second').getSnapshot().execution.kind)).toBe('idle');
  await page.evaluate(() => window.embeddedHarness.get('second').dispose());
  expect(await page.evaluate(() => window.embeddedHarness.errors)).toEqual([]);
});

test('cleans up immediate disposal before the editor loads and permits remounting', async ({ page }) => {
  await page.evaluate(async () => {
    const ready = window.embeddedHarness.mount('first', { value: 'discarded' });
    window.embeddedHarness.get('first').dispose();
    await ready.catch(() => {});
    await window.embeddedHarness.mount('first', { value: 'remounted' });
  });
  await expect(page.locator('#first .view-lines')).toContainText('remounted');
  expect(await page.evaluate(() => window.embeddedHarness.modelCount)).toBe(1);
  await page.evaluate(() => window.embeddedHarness.get('first').dispose());
  expect(await page.evaluate(() => [window.embeddedHarness.modelCount, window.embeddedHarness.installations])).toEqual([
    0, 0,
  ]);
});
