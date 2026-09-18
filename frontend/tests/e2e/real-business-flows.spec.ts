import { expect, test, type Page, type Browser } from '@playwright/test'

if (!process.env.E2E_BASE_URL) throw new Error('Real business flows require E2E_BASE_URL')
const policy = '虚构服务流程：请提供订单编号，客服核验后回复处理进度。'

function newRoleContext(browser: Browser) {
  const { viewport, deviceScaleFactor, isMobile, hasTouch, userAgent } = test.info().project.use
  return browser.newContext({ baseURL: process.env.E2E_BASE_URL, viewport, deviceScaleFactor, isMobile, hasTouch, userAgent })
}

async function login(page: Page, role: 'admin' | 'operator' | 'reviewer'): Promise<void> {
  await page.goto('/login')
  await page.locator('#login-form input[name="username"]').fill(role)
  await page.locator('#login-form input[name="password"]').fill(`${role}-change-me`)
  await page.locator('#login-form button[type="submit"]').click()
  await expect(page).toHaveURL(/\/(\?.*)?$/)
}
async function response(page: Page, path: string, action: () => Promise<unknown>): Promise<any> {
  const [result] = await Promise.all([
    page.waitForResponse(r => r.url().includes(path) && r.request().method() !== 'GET', { timeout: 30000 }),
    action(),
  ])
  const body = await result.json()
  expect(result.ok(), JSON.stringify(body)).toBe(true)
  expect(body.success, JSON.stringify(body)).toBe(true)
  return body.data
}
async function post(page: Page, path: string, data: unknown): Promise<any> {
  await page.request.get('/api/session')
  const token = (await page.context().cookies()).find(c => c.name === 'XSRF-TOKEN')
  expect(token).toBeDefined()
  return page.request.post(path, { data, headers: { 'X-XSRF-TOKEN': decodeURIComponent(token!.value) } })
}
async function approve(browser: Browser, path: string, subject: string): Promise<void> {
  const context = await newRoleContext(browser)
  try {
    const reviewer = await context.newPage()
    await login(reviewer, 'reviewer')
    await reviewer.goto(path)
    const card = reviewer.locator('.review-task-card').filter({ has: reviewer.getByText(`#${subject}`, { exact: true }) })
    await expect(card).toBeVisible()
    await card.getByLabel('复核意见', { exact: true }).fill('已核对虚构测试来源与业务范围。')
    await response(reviewer, '/decision', () => card.getByRole('button', { name: '批准', exact: true }).click())
    await expect(card).toHaveCount(0)
  } catch (error) {
    for (const current of context.pages()) {
      await test.info().attach('independent-review-page', { body: await current.locator('body').innerText(), contentType: 'text/plain' })
    }
    throw error
  } finally { await context.close() }
}
async function seedPolicy(page: Page, name: string): Promise<void> {
  const uploaded = await post(page, '/api/knowledge-copilot/documents', { fileName: `${name}.txt`, content: `${policy}\n测试来源：${name}`, category: '服务流程' })
  expect(uploaded.ok(), await uploaded.text()).toBe(true)
  const id = (await uploaded.json()).data.documentId
  await expect.poll(async () => {
    const result = await page.request.get('/api/knowledge-copilot/documents')
    return (await result.json()).data.find((d: any) => d.id === id)?.indexStatus
  }, { timeout: 30000 }).toBe('INDEXED')
}

