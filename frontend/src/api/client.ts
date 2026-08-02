import { currentLocale } from '@/locales'

export interface ApiEnvelope<T> {
  data: T
  success: boolean
  errorCode: string | null
  message: string | null
  requestId: string | null
  timestamp: string
}

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly errorCode: string,
    readonly requestId: string | null,
  ) {
    super(errorCode)
    this.name = 'ApiError'
  }
}

function cookie(name: string): string | null {
  const prefix = `${encodeURIComponent(name)}=`
  const part = document.cookie.split('; ').find((item) => item.startsWith(prefix))
  return part ? decodeURIComponent(part.slice(prefix.length)) : null
}

function requestId(): string {
  return globalThis.crypto?.randomUUID?.().replaceAll('-', '') ?? `${Date.now()}frontend`
}

export async function api<T>(path: string, init: RequestInit = {}): Promise<ApiEnvelope<T>> {
  if (!path.startsWith('/api/')) throw new Error('API path must remain same-origin under /api/')
  const method = (init.method ?? 'GET').toUpperCase()
  const headers = new Headers(init.headers)
  headers.set('Accept', 'application/json')
  headers.set('Accept-Language', currentLocale())
  headers.set('X-Request-Id', requestId())
  if (!(init.body instanceof FormData) && init.body !== undefined) {
    headers.set('Content-Type', 'application/json')
  }
  if (!['GET', 'HEAD', 'OPTIONS'].includes(method)) {
    const token = cookie('XSRF-TOKEN')
    if (token) headers.set('X-XSRF-TOKEN', token)
  }

  const timeout = AbortSignal.timeout(30_000)
  let response: Response
  try {
    response = await fetch(path, {
      ...init,
      headers,
      signal: init.signal ? AbortSignal.any([init.signal, timeout]) : timeout,
      credentials: 'same-origin',
      redirect: 'error',
    })
  } catch {
    throw new ApiError(0, 'networkTimeout', null)
  }

  let envelope: ApiEnvelope<T>
  try {
    envelope = (await response.json()) as ApiEnvelope<T>
  } catch {
    throw new ApiError(response.status, 'generic', response.headers.get('X-Request-Id'))
  }

  if (!response.ok || !envelope.success) {
    if (response.status === 401 && location.pathname !== '/login') {
      history.replaceState(null, '', `/login?expired=1`)
      location.reload()
    }
    throw new ApiError(
      response.status,
      envelope.errorCode ?? (response.status === 403 ? 'SEC_0403' : 'generic'),
      envelope.requestId ?? response.headers.get('X-Request-Id'),
    )
  }
  return envelope
}

export function jsonBody(value: unknown): Pick<RequestInit, 'body'> {
  return { body: JSON.stringify(value) }
}
