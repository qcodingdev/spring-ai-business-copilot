import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import LanguageSwitcher from './LanguageSwitcher.vue'
import { i18n, LOCALE_STORAGE_KEY } from '@/locales'

describe('LanguageSwitcher', () => {
  it('shows only the opposite language and switches immediately', async () => {
    const wrapper = mount(LanguageSwitcher, { global: { plugins: [i18n] } })
    expect(wrapper.get('button').text()).toBe('English')
    await wrapper.get('button').trigger('click')
    expect(document.documentElement.lang).toBe('en-US')
    expect(localStorage.getItem(LOCALE_STORAGE_KEY)).toBe('en-US')
    expect(wrapper.get('button').text()).toBe('简体中文')
    expect(wrapper.get('button').attributes('aria-label')).toBe('Switch to 简体中文')
  })
})
