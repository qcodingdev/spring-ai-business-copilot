import { expect, test, type Page } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'

test.beforeEach(async ({ page }) => {
  const baseUrl = process.env.E2E_BASE_URL
  if (!baseUrl) return

  const sessionResponse = await page.request.get(`${baseUrl}/api/session`)
  expect(sessionResponse.ok()).toBe(true)
  const csrfCookie = (await page.context().cookies(baseUrl))
    .find((cookie) => cookie.name === 'XSRF-TOKEN')
  expect(csrfCookie, 'packaged E2E session must issue an XSRF-TOKEN cookie').toBeDefined()

  const loginResponse = await page.request.post(`${baseUrl}/login`, {
    form: {
      username: process.env.E2E_USERNAME ?? 'admin',
      password: process.env.E2E_PASSWORD ?? 'admin-change-me',
      _csrf: decodeURIComponent(csrfCookie!.value),
    },
    maxRedirects: 0,
  })
  expect(loginResponse.status()).toBe(302)
})

async function mockSession(page: Page): Promise<void> {
  await page.route('**/api/session', async (route) => {
    await route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify({
        data: {
          authenticated: true,
          username: 'fictional.operator',
          roles: ['ADMIN', 'OPERATOR'],
          runtimeMode: 'self-hosted',
          publicDemo: false,
          aiEnabled: true,
        },
        success: true,
        errorCode: null,
        message: null,
        requestId: 'e2e-session-request',
        timestamp: new Date().toISOString(),
      }),
    })
  })
}

test('defaults to Chinese and exposes the five business copilots', async ({ page }, testInfo) => {
  await mockSession(page)
  await page.addInitScript(() => localStorage.clear())
  await page.goto('/')
  await expect(page.locator('html')).toHaveAttribute('lang', 'zh-CN')
  for (const name of ['数据分析', '企业知识', '客户服务', '经营报告', '招聘与员工服务']) {
    await expect(page.getByRole('link', { name, exact: true })).toBeVisible()
  }
  if (process.env.CAPTURE_VISUALS === '1') {
    await page.screenshot({
      path: `../assets/workbench-v2.3-${testInfo.project.name}.png`,
      fullPage: true,
    })
  }
})

test('workbench has no serious accessibility violations or horizontal overflow', async ({ page }) => {
  await mockSession(page)
  await page.addInitScript(() => localStorage.setItem('businessCopilot.locale', 'en-US'))
  await page.goto('/data')
  const results = await new AxeBuilder({ page }).analyze()
  expect(results.violations.filter((violation) =>
    violation.impact === 'serious' || violation.impact === 'critical')).toEqual([])
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  await page.keyboard.press('Tab')
  await expect(page.locator(':focus')).toBeVisible()
})

test('completes all five primary workflows in the default Chinese locale', async ({ page }) => {
  await mockSession(page)
  const workflows = [
    ['/data', '**/api/data-copilot/sql-candidates', '业务问题', '生成 SQL 候选'],
    ['/knowledge', '**/api/knowledge-copilot/questions', '知识问题', '检索并回答'],
    ['/support', '**/api/support-copilot/tickets/analyze', '客户消息', '分析工单并生成草稿'],
    ['/report', '**/api/report-copilot/reports/generate', '来源数据', '生成报告草稿'],
    ['/hr', '**/api/resume-copilot/jobs/draft', '岗位需求', '生成岗位画像和 JD'],
  ] as const
  for (const [path, endpoint, field, action] of workflows) {
    await page.route(endpoint, async (route) => {
      await route.fulfill({
        contentType: 'application/json',
        body: JSON.stringify({
          data: {
            status: 'DRAFTED',
            executable: false,
            sql: path === '/data' ? 'SELECT 1 LIMIT 1' : null,
            answer: '基于虚构证据的结果。',
            evidence: ['虚构业务证据。'],
            warnings: [],
          },
          success: true,
          errorCode: null,
          message: null,
          requestId: `e2e-zh-${path.slice(1)}`,
          timestamp: new Date().toISOString(),
        }),
      })
    })
    await page.goto(path)
    if (path === '/report') await page.getByLabel('报告标题').fill('虚构周报')
    if (path === '/hr') await page.getByLabel('职位名称').fill('虚构 Java 工程师')
    await page.getByLabel(field).fill('不含个人信息的虚构业务输入。')
    await page.getByRole('button', { name: action }).click()
    await expect(page.getByText(`e2e-zh-${path.slice(1)}`)).toBeVisible()
  }
})