test('Data: independent review, one-time execution, refresh, export and Report handoff', async ({ page, browser }, info) => {
  await login(page, 'operator')
  const metricKey = `orders_${info.project.name.replaceAll('-', '_')}_${Date.now()}`
  const savedMetric = await post(page, '/api/data-copilot/metrics', {
    metricKey, displayName: metricKey, description: '订单表每行是一笔订单，统计期间内订单总数', unit: '笔',
    expressionSql: "SELECT count(*) AS order_count FROM public.orders WHERE created_at >= '2026-09-01' AND created_at < '2026-09-08' LIMIT 1",
  })
  expect(savedMetric.ok(), await savedMetric.text()).toBe(true)
  const metricId = (await savedMetric.json()).data.id
  const adminContext = await newRoleContext(browser)
  try {
    const admin = await adminContext.newPage()
    await login(admin, 'admin')
    const approvedMetric = await post(admin, `/api/data-copilot/metrics/${metricId}/approve`, {})
    expect(approvedMetric.ok(), await approvedMetric.text()).toBe(true)
  } finally { await adminContext.close() }
  await page.goto('/data')
  await page.getByLabel('业务问题').fill(`统计 2026-09-01 至 2026-09-07 的 ${metricKey} 订单总数，单位为笔，按订单编号去重。`)
  const candidate = await response(page, '/sql-candidates', () => page.getByRole('button', { name: '生成 SQL 候选' }).click())
  expect(candidate.executable, JSON.stringify(candidate)).toBe(true)
  await expect(page.locator('.task-panel')).toContainText('SELECT count(*)')
  await approve(browser, '/data?tab=records', candidate.candidateId)
  await page.getByRole('button', { name: '刷新复核状态' }).click()
  await page.getByRole('button', { name: '确认查询', exact: true }).click()
  const execution = await response(page, '/execute', () => page.getByRole('dialog').getByRole('button', { name: '确认执行只读查询' }).click())
  expect(execution.table.rowCount).toBe(1)
  await expect(page.getByRole('heading', { name: '本次处理结果' })).toBeVisible()
  const replay = await post(page, `/api/data-copilot/sql-candidates/${candidate.candidateId}/execute`, { confirmationToken: candidate.confirmationToken })
  expect(replay.status()).toBe(409)
  const stored = await page.request.get('/api/data-copilot/query-results')
  const row = (await stored.json()).data.find((item: any) => item.candidateId === candidate.candidateId)
  expect(row).toBeDefined()
  const csv = await page.request.get(`/api/data-copilot/query-results/${row.id}/csv`)
  expect(csv.ok()).toBe(true)
  expect(await csv.text()).toContain('order_count')
  await page.goto('/data?tab=handoff')
  await page.reload()
  const resultRow = page.locator('tr').filter({ has: page.getByText(`#${row.id}`, { exact: true }) })
  await resultRow.getByRole('button', { name: '选择结果' }).click()
  const title = `真实交接-${info.project.name}-${Date.now()}`
  await page.getByLabel('交接标题').fill(title)
  await page.getByLabel('将数值列登记为已核验的期间总量').check()
  await page.getByRole('combobox', { name: '指标名称', exact: true }).selectOption(metricKey)
  await page.getByLabel('结果数值列').selectOption('order_count')
  await page.getByLabel('业务周期开始').fill('2026-09-01')
  await page.getByLabel('业务周期结束（含当天）').fill('2026-09-07')
  await expect(page.getByRole('button', { name: '创建结果交接' })).toBeDisabled()
  await page.getByLabel('我已核对 SQL、指标口径', { exact: false }).check()
  await page.getByRole('button', { name: '创建结果交接' }).click()
  await response(page, '/report-handoff', () => page.getByRole('dialog').getByRole('button', { name: '创建结果交接' }).click())
  await expect(page.locator('article').filter({ hasText: title })).toContainText('READY')
  const handoffs = await page.request.get('/api/data-copilot/report-handoffs')
  const handoff = (await handoffs.json()).data.find((item: any) => item.title === title)
  expect(handoff.metricSnapshot).toMatchObject({ metricKey, periodStart: '2026-09-01', periodEnd: '2026-09-07', confirmedBy: 'operator', unit: '笔' })
  const generated = await post(page, '/api/report-copilot/enterprise/reports/generate', {
    reportType: 'BUSINESS_WEEKLY', period: { periodStart: '2026-09-01', periodEnd: '2026-09-07', timezone: 'Asia/Shanghai' },
    title: `指标交接报告-${info.project.name}`, templateId: 'business-weekly', templateVersion: 'v1', selection: { connectionIds: [], dataHandoffReferences: [handoff.sourceReference], includeSupportMetrics: false },
  })
  expect(generated.ok(), await generated.text()).toBe(true)
  const handedDraft = (await generated.json()).data
  expect(handedDraft.status).toBe('DRAFTED')
  const consumed = await page.request.get('/api/data-copilot/report-handoffs')
  expect((await consumed.json()).data.find((item: any) => item.id === handoff.id).status).toBe('CONSUMED')
  const trace = await page.request.get(`/api/report-copilot/enterprise/drafts/${handedDraft.draftId}/data-trace`)
  expect(trace.ok(), await trace.text()).toBe(true)
  expect(JSON.stringify((await trace.json()).data)).toContain(handoff.sourceReference)
})

