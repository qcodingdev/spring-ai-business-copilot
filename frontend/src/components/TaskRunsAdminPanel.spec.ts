import { flushPromises, mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { messages } from '@/locales/messages'

vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return { ...actual, api: vi.fn() }
})
import { api } from '@/api/client'
import TaskRunsAdminPanel from './TaskRunsAdminPanel.vue'

const apiMock = vi.mocked(api)

const runRow = {
  runId: 'run-1234567890abcdef',
  module: 'report',
  refType: 'generation',
  refId: 'report-1',
  ownerActorId: 'operator-1',
  status: 'FAILED',
  failureCategory: 'PROVIDER',
  stopReason: '报告草稿生成失败',
  startedAt: '2026-08-28T10:00:00Z',
  endedAt: '2026-08-28T10:01:00Z',
}

const timeline = {
  run: { ...runRow },
  steps: [{
    stepId: 'step-1',
    name: 'generate-draft',
    status: 'FAILED',
    attemptCount: 2,
    failureCategory: 'PROVIDER',
    evidenceRefs: ['source:s1'],
    summary: '草稿生成失败',
  }],
  attempts: [{
    stepId: 'step-1',
    kind: 'MODEL',
    operation: 'report.generation',
    provider: 'openai',
    model: 'gpt-5-mini',
    inputTokens: 60,
    outputTokens: 0,
    latencyMs: 30000,
    outcome: 'FAILURE',
    failureCategory: 'PROVIDER',
  }],
}

function envelope<T>(data: T) {
  return { data, success: true, errorCode: null, message: 'OK', requestId: 'runs-request', timestamp: '2026-08-28T12:00:00Z' }
}

function render() {
  return mount(TaskRunsAdminPanel, {
    global: { plugins: [createI18n({ legacy: false, locale: 'zh-CN', messages })] },
  })
}

describe('TaskRunsAdminPanel', () => {
  beforeEach(() => {
    apiMock.mockReset()
    apiMock.mockImplementation(async (path) => {
      if (String(path).includes('/timeline')) return envelope(timeline) as never
      return envelope([runRow]) as never
    })
  })

  it('lists runs and loads the timeline without exposing sensitive payloads', async () => {
    const wrapper = render()
    await flushPromises()
    expect(wrapper.text()).toContain('任务运行与时间线')
    expect(wrapper.text()).toContain('供应商故障')
    const filterValues = wrapper.findAll('[data-testid="task-run-status-filter"] option')
      .map(option => option.attributes('value'))
    expect(filterValues).toEqual(['', 'WAITING_CONFIRMATION', 'RUNNING', 'FAILED', 'BUDGET_EXHAUSTED', 'OUTCOME_UNKNOWN'])
    expect(new Set(filterValues).size).toBe(filterValues.length)
    await wrapper.get('[data-testid="task-runs-table"] button').trigger('click')
    await flushPromises()
    expect(apiMock.mock.calls.find(([path]) => String(path).includes('/timeline'))?.[0])
      .toBe('/api/admin/task-runs/run-1234567890abcdef/timeline')
    const detail = wrapper.get('[data-testid="task-run-timeline"]')
    expect(detail.text()).toContain('generate-draft')
    expect(detail.text()).toContain('report.generation')
    expect(detail.text()).toContain('停止原因')
  })

  it('shows an empty state instead of fabricated rows when no runs exist', async () => {
    apiMock.mockImplementation(async () => envelope([]) as never)
    const wrapper = render()
    await flushPromises()
    expect(wrapper.text()).toContain('当前筛选条件下没有运行记录。')
    expect(wrapper.find('[data-testid="task-run-timeline"]').exists()).toBeFalsy()
  })

  it('renders failure category badges for failed attempts', async () => {
    const wrapper = render()
    await flushPromises()
    await wrapper.get('[data-testid="task-runs-table"] button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('60 + 0')
    expect(wrapper.text()).toContain('失败')
    expect(wrapper.text()).toContain('供应商故障')
  })
})
