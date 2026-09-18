import { defineConfig } from '@playwright/test'
import base from './playwright.config'

if (!process.env.E2E_BASE_URL) throw new Error('Real backend gate requires E2E_BASE_URL; start RealBackendBrowserIT')
export default defineConfig(base, {
  testMatch: /real-.*\.spec\.ts/,
  testIgnore: [],
  fullyParallel: false,
  use: { ...base.use, actionTimeout: 15000 },
  forbidOnly: true,
  reporter: [['list'], ['json'], ['html', { open: 'never' }]],
  webServer: undefined,
})
