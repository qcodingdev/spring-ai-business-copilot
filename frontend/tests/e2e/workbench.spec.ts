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
  await page.route('**/api/data-copilot/report-handoffs', async (route) => {
    await route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify({
        data: [],
        success: true,
        errorCode: null,
        message: null,
        requestId: 'e2e-empty-data-handoffs',
        timestamp: new Date().toISOString(),
      }),
    })
  })
}

async function mockReviewerSession(page: Page): Promise<void> {
  await page.route('**/api/session', async (route) => route.fulfill({
    contentType: 'application/json',
    body: JSON.stringify({
      data: { authenticated: true, username: 'fictional.reviewer', roles: ['REVIEWER'], runtimeMode: 'self-hosted', publicDemo: false, aiEnabled: true },
      success: true, requestId: 'e2e-reviewer-session', timestamp: new Date().toISOString(),
    }),
  }))
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

test('reviewer navigation exposes only callable review workflows', async ({ page }) => {
  await mockReviewerSession(page)
  await page.route('**/api/data-copilot/query-results', async (route) => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ data: [], success: true, requestId: 'reviewer-results', timestamp: new Date().toISOString() }) }))
  await page.route('**/api/data-copilot/audit-logs', async (route) => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ data: [], success: true, requestId: 'reviewer-audits', timestamp: new Date().toISOString() }) }))
  await page.goto('/')
  await expect(page.getByRole('link', { name: '经营报告', exact: true })).toHaveCount(0)
  await expect(page.getByRole('link', { name: '企业知识', exact: true })).toBeVisible()
  await page.goto('/data')
  await expect(page.getByRole('tab', { name: '执行记录' })).toBeVisible()
  await expect(page.getByRole('tab', { name: '查询' })).toHaveCount(0)
  await expect(page.getByText('reviewer-audits')).toBeVisible()
})

test('reviewer claims and completes an HR assessment review', async ({ page }) => {
  await mockReviewerSession(page)
  await page.route('**/api/resume-copilot/assessments/review-queue?limit=50', async (route) => route.fulfill({ contentType: 'application/json', body: JSON.stringify({
    data: [{ assessmentId: 81, jobId: 7, submissionId: 9, jobTitle: '虚构 Java 工程师', candidateReference: 'candidate-81', criteriaVersion: 2, status: 'NEEDS_REVIEW', reviewReasons: ['证据需要人工核验'], reviewerActorId: null, updatedAt: new Date().toISOString() }],
    success: true, requestId: 'assessment-review-queue', timestamp: new Date().toISOString(),
  }) }))
  await page.route('**/api/resume-copilot/assessments/81/review-session', async (route) => route.fulfill({ contentType: 'application/json', body: JSON.stringify({
    data: { assessment: { assessmentId: 81, status: 'NEEDS_REVIEW', content: { anonymousSummary: '具备可核验的 Java 项目经验', criterionAssessments: [{ criterionId: 'java', status: 'SUPPORTED', explanation: '存在项目证据' }] }, evidence: [{ evidenceId: 'E81', section: '项目', sanitizedText: '负责 Java 服务开发' }] }, criteria: [], reviewToken: 'review-token-81', expiresAt: new Date(Date.now() + 60_000).toISOString() },
    success: true, requestId: 'assessment-review-session', timestamp: new Date().toISOString(),
  }) }))
  let reviewed = false
  await page.route('**/api/resume-copilot/assessments/81/review', async (route) => {
    expect(route.request().postDataJSON().token).toBe('review-token-81')
    reviewed = true
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ data: { assessmentId: 81, status: 'REVIEWED' }, success: true, requestId: 'assessment-review-complete', timestamp: new Date().toISOString() }) })
  })
  await page.goto('/hr')
  await expect(page.getByRole('tab', { name: '候选人评估' })).toBeVisible()
  await expect(page.getByRole('tab', { name: '岗位标准' })).toHaveCount(0)
  await page.getByRole('button', { name: '领取并打开复核' }).click()
  await expect(page.getByText('具备可核验的 Java 项目经验', { exact: true })).toBeVisible()
  await page.getByLabel('复核意见（可选）').fill('证据已逐项核验。')
  await page.getByRole('button', { name: '确认人工复核完成' }).click()
  expect(reviewed).toBe(true)
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

