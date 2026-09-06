import { flushPromises, mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { messages } from '@/locales/messages'
import EvaluationManagementPanel from './EvaluationManagementPanel.vue'

vi.mock('@/api/client', async () => ({
  ...await vi.importActual<typeof import('@/api/client')>('@/api/client'), api: vi.fn(),
}))
import { api } from '@/api/client'
const apiMock = vi.mocked(api)
const envelope = (data: unknown) => ({ data, success: true, errorCode: null, message: null, requestId: 'test', timestamp: '' })
let wrapper: ReturnType<typeof mount>

beforeEach(() => {
  vi.useFakeTimers()
  apiMock.mockReset()
  apiMock.mockImplementation(async (path) => envelope(
    path.endsWith('/datasets') || path.endsWith('/gate-policies') ? []
      : path.endsWith('/runs') ? [{ id: 'queued-run', status: 'QUEUED', gateDecision: 'NOT_VERIFIED', results: [] }]
        : { id: 'queued-run', status: 'PASSED', gateDecision: 'ALLOW_RELEASE', results: [] },
  ) as never)
})
afterEach(() => { wrapper?.unmount(); vi.useRealTimers() })

it('resumes polling a queued run after page refresh and stops when it finishes', async () => {
  wrapper = mount(EvaluationManagementPanel, { global: { plugins: [createI18n({ legacy: false, locale: 'zh-CN', messages })] } })
  await flushPromises()
  expect(wrapper.text()).toContain('排队中')
  expect(wrapper.text()).toContain(messages['zh-CN'].admin.cancelEvaluation)
  await vi.advanceTimersByTimeAsync(1200)
  await flushPromises()
  expect(apiMock).toHaveBeenCalledWith('/api/governance/evaluations/runs/queued-run')
  expect(wrapper.text()).toContain('允许发布')
  const count = apiMock.mock.calls.length
  await vi.advanceTimersByTimeAsync(2400)
  expect(apiMock).toHaveBeenCalledTimes(count)
})
