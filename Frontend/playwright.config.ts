import { defineConfig } from '@playwright/test';
export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  workers: 1,
  reporter: [['list'], ['html', { open: 'never' }]],
  use: { baseURL: 'http://127.0.0.1:4201', browserName: 'chromium', trace: 'retain-on-failure' },
  webServer: [
    {
      command: 'npm start -- --host 127.0.0.1 --port 4201',
      url: 'http://127.0.0.1:4201',
      reuseExistingServer: false,
      timeout: 120000,
    },
    {
      // Never serve a stale dist/ bundle: a previous npm run build can predate
      // the branch under test (notably when running a targeted E2E spec).
      command: 'npm run build && node tools/serve-production.cjs',
      url: 'http://127.0.0.1:4300',
      reuseExistingServer: false,
      timeout: 180000,
    },
  ],
});
