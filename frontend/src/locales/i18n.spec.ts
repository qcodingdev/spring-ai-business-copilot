import { describe, expect, it } from 'vitest'
import { DEFAULT_LOCALE, LOCALE_STORAGE_KEY, resolveLocale, setLocale } from './index'
import { messages } from './messages'
import { formatDate, formatNumber } from './format'

function keys(value: unknown, prefix = ''): string[] {
  if (typeof value !== 'object' || value === null) return [prefix]
  return Object.entries(value).flatMap(([key, child]) => keys(child, prefix ? `${prefix}.${key}` : key))
}

describe('frontend locale contract', () => {
  it('keeps Chinese and English keys exactly aligned', () => {
    expect(keys(messages['en-US']).sort()).toEqual(keys(messages['zh-CN']).sort())
  })

  it('defaults invalid and missing values to Simplified Chinese', () => {
    expect(resolveLocale()).toBe(DEFAULT_LOCALE)
    localStorage.setItem(LOCALE_STORAGE_KEY, 'fr-FR')
    expect(resolveLocale()).toBe('zh-CN')
    expect(localStorage.getItem(LOCALE_STORAGE_KEY)).toBeNull()
  })

  it('persists an explicit locale and synchronizes document.lang', () => {
    setLocale('en-US')
    expect(localStorage.getItem(LOCALE_STORAGE_KEY)).toBe('en-US')
    expect(document.documentElement.lang).toBe('en-US')
  })

  it('formats date and number with the selected locale', () => {
    expect(formatNumber(12345.5, 'en-US')).toContain('12,345')
    expect(formatNumber(12345.5, 'zh-CN')).toContain('12,345')
    expect(formatDate('2026-07-29T08:00:00Z', 'en-US')).not.toBe('')
  })
})