test('Knowledge: upload, asynchronous vector index, grounded answer and feedback', async ({ page }, info) => {
  await login(page, 'admin')
  await page.goto('/admin?tab=documents')
  const name = `服务流程-${info.project.name}-${Date.now()}.txt`
  await page.getByLabel('知识文档文件').setInputFiles({ name, mimeType: 'text/plain', buffer: Buffer.from(`${policy}\n测试来源：${name}`) })
  const document = await response(page, '/documents/file', () => page.getByRole('button', { name: '上传知识文档' }).click())
  expect(document.indexJobId).toBeTruthy()
  await expect.poll(async () => {
    const result = await page.request.get('/api/knowledge-copilot/documents')
    return (await result.json()).data.find((d: any) => d.id === document.documentId)?.indexStatus
  }, { timeout: 30000 }).toBe('INDEXED')
  await page.reload()
  await expect(page.getByRole('row').filter({ hasText: name })).toContainText('INDEXED')
  await page.goto('/knowledge')
  await page.getByLabel('知识问题').fill('虚构服务流程如何提供订单编号和核验处理进度？')
  const answer = await response(page, '/questions', () => page.getByRole('button', { name: '检索并回答' }).click())
  expect(answer.status).toBe('ANSWERED')
  expect(answer.citations.length).toBeGreaterThan(0)
  await expect(page.getByText(policy, { exact: true }).first()).toBeVisible()
  await page.getByRole('button', { name: '有帮助', exact: true }).click()
  await response(page, '/feedback', () => page.getByRole('button', { name: '提交反馈' }).click())
  await expect(page.locator('.feedback-complete')).toBeVisible()
})

test('Support: evidence-backed draft, separate reviewer and internal confirmation', async ({ page, browser }, info) => {
  await login(page, 'operator')
  await seedPolicy(page, `客服流程-${info.project.name}-${Date.now()}`)
  await page.goto('/support')
  const question = `虚构服务流程如何提供订单编号和核验处理进度？${info.project.name}`
  await page.getByLabel('客户消息').fill(question)
  const ticket = await response(page, '/tickets/analyze', () => page.getByRole('button', { name: '分析工单并生成草稿' }).click())
  expect(ticket.draft?.draftId).toBeTruthy()
  const context = await newRoleContext(browser)
  try {
    const reviewer = await context.newPage()
    await login(reviewer, 'reviewer')
    await reviewer.goto('/support?tab=review')
    const card = reviewer.locator('.review-queue-card').filter({ has: reviewer.getByText(`#${ticket.ticketId}`, { exact: true }) })
    await expect(card, await reviewer.locator('body').innerText()).toBeVisible()
    await card.getByRole('button', { name: '进入复核' }).click()
    await expect(reviewer.locator('.queue-review-editor textarea').first()).toHaveValue(policy)
    await reviewer.getByRole('button', { name: '确认采用复核结果', exact: true }).click()
    const confirmed = await response(reviewer, '/confirm', () => reviewer.getByRole('dialog').getByRole('button', { name: '确认采用复核结果', exact: true }).click())
    expect(confirmed.status).toBe('CONFIRMED')
  } finally { await context.close() }
  const capability = await page.request.get(`/api/support-copilot/enterprise/drafts/${ticket.draft.draftId}/writeback-capability`)
  expect((await capability.json()).data.eligible).toBe(false)
})

