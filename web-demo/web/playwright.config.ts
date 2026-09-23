import { defineConfig } from "@playwright/test";

export default defineConfig({
  testDir: "tests/browser",
  timeout: 30_000,
  use: {
    baseURL: "http://127.0.0.1:4178/cp-test/",
    viewport: { width: 1440, height: 960 },
    launchOptions: { executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE },
    screenshot: "only-on-failure",
    trace: "retain-on-failure"
  },
  webServer: {
    command: "npx vite preview --host 127.0.0.1 --port 4178 --strictPort --base=/cp-test/",
    url: "http://127.0.0.1:4178/cp-test/",
    reuseExistingServer: !process.env.CI
  }
});
