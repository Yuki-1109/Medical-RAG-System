import { defineConfig } from '@playwright/test'

const port = Number(process.env.FRONTEND_PORT || '5173')

export default defineConfig({
  testDir: './e2e',
  workers: 1,
  use: {
    baseURL: `http://127.0.0.1:${port}`,
    // Auth flows carry passwords/CSRF/Cookie. Do not save network traces or videos.
    trace: 'off',
    launchOptions: {
      executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH || undefined,
    },
  },
  webServer: {
    command: 'npm run dev',
    url: `http://127.0.0.1:${port}`,
    reuseExistingServer: false,
  },
})
