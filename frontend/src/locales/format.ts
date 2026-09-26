import type { SupportedLocale } from './messages'

/**
 * Business timestamps are persisted as instants (normally ISO-8601/UTC), but
 * the workbench must show them in the user's product locale rather than the
 * browser machine timezone.  English uses Pacific time with DST; Chinese is
 * fixed to China Standard Time (UTC+08:00).
 */
export function displayTimeZone(locale: string): 'Asia/Shanghai' | 'America/Los_Angeles' {
  return locale === 'en-US' ? 'America/Los_Angeles' : 'Asia/Shanghai'
}

export function formatDate(value: string | Date, locale: SupportedLocale | string): string {
  const date = value instanceof Date ? value : new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  return new Intl.DateTimeFormat(locale, {
    dateStyle: 'medium',
    timeStyle: 'short',
    timeZone: displayTimeZone(locale),
  }).format(date)
}

export function formatNumber(value: number, locale: SupportedLocale): string {
  return new Intl.NumberFormat(locale, { maximumFractionDigits: 2 }).format(value)
}
