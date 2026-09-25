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
      command: 'node tools/serve-production.cjs',
      url: 'http://127.0.0.1:4300',
      reuseExistingServer: false,
    },
  ],
});
