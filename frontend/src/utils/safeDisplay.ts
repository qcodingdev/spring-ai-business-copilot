const SENSITIVE_KEY = /(authorization|cookie|credential|password|secret|token)/i

export function safeJson(value: unknown): string {
  return JSON.stringify(value, (key, item) => {
    if (SENSITIVE_KEY.test(key)) return '••••'
    return item
  }, 2)
}
