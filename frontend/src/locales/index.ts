import { createI18n } from 'vue-i18n'
import { messages, type SupportedLocale } from './messages'

export const LOCALE_STORAGE_KEY = 'businessCopilot.locale'
export const DEFAULT_LOCALE: SupportedLocale = 'zh-CN'
export const SUPPORTED_LOCALES: readonly SupportedLocale[] = ['zh-CN', 'en-US']

export function isSupportedLocale(value: string | null): value is SupportedLocale {
  return value === 'zh-CN' || value === 'en-US'
}

export function resolveLocale(): SupportedLocale {
  const stored = localStorage.getItem(LOCALE_STORAGE_KEY)
  if (isSupportedLocale(stored)) return stored
  if (stored !== null) localStorage.removeItem(LOCALE_STORAGE_KEY)
  return DEFAULT_LOCALE
}

export function applyDocumentLocale(locale: SupportedLocale): void {
  document.documentElement.lang = locale
}

const initialLocale = resolveLocale()
applyDocumentLocale(initialLocale)

export const i18n = createI18n({
  legacy: false,
  locale: initialLocale,
  fallbackLocale: DEFAULT_LOCALE,
  messages,
  missingWarn: true,
  fallbackWarn: true,
})

export function setLocale(locale: SupportedLocale): void {
  i18n.global.locale.value = locale
  localStorage.setItem(LOCALE_STORAGE_KEY, locale)
  applyDocumentLocale(locale)
}

export function currentLocale(): SupportedLocale {
  const locale = i18n.global.locale.value
  return isSupportedLocale(locale) ? locale : DEFAULT_LOCALE
}