test('keeps the SQL candidate and confirmed query result in the main data workflow', async ({ page }) => {
  await mockSession(page)
  await page.route('**/api/data-copilot/sql-candidates', async (route) => {
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify({
      data: { candidateId: 'candidate-42', confirmationToken: 'confirm-42', executable: true, sql: 'SELECT public.products.name FROM public.products LIMIT 1', summary: '查询一个商品名称。', validation: { passed: true }, assumptions: [], warnings: [] },
      success: true, errorCode: null, message: null, requestId: 'e2e-data-candidate', timestamp: new Date().toISOString(),
    }) })
  })
  await page.route('**/api/data-copilot/sql-candidates/candidate-42/execute', async (route) => {
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify({
      data: { table: { columns: [{ name: 'name' }], rows: [{ values: { name: '虚构商品' } }], rowCount: 1, truncated: false }, explanation: { explanation: '已返回一条脱敏结果。' } },
      success: true, errorCode: null, message: null, requestId: 'e2e-data-execution', timestamp: new Date().toISOString(),
    }) })
  })

  await page.goto('/data')
  await page.getByLabel('业务问题').fill('查询一个商品名称。')
  await page.getByRole('button', { name: '生成 SQL 候选' }).click()
  await expect(page.locator('.task-panel').getByRole('heading', { name: 'SQL 预览' })).toBeVisible()
  await expect(page.getByRole('heading', { name: '本次处理结果' })).toHaveCount(0)
  await page.getByRole('button', { name: '确认查询' }).click()
  await page.getByRole('dialog').getByRole('button', { name: '确认执行只读查询' }).click()
  await expect(page.getByRole('heading', { name: '本次处理结果' })).toBeVisible()
  await expect(page.getByText('虚构商品', { exact: true })).toBeVisible()
  await expect(page.getByText('e2e-data-execution')).toBeVisible()
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
  await page.route('**/api/admin/demo-data/jobs/fictional-initialize-job', async (route) => {
    await route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify({
        data: { id: 'fictional-initialize-job', jobType: 'INITIALIZE', status: 'COMPLETED', summaryJson: '{"documents":3}' },
        success: true,
        requestId: 'e2e-admin-initialize-completed',
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
  await expect(page.getByRole('heading', { name: 'System administration' })).toBeVisible()
  await expect(page.locator('html')).toHaveAttribute('lang', 'en-US')
  await page.getByRole('button', { name: 'Experience data' }).click()
  await page.getByRole('button', { name: 'Prepare experience data' }).click()
  await page.getByRole('dialog')
    .getByRole('button', { name: 'Prepare experience data' })
    .click()
  await expect(page.getByText('fictional-initialize-job')).toBeVisible()
  await expect(page.getByText('Experience data preparation completed.', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Preview restore impact' })).toBeDisabled()
  await expect(page.getByText(/available only in public-demo mode/)).toBeVisible()
})

test('keeps processing results scoped to the tab that produced them', async ({ page }) => {
  await mockSession(page)
  await page.route('**/api/knowledge-copilot/questions', async (route) => {
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify({
      data: { status: 'ANSWERED', answer: '试用期为三个月。', citations: [{ sourceTitle: '员工手册', snippet: '试用期三个月' }] },
      success: true, requestId: 'knowledge-scoped-result', timestamp: new Date().toISOString(),
    }) })
  })
  await page.route('**/api/knowledge-copilot/sources', async (route) => {
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ data: [], success: true, requestId: 'sources-tab', timestamp: new Date().toISOString() }) })
  })
  await page.goto('/knowledge')
  await page.getByLabel('知识问题').fill('试用期多久？')
  await page.getByRole('button', { name: '检索并回答' }).click()
  await expect(page.getByText('试用期为三个月。', { exact: true })).toBeVisible()
  await page.getByRole('tab', { name: '外部来源' }).click()
  await expect(page.getByText('试用期为三个月。', { exact: true })).toHaveCount(0)
  await expect(page.getByText('本次处理结果')).toHaveCount(0)
})

