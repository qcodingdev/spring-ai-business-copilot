import type { SupportedLocale } from './messages'

export function formatDate(value: string | Date, locale: SupportedLocale): string {
  const date = value instanceof Date ? value : new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  return new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeStyle: 'short' }).format(date)
}

export function formatNumber(value: number, locale: SupportedLocale): string {
  return new Intl.NumberFormat(locale, { maximumFractionDigits: 2 }).format(value)
}
