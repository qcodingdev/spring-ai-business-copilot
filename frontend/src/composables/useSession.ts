import { computed, readonly, ref } from 'vue'
import { fetchSession, type Session } from '@/api/session'

const session = ref<Session | null>(null)
const loading = ref(false)

export function useSession() {
  async function load(): Promise<Session | null> {
    loading.value = true
    try {
      session.value = await fetchSession()
      return session.value
    } finally {
      loading.value = false
    }
  }

  const isAdmin = computed(() => session.value?.roles.includes('ADMIN') ?? false)
  const isReviewer = computed(() => session.value?.roles.includes('REVIEWER') ?? false)
  const canOperate = computed(() => session.value?.roles.some((role) => role === 'ADMIN' || role === 'OPERATOR') ?? false)
  const canReview = computed(() => session.value?.roles.some((role) => role === 'ADMIN' || role === 'REVIEWER') ?? false)
  const authenticated = computed(() => session.value?.authenticated ?? false)

  return {
    session: readonly(session),
    loading: readonly(loading),
    isAdmin,
    isReviewer,
    canOperate,
    canReview,
    authenticated,
    load,
  }
}