test('login and Admin expose the same persistent language switch', async ({ page }) => {
  await page.route('**/api/session', async (route) => {
    await route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify({
        data: {
          authenticated: false,
          username: null,
          roles: [],
          runtimeMode: 'public-demo',
          publicDemo: true,
          aiEnabled: false,
        },
        success: true,
        requestId: 'e2e-anonymous-session',
        timestamp: new Date().toISOString(),
      }),
    })
  })
  await page.goto('/login')
  await page.locator('[data-testid="language-switcher"]:visible').click()
  await expect(page.getByRole('button', { name: 'Sign in' })).toBeVisible()
  await page.unroute('**/api/session')

  await mockSession(page)
  await page.route('**/api/admin/diagnostics', async (route) => {
    await route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify({
        data: { runtimeMode: 'self-hosted', aiEnabled: true },
        success: true,
        requestId: 'e2e-admin-request',
        timestamp: new Date().toISOString(),
      }),
    })
  })
  await page.route('**/api/admin/demo-data/initialize', async (route) => {
    await route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify({
        data: { id: 'fictional-initialize-job', status: 'PENDING' },
        success: true,
        requestId: 'e2e-admin-initialize',
        timestamp: new Date().toISOString(),
      }),
    })
  })
  await page.route('**/api/admin/demo-data/reset-intents', async (route) => {
    await route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify({
        data: {
          willDelete: { fictionalRecords: 15 },
          resetToken: 'one-time-reset-token',
          expiresAt: new Date(Date.now() + 60_000).toISOString(),
          requiredConfirmationText: '恢复公网演示初始数据',
        },
        success: true,
        requestId: 'e2e-admin-reset-intent',
        timestamp: new Date().toISOString(),
      }),
    })
  })
  await page.route('**/api/admin/demo-data/reset', async (route) => {
    await route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify({
        data: { id: 'fictional-reset-job', status: 'COMPLETED' },
        success: true,
        requestId: 'e2e-admin-reset',
        timestamp: new Date().toISOString(),
      }),
    })
  })
  await page.goto('/admin')
  await expect(page.getByRole('heading', { name: 'System health and experience management' })).toBeVisible()
  await expect(page.locator('html')).toHaveAttribute('lang', 'en-US')
  await page.getByRole('button', { name: 'Prepare experience data' }).click()
  await page.getByRole('dialog')
    .getByRole('button', { name: 'Prepare experience data' })
    .click()
  await expect(page.getByText('e2e-admin-initialize')).toBeVisible()
  await page.getByRole('button', { name: 'Preview restore impact' }).click()
  await page.getByLabel('Enter the confirmation text below to continue')
    .fill('恢复公网演示初始数据')
  await page.getByRole('button', { name: 'Restore experience area' }).click()
  await page.getByRole('dialog')
    .getByRole('button', { name: 'Restore experience area' })
    .click()
  await expect(page.getByText('e2e-admin-reset')).toBeVisible()
})

