import { expect, test, type Page } from "@playwright/test";

async function replaceSource(page: Page, source: string): Promise<void> {
  await page.getByRole("textbox", { name: /Source code editor/ }).focus();
  await page.keyboard.press("ControlOrMeta+a");
  await page.keyboard.insertText(source);
}

async function chooseExample(page: Page, title: string): Promise<void> {
  await page.getByRole("button", { name: "Explore examples", exact: true }).click();
  await page.getByRole("complementary", { name: "Explore examples", exact: true })
    .getByRole("button", { name: new RegExp(title) }).click();
  await page.getByRole("button", { name: "Replace source", exact: true }).click();
}

test.beforeEach(async ({ page }) => {
  await page.goto("./");
  await expect(page.getByTestId("source-editor")).toBeVisible();
  await expect(page.getByRole("button", { name: "Language service ready" })).toBeVisible();
});

test("runs, steps and restarts the trie with workers loaded from a deployment subdirectory", async ({ page }, info) => {
  const errors: string[] = [];
  page.on("pageerror", (error) => errors.push(error.message));
  await replaceSource(page, "def main: Int = 20 + 22\n");
  await page.getByRole("button", { name: /^Run/ }).click();
  await expect(page.getByTestId("execution-value")).toHaveText("↳42");
  await expect(page.locator("#trie-entry")).toHaveText("Main::main");
  await expect(page.locator("#trie-step-number")).toHaveText("Step 1");
  await expect(page.locator("#trie-graph canvas").first()).toBeVisible();
  const step = page.getByRole("button", { name: "Step", exact: true });
  for (let fuel = 30; fuel > 0 && await step.isEnabled(); fuel -= 1) {
    const previous = await page.locator("#trie-step-number").textContent();
    await page.keyboard.press("F10");
    await expect.poll(async () => !(await step.isEnabled()) ||
      await page.locator("#trie-step-number").textContent() !== previous).toBe(true);
    await expect(page.getByRole("button", { name: "Stop trie", exact: true })).toBeHidden();
  }
  await expect(page.locator("#trie-status-message")).toHaveText("Normal form");
  await page.getByRole("button", { name: "Fit", exact: true }).click();
  await page.getByRole("button", { name: "Layout", exact: true }).click();
  await page.getByRole("button", { name: "Restart", exact: true }).click();
  await expect(page.locator("#trie-step-number")).toHaveText("Step 1");
  await expect(step).toBeEnabled();
  await page.getByRole("tab", { name: "Inspector", exact: true }).click();
  await expect(page.getByRole("combobox", { name: "Artifact", exact: true })).toContainText("Main::main.fiobs");
  await expect(page.getByRole("tabpanel").locator("pre")).toContainText("20 + 22");
  expect(page.workers().every((worker) => new URL(worker.url()).pathname.startsWith("/cp-test/"))).toBe(true);
  expect(errors).toEqual([]);
  await page.screenshot({ path: info.outputPath("observatory.png"), fullPage: true });
});

test("executes the selected entry while editing an imported file and publishes live diagnostics", async ({ page }) => {
  await chooseExample(page, "Module imports");
  await page.getByRole("button", { name: "Workspace and outline", exact: true }).click();
  await page.getByRole("treeitem", { name: "lib", exact: true }).click();
  await page.getByRole("treeitem", { name: "lib/Library.cp", exact: true }).click();
  await replaceSource(page, "module Examples::Library\ndef twice(value: Int): Int = value + value + 1\n");
  await page.getByRole("button", { name: /^Run/ }).click();
  await expect(page.getByTestId("execution-value")).toHaveText("↳43");
  await expect(page.locator("#trie-entry")).toHaveText("Examples::Application::main");
  await replaceSource(page, "module Examples::Library\ndef twice(value: Int) = 1 ,, 2\n");
  await page.getByRole("tab", { name: /Problems/ }).click();
  await expect(page.getByRole("tabpanel")).toContainText("not disjoint");
  await expect(page.getByRole("tabpanel")).toContainText("Library.cp");
  await page.reload();
  await expect(page.locator(".view-lines")).toContainText("1 ,, 2");
  await expect(page.getByRole("button", { name: "Language service ready" })).toBeVisible();
});

test("offers typed record fields through the Scala.js language-server worker", async ({ page }) => {
  await replaceSource(page, 'def main = { field = 42; file = "text" }.fi');
  await page.keyboard.press("Control+Space");
  const suggestions = page.locator(".suggest-widget.visible");
  await expect(suggestions).toBeVisible();
  await expect(suggestions.getByText("field", { exact: true })).toBeVisible();
  await expect(suggestions.getByText("file", { exact: true })).toBeVisible();
  await suggestions.getByText("field", { exact: true }).click();
  await page.keyboard.press("Enter");
  await expect(page.locator(".view-lines")).toContainText(".field");
  await page.getByRole("button", { name: /^Run/ }).click();
  await expect(page.getByTestId("execution-value")).toHaveText("↳42");
});

test("stops long runs and enforces the execution time limit", async ({ page }) => {
  await replaceSource(page, `def fibonacci(n: Int): Int =
  if n < 2 then 1 else fibonacci(n - 1) + fibonacci(n - 2)
def main: Int = fibonacci(42)
`);
  await page.getByRole("button", { name: /^Run/ }).click();
  await expect(page.locator("#trie-entry")).toHaveText("Main::main");
  await page.getByRole("button", { name: "Stop", exact: true }).click();
  await expect(page.getByRole("tabpanel")).toContainText(/stopped/i);
  await page.getByRole("button", { name: "Workspace settings", exact: true }).click();
  const limit = page.getByRole("textbox", { name: "Execution time limit", exact: true });
  await limit.fill("1");
  await limit.press("Tab");
  await page.keyboard.press("Escape");
  await page.getByRole("button", { name: /^Run/ }).click();
  await expect(page.getByRole("tabpanel")).toContainText(/time limit/i, { timeout: 5000 });
  await replaceSource(page, "def main: Int = 42\n");
  await page.getByRole("button", { name: /^Run/ }).click();
  await expect(page.getByTestId("execution-value")).toHaveText("↳42");
});

test("applies a light theme to the explorer and restores the saved workspace", async ({ page }) => {
  await replaceSource(page, "def main: Int = 17\n");
  await page.getByRole("button", { name: /^Run/ }).click();
  await expect(page.getByTestId("execution-value")).toHaveText("↳17");
  await page.getByRole("button", { name: "Color theme", exact: true }).click();
  await page.getByRole("combobox", { name: "Search color themes" }).fill("Daylight");
  await page.getByRole("option", { name: "Daylight", exact: true }).click();
  await expect(page.locator("#trie-explorer")).toHaveCSS("color-scheme", "light");
  await expect(page.locator("#trie-entry")).toHaveText("Main::main");
  await page.reload();
  await expect(page.locator(".view-lines")).toContainText("17");
  await expect(page.locator("#trie-explorer")).toHaveCSS("color-scheme", "light");
  await page.setViewportSize({ width: 760, height: 900 });
  const editor = await page.locator("#playground").boundingBox();
  const explorer = await page.locator("#trie-explorer").boundingBox();
  expect(explorer!.y).toBeGreaterThanOrEqual(editor!.height);
});
