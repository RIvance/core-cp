import { expect, test } from '@playwright/test';

test('lists supplied examples and replaces source only after confirmation', async ({ page }) => {
  await page.goto('./');
  await page.evaluate(() => window.consumerHarness.mount());
  const editor = page.getByRole('textbox', { name: /Source code editor/ });
  await editor.focus();
  await page.keyboard.press('Control+a');
  await page.keyboard.insertText('unsaved edits');
  await page.getByRole('button', { name: 'Explore examples', exact: true }).click();
  const examples = page.getByRole('complementary', { name: 'Explore examples', exact: true });
  await examples.getByRole('button', { name: /Greeting/ }).click();
  await page.getByRole('button', { name: 'Cancel', exact: true }).click();
  await expect(page.locator('.view-lines')).toContainText('unsaved edits');
  await examples.getByRole('button', { name: /Greeting/ }).click();
  await page.getByRole('button', { name: 'Replace source', exact: true }).click();
  await expect(page.locator('.view-lines')).toContainText('independent package');
  await editor.focus();
  await page.keyboard.press('Control+z');
  await expect(page.locator('.view-lines')).toContainText('unsaved edits');
  await page.evaluate(() => window.consumerHarness.dispose());
});

test('installs all public entry points and loads both workers from a subdirectory deployment', async ({ page }) => {
  const failures: string[] = [];
  const workers: string[] = [];
  page.on('pageerror', (error) => failures.push(error.message));
  page.on('worker', (worker) => workers.push(worker.url()));
  await page.goto('./');
  await expect.poll(() => page.evaluate(() => Boolean(window.consumerHarness))).toBe(true);
  await expect(page.locator('#host')).toBeEmpty();
  expect(await page.evaluate(() => window.consumerHarness.lspExported)).toBe(true);
  await page.evaluate(() => window.consumerHarness.mount());
  await expect(page.getByTestId('source-editor')).toBeVisible();
  await expect(page.locator('.view-lines')).toContainText('independent package');
  await expect(page.getByRole('button', { name: 'Language service unavailable' })).toBeVisible();
  const editor = page.getByRole('textbox', { name: /Source code editor/ });
  await editor.focus();
  await page.keyboard.press('Control+a');
  await page.keyboard.insertText('consumer edits');
  await page.getByRole('button', { name: /^Run/ }).click();
  await expect(page.getByTestId('execution-value')).toHaveText('↳consumer edits');
  expect(await page.evaluate(() => window.consumerHarness.computeDiff())).toBe(1);
  expect(workers.some((url) => url.includes('editor.worker'))).toBe(true);
  expect(workers.some((url) => url.includes('runtime.worker'))).toBe(true);
  expect(workers.every((url) => new URL(url).pathname.startsWith('/embedded/'))).toBe(true);
  expect(await page.locator('.workbench').evaluate((element) => element.getBoundingClientRect().height)).toBe(780);
  await page.evaluate(() => {
    window.consumerHarness.dispose();
    window.consumerHarness.dispose();
  });
  await expect(page.locator('#host')).toBeEmpty();
  expect(await page.evaluate(() => [window.consumerHarness.installations, window.consumerHarness.modelCount])).toEqual([
    0, 0,
  ]);
  await page.evaluate(() => window.consumerHarness.mount());
  await expect(page.locator('.view-lines')).toContainText('consumer edits');
  await page.evaluate(() => window.consumerHarness.dispose());
  expect(failures).toEqual([]);
});

test('owns service and portal lifetimes and rejects a second mount without damaging the first', async ({ page }) => {
  await page.goto('./');
  await page.evaluate(() => window.consumerHarness.mount(true));
  await expect(page.getByRole('button', { name: 'Language service ready' })).toBeVisible();
  await expect(page.getByTestId('source-editor')).toBeVisible();
  await expect(page.evaluate(() => window.consumerHarness.mount())).rejects.toThrow('Dispose the mounted playground');
  await page.getByRole('button', { name: 'Workspace settings', exact: true }).click();
  await expect(page.getByRole('dialog')).toBeVisible();
  await page.evaluate(() => {
    window.consumerHarness.dispose();
    window.consumerHarness.dispose();
  });
  await expect(page.getByRole('dialog')).toHaveCount(0);
  expect(await page.evaluate(() => window.consumerHarness.serviceDisposals)).toBe(1);
  expect(await page.evaluate(() => window.consumerHarness.installations)).toBe(0);
  await page.keyboard.press('Control+Shift+p');
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await page.evaluate(() => window.consumerHarness.mount(true));
  await expect(page.getByRole('button', { name: 'Language service ready' })).toBeVisible();
  await page.evaluate(() => window.consumerHarness.dispose());
  expect(await page.evaluate(() => window.consumerHarness.serviceDisposals)).toBe(2);
});

test('keeps execution usable when host storage fails', async ({ page }) => {
  await page.goto('./');
  await page.evaluate(() => window.consumerHarness.mount(false, true));
  await expect(page.getByTestId('source-editor')).toBeVisible();
  await expect(page.getByText(/Storage disabled by consumer/).first()).toBeVisible();
  await page.getByRole('button', { name: /^Run/ }).click();
  await expect(page.getByTestId('execution-value')).toHaveText('↳independent package');
  await page.evaluate(() => window.consumerHarness.dispose());
});

test('dispatches a definition command from the palette through the supplied language service', async ({ page }) => {
  const errors: string[] = [];
  page.on('pageerror', (error) => errors.push(error.message));
  await page.goto('./');
  await page.evaluate(() => window.consumerHarness.mount(true));
  await expect(page.getByTestId('source-editor')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Language service ready' })).toBeVisible();
  await page.evaluate(() => {
    const uri = window.consumerHarness.modelUris[0];
    if (!uri) throw new Error('The source model was not created.');
    window.consumerHarness.replaceSource(uri, 'first\nsecond');
  });
  await page.getByRole('textbox', { name: /Source code editor/ }).focus();
  await page.keyboard.press('Control+End');
  await page.keyboard.press('Control+Shift+p');
  await page.getByRole('combobox', { name: 'Search commands' }).fill('Go to definition');
  await page.keyboard.press('Enter');
  await expect(page.getByRole('button', { name: /Ln 1, Col 1/ })).toBeVisible();
  await expect(page.getByText('This editor action is unavailable for the current language.')).not.toBeVisible();
  await page.evaluate(() => window.consumerHarness.dispose());
  expect(errors).toEqual([]);
});

test('customizes the application header and browser title and restores the host title on disposal', async ({
  page,
}) => {
  await page.goto('./');
  const title = await page.title();
  await page.evaluate(() =>
    window.consumerHarness.mount(false, false, { title: 'Custom editor', documentTitle: 'Custom browser tab' }),
  );
  await expect(page.locator('.brand strong')).toHaveText('Custom editor');
  await expect(page).toHaveTitle('Custom browser tab');
  await page.evaluate(() => window.consumerHarness.dispose());
  await expect(page).toHaveTitle(title);
  await page.evaluate(() => {
    window.consumerHarness.mount(false, false, { documentTitle: 'Temporary' });
    document.title = 'Host changed title';
    window.consumerHarness.dispose();
  });
  await expect(page).toHaveTitle('Host changed title');
});
