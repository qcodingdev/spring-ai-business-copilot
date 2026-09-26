import { flushPromises, mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { expect, it, vi } from 'vitest'
import { messages } from '@/locales/messages'
import MyTaskRunsPanel from './MyTaskRunsPanel.vue'

vi.mock('@/api/client', async () => ({
  ...await vi.importActual<typeof import('@/api/client')>('@/api/client'), api: vi.fn(),
}))
import { api, ApiError } from '@/api/client'

it('distinguishes a load failure from an empty ledger and recovers on refresh', async () => {
  vi.mocked(api).mockRejectedValueOnce(new ApiError(503, 'generic', 'failed-load'))
    .mockResolvedValueOnce({ data: [], success: true, errorCode: null, message: null, requestId: 'ok', timestamp: '' })
  const wrapper = mount(MyTaskRunsPanel, { global: { plugins: [createI18n({ legacy: false, locale: 'zh-CN', messages })] } })
  await flushPromises()
  expect(wrapper.text()).toContain('暂时无法加载任务运行记录')
  expect(wrapper.text()).toContain('failed-load')
  expect(wrapper.text()).not.toContain('还没有运行记录')
  await wrapper.get('button').trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('还没有运行记录')
  expect(wrapper.text()).not.toContain('暂时无法加载任务运行记录')
  wrapper.unmount()
})