test('shows and completes the support draft review flow', async ({ page }) => {
  await mockSession(page)
  await page.route('**/api/support-copilot/tickets/analyze', async (route) => {
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify({
      data: { status: 'DRAFTED', summary: '退款咨询', needsHuman: true, draft: { draftId: 7, replyText: '您好，我们会核实退款条件。', confirmationToken: 'support-token', expiresAt: new Date(Date.now() + 60_000).toISOString(), citations: [] } },
      success: true, requestId: 'support-draft-result', timestamp: new Date().toISOString(),
    }) })
  })
  await page.route('**/api/support-copilot/reply-drafts/7/confirm', async (route) => {
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ data: { draftId: 7, status: 'CONFIRMED' }, success: true, requestId: 'support-confirmed', timestamp: new Date().toISOString() }) })
  })
  await page.route('**/api/support-copilot/enterprise/drafts/7/writeback-capability', async (route) => {
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ data: { eligible: false }, success: true, requestId: 'support-writeback-unavailable', timestamp: new Date().toISOString() }) })
  })
  await page.goto('/support')
  await page.getByLabel('客户消息').fill('请协助处理退款。')
  await page.getByRole('button', { name: '分析工单并生成草稿' }).click()
  await expect(page.locator('.review-editor textarea').first()).toHaveValue('您好，我们会核实退款条件。')
  await page.getByRole('button', { name: '确认采用草稿' }).click()
  await page.getByRole('dialog').getByRole('button', { name: '确认客服回复草稿' }).click()
  await expect(page.getByText('回复草稿已确认；系统不会自动向客户发送。')).toBeVisible()
  await expect(page.getByText('当前工单由工作台直接创建，没有外部工单目标；流程已在内部确认处安全结束。')).toBeVisible()
})

test('operates the support human review queue instead of exposing raw JSON', async ({ page }) => {
  await mockSession(page)
  let confirmed = false
  await page.route('**/api/support-copilot/tickets?limit=100', async (route) => route.fulfill({ contentType: 'application/json', body: JSON.stringify({
    data: [{ ticketId: 7, externalReference: 'ticket-review-7', customerQuestion: '退款何时到账？', category: 'REFUND', sentiment: 'FRUSTRATED', urgency: 'HIGH', status: 'NEEDS_HUMAN', draftId: 7, draftStatus: 'NEEDS_REVIEW', riskLevel: 'HIGH', riskReasons: ['退款金额需要人工核对'], suggestedReply: '您好，我们正在核对退款。', editReason: null, decisionOutcome: null, knowledgeVersions: ['policy-v2'], createdAt: new Date().toISOString() }],
    success: true, requestId: 'support-review-queue', timestamp: new Date().toISOString(),
  }) }))
  await page.route('**/api/support-copilot/reply-drafts/7/review-session', async (route) => route.fulfill({ contentType: 'application/json', body: JSON.stringify({
    data: { draftId: 7, suggestedReply: '您好，我们正在核对退款。', confirmationToken: 'review-token-7', status: 'NEEDS_REVIEW', expiresAt: new Date(Date.now() + 60_000).toISOString() },
    success: true, requestId: 'support-review-session', timestamp: new Date().toISOString(),
  }) }))
  await page.route('**/api/support-copilot/reply-drafts/7/edit', async (route) => {
    expect(route.request().postDataJSON().editedText).toContain('两个工作日')
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ data: { draftId: 7, status: 'NEEDS_REVIEW' }, success: true, requestId: 'support-review-edited', timestamp: new Date().toISOString() }) })
  })
  await page.route('**/api/support-copilot/reply-drafts/7/confirm', async (route) => {
    expect(route.request().postDataJSON().confirmationToken).toBe('review-token-7')
    confirmed = true
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ data: { draftId: 7, status: 'CONFIRMED' }, success: true, requestId: 'support-review-confirmed', timestamp: new Date().toISOString() }) })
  })

  await page.goto('/support?tab=review')
  await expect(page.getByText('退款何时到账？', { exact: true })).toBeVisible()
  await expect(page.locator('.result-preview')).toHaveCount(0)
  await page.getByRole('button', { name: '进入复核' }).click()
  await page.getByLabel('待人工复核的回复草稿').fill('您好，退款预计两个工作日内到账。')
  await page.getByRole('button', { name: '保存人工修订' }).click()
  await expect(page.getByText('人工修订已保存，仍需确认后才完成草稿复核。')).toBeVisible()
  await page.getByRole('button', { name: '确认采用复核结果' }).click()
  await page.getByRole('dialog').getByRole('button', { name: '确认采用复核结果' }).click()
  expect(confirmed).toBe(true)
  await expect(page.getByText('人工复核队列已更新。')).toBeVisible()
})

