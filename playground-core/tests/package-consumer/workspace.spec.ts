import { expect, test, type Page } from '@playwright/test';
import { readFile } from 'node:fs/promises';
import { Workspace, getWorkspaceArchiveFormats, workspaceLimits } from '@language-playground/ide/workspace';

async function command(page: Page, label: string) {
  await page.keyboard.press('Control+Shift+p');
  await page.getByRole('combobox', { name: 'Search commands' }).fill(label);
  await page.getByRole('option').filter({ hasText: label }).click();
}
async function createFile(page: Page, path: string) {
  await page.getByRole('button', { name: 'New source file', exact: true }).click();
  await page.getByRole('textbox', { name: 'Path', exact: true }).fill(path);
  await page.getByRole('button', { name: 'Create', exact: true }).click();
  await expect(page.getByRole('dialog')).toHaveCount(0);
}

test('creates nested files, moves folders, and keeps closed files available with their undo history', async ({
  page,
}) => {
  await page.goto('./');
  await page.evaluate(() => window.consumerHarness.mount(true));
  await createFile(page, 'src/library/main.txt');
  const source = page.getByRole('textbox', { name: /Source code editor/ });
  await source.focus();
  await page.keyboard.insertText('nested source');
  await page.getByRole('button', { name: 'Close src/library/main.txt', exact: true }).click();
  await expect(page.getByRole('treeitem', { name: 'src/library/main.txt', exact: true })).toBeVisible();
  await page.getByRole('treeitem', { name: 'src/library/main.txt', exact: true }).click();
  await expect(page.locator('.view-lines')).toContainText('nested source');
  await source.focus();
  await page.keyboard.press('Control+z');
  await expect(page.locator('.view-lines')).not.toContainText('nested source');
  await page.keyboard.insertText('moved source');
  await page.getByRole('button', { name: 'Actions for src', exact: true }).click();
  await page.getByRole('menuitem', { name: 'Move or rename', exact: true }).click();
  await page.getByRole('textbox', { name: 'Path', exact: true }).fill('packages');
  await page.getByRole('button', { name: 'Move', exact: true }).click();
  await expect(page.getByRole('treeitem', { name: 'packages/library/main.txt', exact: true })).toBeVisible();
  await expect(page.getByRole('tab', { name: /packages\/library\/main.txt/ })).toHaveAttribute('aria-selected', 'true');
  await expect(page.locator('.view-lines')).toContainText('moved source');
  expect(await page.evaluate(() => window.consumerHarness.modelUris)).toContain(
    'file:///workspace/packages/library/main.txt',
  );
  expect(await page.evaluate(() => window.consumerHarness.modelUris)).not.toContain(
    'file:///workspace/src/library/main.txt',
  );
  await page.getByRole('treeitem', { name: 'packages', exact: true }).focus();
  await page.keyboard.press('ArrowLeft');
  await expect(page.getByRole('treeitem', { name: 'packages', exact: true })).toHaveAttribute('aria-expanded', 'false');
  await page.keyboard.press('ArrowRight');
  await page.keyboard.press('ArrowRight');
  await expect(page.getByRole('treeitem', { name: 'packages/library', exact: true })).toBeFocused();
  await page.keyboard.press('F2');
  await expect(page.getByRole('dialog', { name: 'Move or rename' })).toBeVisible();
  await page.getByRole('button', { name: 'Cancel', exact: true }).click();
  await page.getByRole('button', { name: 'Actions for packages', exact: true }).click();
  await page.getByRole('menuitem', { name: 'Delete', exact: true }).click();
  await page.getByRole('button', { name: 'Delete', exact: true }).click();
  await expect(page.getByRole('treeitem', { name: 'packages', exact: true })).toHaveCount(0);
  await expect(page.getByRole('tab', { name: /packages\/library\/main.txt/ })).toHaveCount(0);
  await command(page, 'Open local history');
  await expect(page.locator('.history-item')).toHaveCount(2);
});

