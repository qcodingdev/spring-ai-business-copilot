import { flushPromises, mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { messages } from '@/locales/messages'

vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return { ...actual, api: vi.fn() }
})
import { api, ApiError } from '@/api/client'
import AcceptanceEvidencePanel from './AcceptanceEvidencePanel.vue'

const apiMock = vi.mocked(api)
const assessment = {
  applicableVersion: '2.4.1-SNAPSHOT',
  categories: ['RUNTIME_READINESS', 'MODEL_QUALITY', 'VENDOR_ACCEPTANCE', 'RELEASE_GATE']
    .map(category => ({ category, status: category === 'RUNTIME_READINESS' ? 'PASS' : 'NOT_VERIFIED', evidenceCount: category === 'RUNTIME_READINESS' ? 1 : 0 })),
  releaseReadiness: { releasable: false, overall: 'NOT_VERIFIED' },
}
function envelope<T>(data: T) {
  return { data, success: true, errorCode: null, message: 'OK', requestId: 'acceptance-request', timestamp: '2026-08-28T12:00:00Z' }
}
function render(locale = 'zh-CN') {
  return mount(AcceptanceEvidencePanel, { global: { plugins: [createI18n({ legacy: false, locale, messages })] } })
}

describe('AcceptanceEvidencePanel', () => {
  beforeEach(() => {
    apiMock.mockReset()
    apiMock.mockImplementation(async (path, init = {}) => {
      if (init.method === 'POST') return envelope({ id: 1 }) as never
      if (path === '/api/admin/acceptance-evidence') return envelope(assessment) as never
      return envelope([]) as never
    })
  })

  it('separates four categories and does not treat runtime PASS as release acceptance', async () => {
    const wrapper = render()
    await flushPromises()
    expect(wrapper.text()).toContain('验收证据尚未全部通过')
    expect(wrapper.text()).toContain('供应商验收')
    expect(wrapper.text()).toContain('2.4.1-SNAPSHOT')
    expect(wrapper.findAll('.status-badge')).toHaveLength(4)
    expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeDefined()
  })

  it('requires human acknowledgement and never sends a client-supplied actor', async () => {
    const wrapper = render()
    await flushPromises()
    const inputs = wrapper.findAll('input:not([type="checkbox"])')
    await inputs[0]!.setValue('fixed-quality')
    await inputs[1]!.setValue('artifact:fixture-123')
    await wrapper.get('form').trigger('submit')
    expect(apiMock.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(0)
    await wrapper.get('input[type="checkbox"]').setValue(true)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    const body = JSON.parse(String(apiMock.mock.calls.find(([, init]) => init?.method === 'POST')?.[1]?.body))
    expect(body).toEqual({ category: 'MODEL_QUALITY', name: 'fixed-quality', status: 'NOT_VERIFIED', source: 'artifact:fixture-123', applicableVersion: '2.4.1-SNAPSHOT', note: '' })
    expect(wrapper.text()).toContain('验收证据已登记')
  })

  it('only syncs runtime readiness from the server and hides its manual form', async () => {
    const wrapper = render()
    await flushPromises()
    await wrapper.findAll('select')[0]!.setValue('RUNTIME_READINESS')
    await flushPromises()
    expect(wrapper.find('form').exists()).toBe(false)
    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(apiMock).toHaveBeenCalledWith('/api/admin/acceptance-evidence/runtime-readiness/sync', { method: 'POST' })
  })

  it('clears stale PASS after refresh errors and shows safe English feedback', async () => {
    const wrapper = render('en-US')
    await flushPromises()
    apiMock.mockRejectedValue(new ApiError(500, 'internal-secret', 'failure-request'))
    await (wrapper.vm as unknown as { load(): Promise<void> }).load()
    await flushPromises()
    expect(wrapper.text()).toContain('Could not read acceptance evidence')
    expect(wrapper.text()).not.toContain('internal-secret')
    expect(wrapper.findAll('.status-badge')).toHaveLength(0)
  })
})
