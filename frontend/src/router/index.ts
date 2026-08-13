import { createRouter, createWebHistory } from 'vue-router'
import { useSession } from '@/composables/useSession'
import LoginView from '@/views/LoginView.vue'
import AppShell from '@/layouts/AppShell.vue'
import HomeView from '@/views/HomeView.vue'
import BusinessModuleView from '@/views/BusinessModuleView.vue'
import AdminView from '@/views/AdminView.vue'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: LoginView, meta: { public: true } },
    {
      path: '/',
      component: AppShell,
      children: [
        { path: '', component: HomeView },
        { path: 'data', component: BusinessModuleView, props: { module: 'data' } },
        { path: 'knowledge', component: BusinessModuleView, props: { module: 'knowledge' } },
        { path: 'support', component: BusinessModuleView, props: { module: 'support' } },
        { path: 'report', component: BusinessModuleView, props: { module: 'report' } },
        { path: 'hr', component: BusinessModuleView, props: { module: 'hr' } },
        { path: 'admin', component: AdminView, meta: { roles: ['ADMIN'] } },
      ],
    },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
  scrollBehavior: () => ({ top: 0 }),
})

router.beforeEach(async (to) => {
  const { session, load } = useSession()
  if (to.meta.public) {
    try {
      const publicSession = session.value ?? await load()
      return publicSession?.authenticated ? '/' : true
    } catch {
      // The product and login page remains available even if the session endpoint is temporarily unavailable.
      return true
    }
  }
  const current = session.value ?? await load()
  if (!current?.authenticated && !to.meta.public) return { path: '/login', query: { expired: '1' } }
  if (current?.publicDemo && ['/data', '/knowledge', '/support', '/report', '/hr'].includes(to.path)) return '/'
  if (current?.roles.includes('REVIEWER') && to.path === '/report') return '/'
  if (current?.roles.includes('REVIEWER') && to.path === '/hr' && to.query.section === 'employee') {
    return { path: '/hr', query: { section: 'recruiting' } }
  }
  const roles = to.meta.roles as string[] | undefined
  if (roles && !roles.some((role) => current?.roles.includes(role))) return '/'
  return true
})