test('public preview uses only curated confirmed scenarios', async ({ page }) => {
  await page.route('**/api/session', async (route) => {
    await route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify({
        data: {
          authenticated: true,
          username: 'fictional.preview',
          roles: ['OPERATOR'],
          runtimeMode: 'public-demo',
          publicDemo: true,
          aiEnabled: false,
        },
        success: true,
        requestId: 'e2e-public-session',
        timestamp: new Date().toISOString(),
      }),
    })
  })
  await page.route('**/api/demo/scenarios', async (route) => {
    await route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify({
        data: [{
          scenarioId: 'data-safe-query',
          module: 'DATA',
          title: '虚构销售查询',
          description: '只读查询虚构订单。',
          inputTemplate: '查询虚构订单总额。',
          dataScope: 'FICTIONAL_ONLY',
          version: 1,
          fallbackResultAvailable: true,
        }],
        success: true,
        requestId: 'e2e-public-scenarios',
        timestamp: new Date().toISOString(),
      }),
    })
  })
  await page.route('**/api/demo/scenarios/execute', async (route) => {
    await route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify({
        data: { execution: { status: 'PREGENERATED', result: { summary: '虚构范例结果。' } } },
        success: true,
        requestId: 'e2e-public-execution',
        timestamp: new Date().toISOString(),
      }),
    })
  })
  await page.goto('/')
  await page.getByRole('button', { name: /数据分析 · 虚构销售查询/ }).click()
  await page.getByLabel('业务需求').fill('查询虚构订单总额。')
  await page.getByRole('button', { name: '开始体验' }).click()
  await page.getByRole('dialog').getByRole('button', { name: '开始体验' }).click()
  await expect(page.getByText('e2e-public-execution')).toBeVisible()
  await page.goto('/data')
  await expect(page.getByRole('heading', { name: '可直接体验的业务场景', level: 1 })).toBeVisible()
})

test('switches to English, persists it, and sends Accept-Language', async ({ page }) => {
  await mockSession(page)
  let acceptLanguage = ''
  await page.route('**/api/data-copilot/sql-candidates', async (route) => {
    acceptLanguage = route.request().headers()['accept-language'] ?? ''
    await route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify({
        data: { executable: false, sql: 'SELECT 1 LIMIT 1', assumptions: [], warnings: [] },
        success: true,
        errorCode: null,
        message: null,
        requestId: 'e2e-data-request',
        timestamp: new Date().toISOString(),
      }),
    })
  })
  await page.goto('/data')
  await page.locator('[data-testid="language-switcher"]:visible').click()
  await expect(page.locator('html')).toHaveAttribute('lang', 'en-US')
  await page.getByLabel('Business question').fill('Show one fictional record')
  await page.getByRole('button', { name: 'Generate SQL candidate' }).click()
  await expect(page.getByText('SQL preview')).toBeVisible()
  expect(acceptLanguage).toBe('en-US')
  await page.reload()
  await expect(page.locator('html')).toHaveAttribute('lang', 'en-US')
})

for (const scenario of [
  ['/knowledge', 'Knowledge question', 'Retrieve and answer', '**/api/knowledge-copilot/questions'],
  ['/support', 'Customer message', 'Analyze ticket and draft reply', '**/api/support-copilot/tickets/analyze'],
  ['/report', 'Source data', 'Generate report draft', '**/api/report-copilot/reports/generate'],
  ['/hr', 'Job requirements', 'Generate job profile and description', '**/api/resume-copilot/jobs/draft'],
] as const) {
  test(`renders the ${scenario[0]} core workflow in English`, async ({ page }) => {
    await mockSession(page)
    await page.route(scenario[3], async (route) => {
      await route.fulfill({
        contentType: 'application/json',
        body: JSON.stringify({
          data: {
            status: 'DRAFTED',
            answer: 'Grounded fictional answer.',
            summary: 'Fictional review draft.',
            citations: [{ source: 'fictional-source', excerpt: 'Fictional evidence.' }],
            evidence: ['Fictional evidence.'],
            warnings: [],
          },
          success: true,
          errorCode: null,
          message: null,
          requestId: 'e2e-module-request',
          timestamp: new Date().toISOString(),
        }),
      })
    })
    await page.addInitScript(() => localStorage.setItem('businessCopilot.locale', 'en-US'))
    await page.goto(scenario[0])
    await expect(page.getByLabel(scenario[1])).toBeVisible()
    if (scenario[0] === '/report') await page.getByLabel('Report title').fill('Fictional weekly report')
    if (scenario[0] === '/hr') await page.getByLabel('Job title').fill('Fictional Java engineer')
    await page.getByLabel(scenario[1]).fill('Fictional business input with no personal data.')
    await page.getByRole('button', { name: scenario[2] }).click()
    await expect(page.getByText('e2e-module-request')).toBeVisible()
    await expect(page.locator('.evidence-list').getByText('Fictional evidence.', { exact: true })).toBeVisible()
  })
}
