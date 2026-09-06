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

const inFlightMutations = new Map<string, Promise<ApiEnvelope<unknown>>>()

function mutationBodyKey(body: BodyInit | null | undefined): string {
  if (typeof body === 'string') return body
  if (!(body instanceof FormData)) return ''
  return [...body.entries()].map(([key, value]) => {
    if (value instanceof File) return `${key}:file:${value.name}:${value.size}:${value.lastModified}`
    return `${key}:value:${String(value)}`
  }).join('|')
}

/**
 * Coalesce identical in-flight mutations. Disabled buttons remain the visual
 * affordance, while this is the final protection against double-clicks,
 * keyboard repeats, and two components dispatching the same action together.
 */
export function api<T>(path: string, init: ApiRequestInit = {}): Promise<ApiEnvelope<T>> {
  const method = (init.method ?? 'GET').toUpperCase()
  if (['GET', 'HEAD', 'OPTIONS'].includes(method)) return performApi<T>(path, init)
  const bodyKey = mutationBodyKey(init.body)
  const key = `${method}:${path}:${bodyKey}`
  const existing = inFlightMutations.get(key)
  if (existing) return existing as Promise<ApiEnvelope<T>>
  const request = performApi<T>(path, init)
  inFlightMutations.set(key, request as Promise<ApiEnvelope<unknown>>)
  void request.finally(() => {
    if (inFlightMutations.get(key) === request) inFlightMutations.delete(key)
  }).catch(() => { /* the caller observes the original rejection */ })
  return request
}

async function performApi<T>(path: string, init: ApiRequestInit = {}): Promise<ApiEnvelope<T>> {
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
