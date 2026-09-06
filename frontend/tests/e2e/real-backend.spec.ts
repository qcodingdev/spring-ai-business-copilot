import { expect, test } from '@playwright/test'

/**
 * 前后端联合测试（真实后端 + 真实 PostgreSQL + 真实登录）。
 *
 * 仅在设置 E2E_BASE_URL 时运行：`E2E_BASE_URL=http://127.0.0.1:8080 npm run test:e2e -- real-backend.spec.ts`。
 * 与 mock 模式不同，这里不拦截任何 API——页面发出的每个请求都由打包后的 Spring Boot
 * 与真实数据库处理，验证真实登录、真实 CRUD 与新增能力界面的端到端行为。
 */

const baseUrl = process.env.E2E_BASE_URL ?? 'http://127.0.0.1:8080'

test.skip(!process.env.E2E_BASE_URL, 'real-backend joint tests require E2E_BASE_URL')

test.beforeEach(async ({ page }) => {
  // 真实 UI 登录：匿名会话种下 CSRF cookie → 表单提交 → Spring Security 302 回首页。
  await page.goto('/')
  await expect(page).toHaveURL(/\/login/)
  await page.locator('#login-form input[name="username"]')
    .fill(process.env.E2E_USERNAME ?? 'admin')
  await page.locator('#login-form input[name="password"]')
    .fill(process.env.E2E_PASSWORD ?? 'admin-change-me')
  await page.locator('#login-form button[type="submit"]').click()
  await expect(page).toHaveURL(/\/(\?.*)?$/)
  const session = await page.request.get(`${baseUrl}/api/session`)
  expect(session.ok()).toBe(true)
  expect((await session.json()).data.authenticated).toBe(true)
})

test('home shows the real personal task-run ledger (empty on a fresh database)', async ({ page }) => {
  await expect(page.getByTestId('my-task-runs')).toBeVisible()
  await expect(page.getByTestId('my-task-runs')).toContainText('还没有运行记录')
})

test('admin task-run timeline tab loads from the real runtime ledger', async ({ page }) => {
  await page.goto('/admin?tab=runs')
  await expect(page.getByTestId('task-runs-table')).toBeVisible()
  await expect(page.getByTestId('task-runs-table')).toContainText('当前筛选条件下没有运行记录。')
})

test('data metric dictionary persists through the real API and database', async ({ page }) => {
  await page.goto('/data?tab=governance')
  const metricKey = `e2e_metric_${Date.now()}`
  const metricForm = page.locator('form.connection-form').filter({ hasText: '新建指标版本' })
  await metricForm.getByLabel('指标标识').fill(metricKey)
  await metricForm.getByLabel('指标名称').fill('联合测试成交额')
  await metricForm.getByLabel('指标单位').fill('元')
  await metricForm.getByLabel('指标说明').fill('前后端联调自动创建的指标')
  await metricForm.getByLabel('完整只读指标查询 SQL').fill('select count(*) from public.orders limit 100')
  await metricForm.getByRole('button', { name: '新建指标版本' }).click()

  const metricCard = page.locator('article').filter({ hasText: metricKey })
  await expect(metricCard).toBeVisible()
  await expect(metricCard).toContainText('联合测试成交额')
  await expect(metricCard).toContainText('待审批')

  // 职责分离：创建者不能审批自己的指标（前端隐藏审批入口，服务端同样拒绝自审批）。
  await expect(metricCard.getByRole('button', { name: '审批' })).toHaveCount(0)
  await expect(metricCard).toContainText('待审批')
})

test('support quality case registry is read-only and backed by real review feedback', async ({ page }) => {
  await page.goto('/support?tab=quality')
  await expect(page.getByTestId('quality-cases-table')).toBeVisible()
  await expect(page.getByTestId('quality-case-new')).toHaveCount(0)
  await expect(page.getByText('人工修改待复核草稿时')).toBeVisible()
})

test('report records view renders the real enterprise records list', async ({ page }) => {
  await page.goto('/report?tab=records')
  // 全新数据库：记录区为空态；Data 交接产生的报告会在生成后展示"查看数据追溯"入口。
  await expect(page.getByText('暂无数据', { exact: true })).toBeVisible()
})

test('evaluation datasets run the first-40 harness catalog and export a gate report', async ({ page }) => {
  await page.goto('/admin?tab=evaluations')
  await expect(page.getByRole('heading', { name: '评测集管理与上线判断', exact: true })).toBeVisible()
  await expect(page.getByRole('heading', { name: '前 40 个业务基线场景 · v1', exact: true })).toBeVisible()
  await expect(page.getByRole('heading', { name: '评测用例 (40)', exact: true })).toBeVisible()

  await page.getByRole('button', { name: '开始评测', exact: true }).click()
  const reportLink = page.getByRole('link', { name: '导出 Markdown 评测报告', exact: true })
  await expect(reportLink).toBeVisible({ timeout: 15_000 })
  await expect(page.getByText('0/40', { exact: false })).toBeVisible()
  await expect(page.getByRole('combobox', { name: '评测运行', exact: true })).toContainText('未验证')

  const href = await reportLink.getAttribute('href')
  expect(href).toMatch(/^\/api\/governance\/evaluations\/runs\/.+\/report$/)
  const report = await page.request.get(`${baseUrl}${href}`)
  expect(report.ok()).toBe(true)
  const markdown = await report.text()
  expect(markdown).toContain('# Evaluation Gate Report')
  expect(markdown).toContain('NOT_VERIFIED never counts as passed')
})
