import { defineConfig } from '@playwright/test';

export default defineConfig({
  testMatch: '*.spec.ts',
  workers: 1,
  use: {
    baseURL: 'http://127.0.0.1:4175/embedded/',
    viewport: { width: 1440, height: 950 },
    launchOptions: { executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH },
  },
  webServer: {
    command: 'npx vite preview --host 127.0.0.1 --port 4175 --strictPort',
    url: 'http://127.0.0.1:4175/embedded/',
  },
});
