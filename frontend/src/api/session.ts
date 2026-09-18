import { api } from './client'

export interface Session {
  authenticated: boolean
  username: string | null
  roles: string[]
  runtimeMode: string
  publicDemo: boolean
  aiEnabled: boolean
  localLoginEnabled?: boolean
  enterpriseLoginUrl?: string | null
}

export async function fetchSession(): Promise<Session> {
  return (await api<Session>('/api/session')).data
}