for (const format of getWorkspaceArchiveFormats()) {
  test(`${format.label} imports and downloads complete folder structures through the installed package`, async ({
    page,
  }) => {
    const workspace = new Workspace({
      files: [
        { id: 'entry', path: 'project/src/main.txt', text: 'archive source\r\n🌍' },
        { id: 'other', path: 'project/lib/資料 #%!().txt', text: 'dependency' },
      ],
      directories: ['project/assets/empty'],
    });
    const input = await format.write(workspace);
    await page.goto('./');
    await page.evaluate(() => window.consumerHarness.mount());
    await page.getByLabel('Import files, project, or archive').setInputFiles({
      name: `example${format.extensions[0]}`,
      mimeType: format.mediaType,
      buffer: Buffer.from(await input.arrayBuffer()),
    });
    await expect(page.getByRole('dialog', { name: /^Open “example/ })).toBeVisible();
    await expect(page.getByRole('combobox', { name: 'Entry file', exact: true })).toHaveValue('project/src/main.txt');
    await page.getByRole('button', { name: 'Replace source', exact: true }).click();
    await expect(page.getByRole('treeitem', { name: 'project/src/main.txt', exact: true })).toBeVisible();
    await expect(page.locator('.view-lines')).toContainText('archive source');
    await expect(page.locator('.saved-label')).toHaveText('Saved locally');
    await page.reload();
    await page.evaluate(() => window.consumerHarness.mount());
    await expect(page.getByRole('tab', { name: /project\/src\/main.txt/ })).toBeVisible();
    await page.getByRole('treeitem', { name: 'project/lib', exact: true }).click();
    await page.getByRole('treeitem', { name: 'project/lib/資料 #%!().txt', exact: true }).click();
    await expect(page.locator('.view-lines')).toContainText('dependency');
    await page.getByRole('textbox', { name: /Source code editor/ }).focus();
    await page.keyboard.press('Control+End');
    await page.keyboard.insertText(' edited');
    await page.keyboard.press('Control+z');
    await expect(page.locator('.view-lines')).toContainText('dependency');
    await expect(page.locator('.view-lines')).not.toContainText('edited');
    const downloaded = page.waitForEvent('download');
    await command(page, `Export ${format.label} archive`);
    const download = await downloaded;
    const path = await download.path();
    expect(path).not.toBeNull();
    const exported = await format.read(new Blob([new Uint8Array(await readFile(path))]));
    expect(exported.files.map(({ path, text }) => ({ path, text }))).toEqual(
      workspace.files.map(({ path, text }) => ({ path, text })),
    );
    expect(exported.directories).toEqual(workspace.directories);
    expect(download.suggestedFilename()).toBe(`workspace${format.extensions[0]}`);
  });
}

test('failed imports leave the current workspace intact', async ({ page }) => {
  await page.goto('./');
  await page.evaluate(() => window.consumerHarness.mount());
  await page.getByLabel('Import files, project, or archive').setInputFiles([
    { name: 'new.txt', mimeType: 'text/plain', buffer: Buffer.from('valid') },
    { name: 'image.bin', mimeType: 'application/octet-stream', buffer: Buffer.from([0, 255]) },
  ]);
  await expect(page.getByText('“image.bin” is not a UTF-8 text file.', { exact: true })).toBeVisible();
  await expect(page.getByRole('tab', { name: /source.txt/ })).toBeVisible();
  await expect(page.getByRole('treeitem', { name: 'new.txt', exact: true })).toHaveCount(0);
  await expect(page.locator('.view-lines')).toContainText('independent package');
});

test('rejects edits exceeding workspace limits without diverging from the saved source', async ({ page }) => {
  const failures: string[] = [];
  page.on('pageerror', (error) => failures.push(error.message));
  await page.goto('./');
  await page.evaluate(() => window.consumerHarness.mount());
  const source = page.getByRole('textbox', { name: /Source code editor/ });
  await source.focus();
  await page.keyboard.press('Control+a');
  await page.keyboard.insertText('valid source');
  await page.keyboard.press('Control+a');
  await page.evaluate(
    (text) => window.consumerHarness.replaceSource('file:///workspace/source.txt', text),
    'x'.repeat(workspaceLimits.fileBytes + 1),
  );
  await expect(
    page.getByText(`The file “source.txt” exceeds ${workspaceLimits.fileBytes} UTF-8 bytes.`, { exact: true }),
  ).toBeVisible();
  await expect(page.locator('.view-lines')).toContainText('valid source');
  await page.evaluate(() => window.consumerHarness.replaceSource('file:///workspace/source.txt', 'another valid edit'));
  await source.focus();
  await page.keyboard.press('Control+z');
  await expect(page.locator('.view-lines')).toContainText('valid source');
  await page.getByRole('button', { name: /^Run/ }).click();
  await expect(page.getByTestId('execution-value')).toHaveText('↳valid source');
  expect(failures).toEqual([]);
});
