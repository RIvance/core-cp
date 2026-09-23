import { expect, test } from '@playwright/test';

test.beforeEach(async ({ page }) => {
  await page.goto('http://127.0.0.1:4174/tests/browser/fixtures/editor.html');
  await expect(page.locator('.monaco-editor')).toBeVisible();
});

test('renders and refreshes inlay hints and semantic colors for an independent adapter', async ({ page }) => {
  await expect(page.locator('.view-lines')).toContainText(': Int');
  const color = () =>
    page.evaluate(() => {
      const token = [...document.querySelectorAll('.view-line span')].find(
        (element) => element.children.length === 0 && element.textContent === 'answer',
      );
      return token ? getComputedStyle(token).color : undefined;
    });
  await expect.poll(color).toBe('rgb(227, 198, 135)');
  await page.evaluate(() => window.editorHarness.refresh(': Refined', 'type'));
  await expect(page.locator('.view-lines')).toContainText(': Refined');
  await expect.poll(color).toBe('rgb(130, 214, 207)');
  await page.evaluate(() => window.editorHarness.editor.updateOptions({ theme: 'playground-sand' }));
  await expect.poll(color).toBe('rgb(33, 108, 101)');
  await page.evaluate(() =>
    window.editorHarness.editor.updateOptions({
      inlayHints: { enabled: 'off' },
      'semanticHighlighting.enabled': false,
    }),
  );
  await expect(page.locator('.view-lines')).not.toContainText(': Refined');
  await expect.poll(color).not.toBe('rgb(33, 108, 101)');
  expect(await page.evaluate(() => window.editorHarness.errors)).toEqual([]);
});

test('resolves and applies a real Monaco quick fix, retaining diagnostic context and undo', async ({ page }) => {
  await page.evaluate(() => {
    window.editorHarness.editor.setPosition({ lineNumber: 1, column: 14 });
    window.editorHarness.editor.focus();
    window.editorHarness.editor.trigger('contract-test', 'editor.action.quickFix', {});
  });
  await expect(page.getByRole('option', { name: 'Use 42, Quick Fix' })).toBeVisible();
  await page.mouse.move(100, 100);
  await page.getByRole('option', { name: 'Use 42, Quick Fix' }).click();
  await expect.poll(() => page.evaluate(() => window.editorHarness.model.getValue())).toContain('= 42;');
  expect(await page.evaluate(() => window.editorHarness.resolvedActions)).toBeGreaterThan(0);
  expect(await page.evaluate(() => window.editorHarness.diagnostics)).toMatchObject([
    { code: 41, data: { literal: 1 } },
  ]);
  await page.evaluate(() => window.editorHarness.editor.trigger('contract-test', 'undo', {}));
  await expect.poll(() => page.evaluate(() => window.editorHarness.model.getValue())).toContain('= 41;');
  expect(await page.evaluate(() => window.editorHarness.errors)).toEqual([]);
});

test('rejects stale, out-of-workspace, overlapping, and invalid edits as whole actions', async ({ page }) => {
  const results = await page.evaluate(() => {
    const uri = window.editorHarness.model.uri.toString();
    const range = { start: { line: 0, character: 4 }, end: { line: 0, character: 10 } };
    const edit = { range, text: 'renamed' };
    return [
      [{ uri, version: 0, edits: [edit] }],
      [
        { uri, edits: [edit] },
        { uri: 'file:///outside.fixture', edits: [edit] },
      ],
      [{ uri, edits: [edit, edit] }],
      [{ uri, edits: [{ range: { ...range, end: { line: 5, character: 0 } }, text: 'bad' }] }],
      [{ uri, version: 1, edits: [edit] }],
    ].map((changes) => window.editorHarness.validateEdits(changes));
  });
  expect(results).toEqual(['rejected', 'rejected', 'rejected', 'rejected', 'ready']);
  expect(await page.evaluate(() => window.editorHarness.model.getValue())).toContain('= 41;');
});

test('discards hints computed for an old document and cancels requests when detached', async ({ page }) => {
  await expect(page.locator('.view-lines')).toContainText(': Int');
  await page.evaluate(() => {
    window.editorHarness.holdHints();
    window.editorHarness.refresh(': Stale', 'type');
  });
  await expect.poll(() => page.evaluate(() => window.editorHarness.hintsPending)).toBe(true);
  await page.evaluate(() => {
    window.editorHarness.model.setValue('replacement source');
    window.editorHarness.releaseHints();
  });
  await expect(page.locator('.view-lines')).toContainText('replacement source');
  await expect(page.locator('.view-lines')).not.toContainText(': Stale');
  await page.evaluate(() => {
    window.editorHarness.holdHints();
    window.editorHarness.refresh(': Disposed', 'type');
  });
  await expect.poll(() => page.evaluate(() => window.editorHarness.hintsPending)).toBe(true);
  await page.evaluate(() => {
    window.editorHarness.dispose();
    window.editorHarness.releaseHints();
  });
  await expect.poll(() => page.evaluate(() => window.editorHarness.cancelled)).toBeGreaterThan(0);
  expect(await page.evaluate(() => window.editorHarness.errors)).toEqual([]);
});