test('persists localized knowledge quality review dimensions', async ({ page }) => {
  await mockSession(page)
  let reviewed = false
  await page.route('**/api/knowledge-copilot/quality-queue?size=50', async (route) => route.fulfill({ contentType: 'application/json', body: JSON.stringify({
    data: { content: reviewed ? [] : [{ answerId: 17, requestId: 'knowledge-17', question: '报销上限是多少？', answerPreview: '旧制度中的上限为 2000 元。', retrievedChunkIds: '11,12', citedChunkIds: '11', answerStatus: 'ANSWERED', refusalReason: null, rating: 'NOT_HELPFUL', feedbackReason: 'OUTDATED', comment: '制度已经更新', answerCreatedAt: new Date().toISOString(), feedbackUpdatedAt: new Date().toISOString(), issueVersion: 2, issueUpdatedAt: '2026-08-09T10:00:00Z' }], page: 0, size: 50, totalElements: reviewed ? 0 : 1, totalPages: reviewed ? 0 : 1 },
    success: true, requestId: 'knowledge-quality-queue', timestamp: new Date().toISOString(),
  }) }))
  await page.route('**/api/knowledge-copilot/quality-queue/17/review', async (route) => {
    const body = route.request().postDataJSON()
    expect(body).toMatchObject({ decision: 'KNOWLEDGE_UPDATE_REQUIRED', evidenceAssessment: 'OUTDATED', answerAssessment: 'PARTIALLY_ACCURATE', remediationAction: 'UPDATE_KNOWLEDGE' })
    reviewed = true
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ data: { id: 5, answerId: 17 }, success: true, requestId: 'knowledge-quality-reviewed', timestamp: new Date().toISOString() }) })
  })

  await page.goto('/knowledge?tab=quality')
  await page.getByRole('button', { name: '开始人工复核' }).click()
  await expect(page.getByText('旧制度中的上限为 2000 元。', { exact: true })).toBeVisible()
  await page.getByLabel('证据评估').selectOption('OUTDATED')
  await page.getByLabel('答案评估').selectOption('PARTIALLY_ACCURATE')
  await page.getByLabel('后续动作').selectOption('UPDATE_KNOWLEDGE')
  await page.getByLabel('处置结论').selectOption('KNOWLEDGE_UPDATE_REQUIRED')
  await page.getByLabel('复核说明').fill('已确认引用的是旧制度，需要更新知识内容并重新索引。')
  await page.getByRole('button', { name: '完成并保存复核' }).click()
  await expect(page.getByText('质量问题已完成处置。')).toBeVisible()
  await expect(page.getByText('暂无数据')).toBeVisible()
})

test('shows, edits, and confirms a report draft', async ({ page }) => {
  await mockSession(page)
  const content = {
    executiveSummary: '原始执行摘要',
    executiveSummarySourceIds: ['meeting-1'],
    metricHighlights: [{ metricName: '交付数', metricValue: '3', unit: '项', summary: '完成三项交付', sourceIds: ['meeting-1'] }],
    completedItems: [{ text: '完成版本发布', sourceIds: ['meeting-1'] }],
    risks: [],
    actionItems: [],
    suggestions: [],
    citations: [{ sourceId: 'meeting-1', title: '周会纪要', excerpt: '本周完成三项交付' }],
  }
  await page.route('**/api/report-copilot/reports/generate', async (route) => route.fulfill({ contentType: 'application/json', body: JSON.stringify({
    data: { draftId: 21, status: 'DRAFTED', content, confirmationToken: 'report-token', expiresAt: new Date(Date.now() + 60_000).toISOString() },
    success: true, requestId: 'report-generated', timestamp: new Date().toISOString(),
  }) }))
  await page.route('**/api/report-copilot/reports/21/edit', async (route) => {
    const body = route.request().postDataJSON()
    expect(body.content.executiveSummary).toBe('人工修改后的摘要')
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ data: { draftId: 21, status: 'DRAFTED', content: body.content }, success: true, requestId: 'report-edited', timestamp: new Date().toISOString() }) })
  })
  await page.route('**/api/report-copilot/reports/21/confirm', async (route) => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ data: { draftId: 21, status: 'CONFIRMED' }, success: true, requestId: 'report-confirmed', timestamp: new Date().toISOString() }) }))

  await page.goto('/report')
  await page.getByLabel('报告标题').fill('研发周报')
  await page.getByLabel('来源数据').fill('本周完成三项交付。')
  await page.getByRole('button', { name: '生成报告草稿' }).click()
  await expect(page.getByLabel('执行摘要')).toHaveValue('原始执行摘要')
  await expect(page.locator('.report-edit-section textarea').first()).toHaveValue('完成三项交付')
  await page.getByLabel('执行摘要').fill('人工修改后的摘要')
  await page.getByRole('button', { name: '保存人工修改' }).click()
  await expect(page.getByText('报告修改已保存，仍需人工确认。')).toBeVisible()
  await page.getByRole('button', { name: '确认报告' }).click()
  await page.getByRole('dialog').getByRole('button', { name: '确认经营报告' }).click()
  await expect(page.getByText('报告已确认，现在可以导出；系统不会自动发布。')).toBeVisible()
  await expect(page.getByLabel('执行摘要')).toBeDisabled()
})

