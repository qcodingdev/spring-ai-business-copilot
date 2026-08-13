import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import ConfirmDialog from './ConfirmDialog.vue'
import { i18n } from '@/locales'

describe('ConfirmDialog', () => {
  it('shows the target, state transition, impact, expiry and concrete action', async () => {
    const wrapper = mount(ConfirmDialog, {
      props: {
        open: true,
        operation: '确认执行查询',
        target: 'candidate-42',
        currentState: 'DRAFTED',
        targetState: 'COMPLETED',
        impact: 'Runs one read-only query',
        recoverable: false,
        expiresAt: '2026-07-29T10:00:00Z',
        risk: 'Result may contain masked fields',
      },
      global: { plugins: [i18n] },
      attachTo: document.body,
    })
    expect(wrapper.text()).toContain('candidate-42')
    expect(wrapper.text()).toContain('DRAFTED')
    expect(wrapper.text()).toContain('COMPLETED')
    expect(wrapper.text()).toContain('确认执行查询')
  })
})