test('Report: real source, independent approval, confirmation and export after reload', async ({ page, browser }, info) => {
  await login(page, 'operator')
  await page.goto('/report')
  const title = `真实报告-${info.project.name}-${Date.now()}`
  await page.getByLabel('报告标题').fill(title)
  await page.getByLabel('来源数据').fill('本周已完成虚构服务流程说明。')
  const draft = await response(page, '/reports/generate', () => page.getByRole('button', { name: '生成报告草稿' }).click())
  expect(draft.status).toBe('DRAFTED')
  expect(draft.content.executiveSummarySourceIds.length).toBeGreaterThan(0)
  await expect(page.getByLabel('执行摘要')).toHaveValue(draft.content.executiveSummary)
  await approve(browser, '/report?tab=records', String(draft.draftId))
  await page.getByRole('button', { name: '刷新复核状态' }).click()
  await page.getByRole('button', { name: '确认报告', exact: true }).click()
  const confirmed = await response(page, `/reports/${draft.draftId}/confirm`, () => page.getByRole('dialog').getByRole('button', { name: '确认经营报告' }).click())
  expect(confirmed.status).toBe('CONFIRMED')
  await page.reload()
  await expect(page.locator('article').filter({ hasText: title })).toBeVisible()
  const exported = await page.request.get(`/api/report-copilot/reports/${draft.draftId}/markdown`)
  expect(exported.ok()).toBe(true)
  expect(await exported.text()).toContain(title)
})

test('HR: explicit consent, confirmed criteria, evidence assessment, reviewer and revocation', async ({ page, browser }, info) => {
  await login(page, 'operator')
  await page.goto('/hr')
  const name = `虚构 Java 岗位-${info.project.name}-${Date.now()}`
  await page.getByLabel('职位名称').fill(name)
  await page.getByLabel('岗位需求').fill('负责 Java 服务开发。')
  await response(page, '/jobs/draft', () => page.getByRole('button', { name: '生成岗位画像和 JD' }).click())
  const criteria = await response(page, '/jobs/criteria', () => page.getByRole('button', { name: '提取岗位标准' }).click())
  await page.getByRole('button', { name: '确认岗位标准', exact: true }).click()
  await response(page, '/criteria/confirm', () => page.getByRole('dialog').getByRole('button', { name: '确认岗位标准' }).click())
  await page.getByRole('tab', { name: '候选人数据授权' }).click()
  const consent = `consent-${info.project.name}-${Date.now()}`
  const candidate = `candidate-${Date.now()}`
  await page.getByLabel('授权凭据编号').fill(consent)
  await page.getByLabel('候选人业务编号').fill(candidate)
  await response(page, '/consents', () => page.getByRole('button', { name: '记录候选人授权' }).click())
  await page.getByRole('tab', { name: '候选人评估' }).click()
  await page.getByLabel('已确认岗位标准').selectOption(String(criteria.jobId))
  await page.getByLabel('候选人业务编号').fill(candidate)
  await page.getByLabel('授权凭据编号').fill(consent)
  await page.getByLabel('候选人简历文本').fill('项目经历：负责 Java 服务开发。')
  const assessment = await response(page, '/assessments', () => page.getByRole('button', { name: '开始证据评估' }).click())
  expect(assessment.status).toBe('DRAFTED')
  expect(assessment.evidence.length).toBeGreaterThan(0)
  const context = await newRoleContext(browser)
  try {
    const reviewer = await context.newPage()
    await login(reviewer, 'reviewer')
    await reviewer.goto('/hr')
    await reviewer.locator('article').filter({ hasText: candidate }).getByRole('button', { name: '领取并打开复核' }).click()
    await reviewer.getByLabel('复核意见（可选）').fill('逐项核验虚构测试证据。')
    const reviewed = await response(reviewer, `/${assessment.assessmentId}/review`, () => reviewer.getByRole('button', { name: '确认人工复核完成' }).click())
    expect(reviewed.status).toBe('REVIEWED')
    await page.getByRole('tab', { name: '候选人数据授权' }).click()
    await page.getByLabel('授权凭据编号').fill(consent)
    await response(page, '/revoke', () => page.getByRole('button', { name: '撤回当前授权' }).click())
    const staleLink = await reviewer.request.get(`/api/resume-copilot/assessments/${assessment.assessmentId}`)
    expect(staleLink.status()).toBe(404)
  } finally { await context.close() }
})