test('starts reports from Data handoffs, quick examples, or an uploaded source', async ({ page }) => {
  await mockSession(page)
  await page.unroute('**/api/data-copilot/report-handoffs')
  await page.route('**/api/data-copilot/report-handoffs', async (route) => route.fulfill({ contentType: 'application/json', body: JSON.stringify({
    data: [{ id: 9, resultId: 91, title: '销售经营分析', status: 'READY', sourceReference: 'data-result:91', rowCount: 24, resultExpiresAt: new Date(Date.now() + 60_000).toISOString(), createdAt: new Date().toISOString() }],
    success: true, requestId: 'report-data-handoffs', timestamp: new Date().toISOString(),
  }) }))
  let uploadCalled = false
  await page.route('**/api/report-copilot/reports/generate-from-file?*', async (route) => {
    expect(route.request().url()).toContain('title=CSV+%E7%BB%8F%E8%90%A5%E5%91%A8%E6%8A%A5')
    expect(route.request().postDataBuffer()?.length ?? 0).toBeGreaterThan(0)
    uploadCalled = true
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ data: { draftId: 31, status: 'DRAFTED', content: { executiveSummary: '上传文件生成的摘要', executiveSummarySourceIds: ['upload-1'], metricHighlights: [], completedItems: [], risks: [], actionItems: [], suggestions: [] }, confirmationToken: 'report-upload-token' }, success: true, requestId: 'report-upload-generated', timestamp: new Date().toISOString() }) })
  })

  await page.goto('/report')
  const picker = page.getByRole('button', { name: /销售经营分析/ })
  await picker.click()
  await expect(page.getByLabel('报告标题')).toHaveValue('销售经营分析')
  await expect(page.getByLabel('来源数据')).toHaveValue(/data-result:91/)
  await page.getByRole('button', { name: '生成本周研发团队周报，突出交付、风险和下周行动。' }).click()
  await expect(page.getByLabel('报告标题')).toHaveValue('研发团队本周经营周报')
  await expect(page.getByLabel('来源数据')).toHaveValue(/版本发布/)
  await page.getByLabel('报告标题').fill('CSV 经营周报')
  await page.getByLabel('上传来源文件（可选）').setInputFiles({ name: 'metrics.csv', mimeType: 'text/csv', buffer: Buffer.from('metric,value\norders,24') })
  await page.getByLabel('来源数据').fill('')
  await page.getByRole('button', { name: '生成报告草稿' }).click()
  expect(uploadCalled).toBe(true)
  await expect(page.getByLabel('执行摘要')).toHaveValue('上传文件生成的摘要')
})

