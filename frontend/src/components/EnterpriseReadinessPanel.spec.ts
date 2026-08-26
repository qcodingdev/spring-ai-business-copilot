import { flushPromises, mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { messages } from '@/locales/messages'

vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return { ...actual, api: vi.fn() }
})

import { api, ApiError } from '@/api/client'
import EnterpriseReadinessPanel from './EnterpriseReadinessPanel.vue'

const apiMock = vi.mocked(api)
const live = {
  schemaVersion: 2,
  applicationVersion: '2.4.0',
  runtimeMode: 'self-hosted',
  status: 'BLOCKED' as const,
  passedCount: 1,
  warningCount: 0,
  blockerCount: 1,
  checks: [
    { checkId: 'DATA_STALE_HANDOFF_CLAIMS', module: 'DATA' as const, status: 'BLOCKER' as const, affectedCount: 2, threshold: 'PT15M', actionPath: '/data?tab=handoff' },
    { checkId: 'DATA_EXPIRED_RESULTS', module: 'DATA' as const, status: 'PASS' as const, affectedCount: 0, threshold: 'PT1H', actionPath: '/data?tab=records' },
  ],
  contentHash: 'a'.repeat(64),
  generatedAt: '2026-08-20T01:00:00Z',
  validUntil: '2026-08-21T01:00:00Z',
}
const snapshot = {
  ...live,
  id: 7,
  snapshotReference: '85a35186-3c79-4cee-ad04-d95d88ae3fd7',
  purpose: '生产前复核',
  generatedBy: 'admin-1',
}

function envelope<T>(data: T) {
  return { data, success: true, errorCode: null, message: 'OK', requestId: 'request-24', timestamp: '2026-08-20T01:00:00Z' }
}

describe('EnterpriseReadinessPanel', () => {
  beforeEach(() => {
    apiMock.mockReset()
    apiMock.mockImplementation(async (path, init = {}) => {
      if (path === '/api/admin/readiness') return envelope(live) as never
      if (path === '/api/admin/readiness/snapshots?page=0&size=20') {
        return envelope({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 }) as never
      }
      if (path === '/api/admin/readiness/snapshots' && init.method === 'POST') {
        return envelope(snapshot) as never
      }
      throw new Error(`Unexpected API call: ${path}`)
    })
  })

  it('shows blockers, links to remediation, and creates evidence with a purpose', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }],
    })
    await router.push('/admin?tab=readiness')
    await router.isReady()
    const i18n = createI18n({ legacy: false, locale: 'zh-CN', messages })
    const wrapper = mount(EnterpriseReadinessPanel, { global: { plugins: [i18n, router] } })

    await flushPromises()

    expect(wrapper.text()).toContain('已阻断')
    expect(wrapper.text()).toContain('结果交接领取超时')
    expect(wrapper.get('a').attributes('href')).toBe('/data?tab=handoff')
    await wrapper.get('input').setValue('  生产前复核  ')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(apiMock).toHaveBeenCalledWith('/api/admin/readiness/snapshots', expect.objectContaining({
      method: 'POST',
      body: JSON.stringify({ purpose: '生产前复核' }),
    }))
    expect(wrapper.text()).toContain('企业就绪快照已保存')
    expect(wrapper.text()).toContain('生产前复核')
    expect(wrapper.text()).toContain(snapshot.snapshotReference)
  })

  it('keeps append-only history visible when the live probe fails closed', async () => {
    apiMock.mockImplementation(async (path) => {
      if (path === '/api/admin/readiness') {
        throw new ApiError(500, 'generic', 'live-failure-request')
      }
      if (path === '/api/admin/readiness/snapshots?page=0&size=20') {
        return envelope({ content: [snapshot], page: 0, size: 20, totalElements: 1, totalPages: 1 }) as never
      }
      throw new Error(`Unexpected API call: ${path}`)
    })
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }],
    })
    await router.push('/admin?tab=readiness')
    await router.isReady()
    const i18n = createI18n({ legacy: false, locale: 'zh-CN', messages })
    const wrapper = mount(EnterpriseReadinessPanel, { global: { plugins: [i18n, router] } })

    await flushPromises()

    expect(wrapper.text()).toContain('生产前复核')
    expect(wrapper.text()).toContain(snapshot.snapshotReference)
    expect(wrapper.find('.readiness-metrics').exists()).toBe(false)
    expect(wrapper.get('[role="alert"]').text()).not.toBe('')
  })

  it('shows not configured instead of ready when a prerequisite is missing', async () => {
    apiMock.mockImplementation(async (path) => {
      if (path === '/api/admin/readiness') {
        return envelope({
          ...live,
          status: 'NOT_CONFIGURED',
          checks: [{ checkId: 'CHAT_MODEL_NOT_CONFIGURED', module: 'PLATFORM', status: 'BLOCKER', affectedCount: 1, threshold: null, actionPath: '/admin?tab=overview' }],
        }) as never
      }
      if (path === '/api/admin/readiness/snapshots?page=0&size=20') {
        return envelope({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 }) as never
      }
      throw new Error(`Unexpected API call: ${path}`)
    })
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }],
    })
    await router.push('/admin?tab=readiness')
    await router.isReady()
    const i18n = createI18n({ legacy: false, locale: 'zh-CN', messages })
    const wrapper = mount(EnterpriseReadinessPanel, { global: { plugins: [i18n, router] } })

    await flushPromises()

    expect(wrapper.text()).toContain('未配置')
    expect(wrapper.text()).toContain('对话模型未配置')
  })

  it('keeps the first history page bounded to twenty snapshots after creation', async () => {
    const existing = Array.from({ length: 20 }, (_, index) => ({
      ...snapshot,
      id: index + 10,
      snapshotReference: `00000000-0000-0000-0000-${String(index).padStart(12, '0')}`,
      purpose: `历史快照 ${index}`,
    }))
    apiMock.mockImplementation(async (path, init = {}) => {
      if (path === '/api/admin/readiness') return envelope(live) as never
      if (path === '/api/admin/readiness/snapshots?page=0&size=20') {
        return envelope({ content: existing, page: 0, size: 20, totalElements: 20, totalPages: 1 }) as never
      }
      if (path === '/api/admin/readiness/snapshots' && init.method === 'POST') {
        return envelope(snapshot) as never
      }
      throw new Error(`Unexpected API call: ${path}`)
    })
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }],
    })
    await router.push('/admin?tab=readiness')
    await router.isReady()
    const i18n = createI18n({ legacy: false, locale: 'zh-CN', messages })
    const wrapper = mount(EnterpriseReadinessPanel, { global: { plugins: [i18n, router] } })
    await flushPromises()
    await wrapper.get('input').setValue('生产前复核')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(wrapper.findAll('.readiness-snapshot')).toHaveLength(20)
    expect(wrapper.text()).toContain('生产前复核')
    expect(wrapper.text()).not.toContain('历史快照 19')
  })
})
