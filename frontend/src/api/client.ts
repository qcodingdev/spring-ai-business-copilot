import { currentLocale } from '@/locales'

export interface ApiEnvelope<T> {
  data: T
  success: boolean
  errorCode: string | null
  message: string | null
  requestId: string | null
  timestamp: string
}

/**
 * Ordinary same-origin API calls should fail fast. Model-backed generation has a
 * separate, explicit budget so that it can wait for the server-side provider
 * timeout without making every management request wait that long.
 */
export const DEFAULT_REQUEST_TIMEOUT_MS = 30_000
export const AI_GENERATION_REQUEST_TIMEOUT_MS = 130_000

export interface ApiRequestInit extends RequestInit {
  /** Local browser-side request budget; this is intentionally not sent as an HTTP header. */
  timeoutMs?: number
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

export async function api<T>(path: string, init: ApiRequestInit = {}): Promise<ApiEnvelope<T>> {
  if (!path.startsWith('/api/')) throw new Error('API path must remain same-origin under /api/')
  const { timeoutMs = DEFAULT_REQUEST_TIMEOUT_MS, signal: callerSignal, ...requestInit } = init
  const method = (requestInit.method ?? 'GET').toUpperCase()
  const headers = new Headers(requestInit.headers)
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

  const timeout = AbortSignal.timeout(timeoutMs)
  let response: Response
  try {
    response = await fetch(path, {
      ...requestInit,
      headers,
      signal: callerSignal ? AbortSignal.any([callerSignal, timeout]) : timeout,
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