test('completes job criteria confirmation and candidate review', async ({ page }) => {
  await mockSession(page)
  const criteria = [{ criterionId: 'java', requirementType: 'MUST_HAVE', category: '技术', description: '熟悉 Java', weight: 1 }]
  await page.route('**/api/resume-copilot/jobs/draft', async (route) => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ data: { jdDraft: 'Java 工程师岗位说明' }, success: true, requestId: 'job-drafted', timestamp: new Date().toISOString() }) }))
  await page.route('**/api/resume-copilot/jobs/criteria', async (route) => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ data: { jobId: 31, status: 'DRAFTED', criteria, confirmationToken: 'criteria-token', expiresAt: new Date(Date.now() + 60_000).toISOString() }, success: true, requestId: 'criteria-generated', timestamp: new Date().toISOString() }) }))
  await page.route('**/api/resume-copilot/jobs/31/criteria', async (route) => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ data: { jobId: 31, status: 'DRAFTED', criteria: route.request().postDataJSON().criteria, confirmationToken: 'criteria-token' }, success: true, requestId: 'criteria-edited', timestamp: new Date().toISOString() }) }))
  await page.route('**/api/resume-copilot/jobs/31/criteria/confirm', async (route) => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ data: { jobId: 31, status: 'CONFIRMED' }, success: true, requestId: 'criteria-confirmed', timestamp: new Date().toISOString() }) }))
  await page.route('**/api/resume-copilot/jobs/confirmed', async (route) => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ data: [{ jobId: 31, title: 'Java 工程师', criteriaVersion: 1 }], success: true, requestId: 'confirmed-jobs', timestamp: new Date().toISOString() }) }))
  await page.route('**/api/resume-copilot/assessments', async (route) => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ data: { assessmentId: 41, status: 'DRAFTED', reviewToken: 'review-token', expiresAt: new Date(Date.now() + 60_000).toISOString(), content: { anonymousSummary: '候选人具备 Java 项目经验', criterionAssessments: [{ criterionId: 'java', status: 'SUPPORTED', explanation: '简历中有明确项目证据' }] }, evidence: [{ evidenceId: 'E1', section: '项目经验', sanitizedText: '负责 Java 服务开发' }], reviewReasons: [] }, success: true, requestId: 'assessment-generated', timestamp: new Date().toISOString() }) }))
  await page.route('**/api/resume-copilot/assessments/41/review', async (route) => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ data: { assessmentId: 41, status: 'REVIEWED' }, success: true, requestId: 'assessment-reviewed', timestamp: new Date().toISOString() }) }))

  await page.goto('/hr')
  await page.getByLabel('职位名称').fill('Java 工程师')
  await page.getByLabel('岗位需求').fill('负责 Java 服务开发。')
  await page.getByRole('button', { name: '生成岗位画像和 JD' }).click()
  await expect(page.locator('.review-editor textarea')).toHaveValue('Java 工程师岗位说明')
  await page.getByRole('button', { name: '提取岗位标准' }).click()
  await page.getByLabel(/MUST_HAVE/).fill('熟悉 Java 与 Spring')
  await page.getByRole('button', { name: '保存岗位标准修改' }).click()
  await page.getByRole('button', { name: '确认岗位标准' }).click()
  await page.getByRole('dialog').getByRole('button', { name: '确认岗位标准' }).click()
  await expect(page.getByText('岗位标准已确认，可进入候选人评估选择使用。')).toBeVisible()
  await page.getByRole('tab', { name: '候选人评估' }).click()
  await page.getByLabel('已确认岗位标准').selectOption('31')
  await page.getByLabel('候选人业务编号').fill('candidate-41')
  await page.getByLabel('授权凭据编号').fill('consent-41')
  await page.getByLabel('候选人简历文本').fill('候选人负责过 Java 服务开发。')
  await page.getByRole('button', { name: '开始证据评估' }).click()
  await expect(page.getByText('候选人具备 Java 项目经验', { exact: true })).toBeVisible()
  await expect(page.getByText('负责 Java 服务开发', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '确认人工复核完成' }).click()
  await page.getByRole('dialog').getByRole('button', { name: '完成人工复核' }).click()
  await expect(page.getByText('候选人评估已完成人工复核。')).toBeVisible()
})

test('separates recruiting and employee service in the left navigation', async ({ page }) => {
  await mockSession(page)
  await page.goto('/hr')
  if ((page.viewportSize()?.width ?? 1280) < 900) {
    await page.getByRole('button', { name: '打开导航' }).click()
  }
  const subnav = page.locator('.sidebar-subnav')
  const recruiting = subnav.getByRole('link', { name: '招聘协同' })
  const employee = subnav.getByRole('link', { name: '员工服务' })
  await expect(recruiting).toBeVisible()
  await expect(recruiting).toHaveAttribute('aria-current', 'page')
  await expect(recruiting).toHaveClass(/active/)
  await employee.click()
  await expect(employee).toHaveAttribute('aria-current', 'page')
  await expect(employee).toHaveClass(/active/)
  await expect(recruiting).not.toHaveAttribute('aria-current', 'page')
  await expect(page.getByRole('tab', { name: '员工问答' })).toBeVisible()
  await expect(page.getByLabel('员工服务问题')).toBeVisible()
  await expect(page.getByRole('tab', { name: '岗位标准' })).toHaveCount(0)
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
    if (scenario[0] !== '/hr') {
      await expect(page.locator('.evidence-list').getByText('Fictional evidence.', { exact: true })).toBeVisible()
    }
  })
}
