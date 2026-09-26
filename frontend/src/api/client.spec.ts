import { afterEach, describe, expect, it, vi } from 'vitest'
import { AI_GENERATION_REQUEST_TIMEOUT_MS, api, DEFAULT_REQUEST_TIMEOUT_MS } from './client'

describe('API request budgets', () => {
  afterEach(() => vi.restoreAllMocks())

  it('keeps ordinary calls on the short request budget', async () => {
    const timeout = vi.spyOn(AbortSignal, 'timeout')
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({
      data: {}, success: true, errorCode: null, message: null, requestId: null, timestamp: '',
    }), { status: 200, headers: { 'Content-Type': 'application/json' } })))

    await api('/api/session')

    expect(timeout).toHaveBeenCalledWith(DEFAULT_REQUEST_TIMEOUT_MS)
  })

  it('allows an explicit model-generation budget without forwarding it to fetch', async () => {
    const timeout = vi.spyOn(AbortSignal, 'timeout')
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({
      data: {}, success: true, errorCode: null, message: null, requestId: null, timestamp: '',
    }), { status: 200, headers: { 'Content-Type': 'application/json' } }))
    vi.stubGlobal('fetch', fetchMock)

    await api('/api/data-copilot/sql-candidates', { method: 'POST', timeoutMs: AI_GENERATION_REQUEST_TIMEOUT_MS })

    expect(timeout).toHaveBeenCalledWith(AI_GENERATION_REQUEST_TIMEOUT_MS)
    expect(fetchMock.mock.calls[0]?.[1]).not.toHaveProperty('timeoutMs')
  })

  it('coalesces identical in-flight mutations to prevent duplicate actions', async () => {
    let resolveFetch: ((response: Response) => void) | undefined
    const fetchMock = vi.fn().mockImplementation(() => new Promise<Response>((resolve) => { resolveFetch = resolve }))
    vi.stubGlobal('fetch', fetchMock)
    const init = { method: 'POST', body: JSON.stringify({ confirmationToken: 'token' }) }

    const first = api('/api/data-copilot/sql-candidates/one/execute', init)
    const second = api('/api/data-copilot/sql-candidates/one/execute', init)

    expect(fetchMock).toHaveBeenCalledTimes(1)
    resolveFetch?.(new Response(JSON.stringify({
      data: {}, success: true, errorCode: null, message: null, requestId: null, timestamp: '',
    }), { status: 200, headers: { 'Content-Type': 'application/json' } }))
    await expect(Promise.all([first, second])).resolves.toHaveLength(2)
  })
})
