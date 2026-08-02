<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink, RouterView, useRoute } from 'vue-router'
import LanguageSwitcher from '@/components/LanguageSwitcher.vue'
import ModuleIcon from '@/components/ModuleIcon.vue'
import { useSession } from '@/composables/useSession'

const { t } = useI18n()
const route = useRoute()
const { session, isAdmin } = useSession()
const menuOpen = ref(false)
const csrfToken = computed(() => {
  const match = document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]+)/)
  return match ? decodeURIComponent(match[1] ?? '') : ''
})
const navigation = computed(() => [
  { to: '/', label: t('navigation.overview'), icon: 'overview' as const },
  ...(session.value?.publicDemo ? [] : [
    { to: '/data', label: t('navigation.data'), icon: 'data' as const },
    { to: '/knowledge', label: t('navigation.knowledge'), icon: 'knowledge' as const },
    { to: '/support', label: t('navigation.support'), icon: 'support' as const },
    { to: '/report', label: t('navigation.report'), icon: 'report' as const },
    { to: '/hr', label: t('navigation.hr'), icon: 'hr' as const },
  ]),
])
const pageTitle = computed(() => {
  const key = route.path === '/' ? 'overview' : route.path.slice(1)
  return t(`navigation.${key}`)
})
const primaryRole = computed(() => {
  const role = session.value?.roles[0]
  return role ? t(`statuses.${role}`) : t('common.roleWorkspace')
})
const userInitial = computed(() => (session.value?.username?.slice(0, 1) || 'U').toUpperCase())
</script>

<template>
  <div class="app-shell">
    <header class="mobile-topbar">
      <button class="icon-button" type="button" :aria-label="t('common.menu')" @click="menuOpen = !menuOpen">
        <span></span><span></span><span></span>
      </button>
      <strong>{{ t('common.appName') }}</strong>
      <LanguageSwitcher />
    </header>
    <aside class="sidebar" :class="{ 'sidebar--open': menuOpen }" :aria-label="t('common.primaryNavigation')">
      <RouterLink class="brand" to="/" @click="menuOpen = false">
        <img class="brand__logo" :src="'/images/qcoding-logo.png'" alt="QCoding Logo" />
        <span class="brand__copy"><strong>{{ t('common.appName') }}</strong><small>{{ t('common.brandTagline') }}</small></span>
      </RouterLink>
      <p class="sidebar__section-label">{{ t('common.businessModules') }}</p>
      <nav class="sidebar__nav">
        <RouterLink v-for="item in navigation" :key="item.to" :to="item.to" @click="menuOpen = false">
          <span class="nav-icon"><ModuleIcon :name="item.icon" /></span>
          <span>{{ item.label }}</span>
          <span class="nav-arrow" aria-hidden="true">›</span>
        </RouterLink>
        <RouterLink v-if="isAdmin" to="/admin" @click="menuOpen = false">
          <span class="nav-icon"><ModuleIcon name="admin" /></span>
          <span>{{ t('navigation.admin') }}</span>
          <span class="nav-arrow" aria-hidden="true">›</span>
        </RouterLink>
      </nav>
      <div class="sidebar__security">
        <span class="security-orbit" aria-hidden="true">✓</span>
        <div><strong>{{ t('common.secureByDesign') }}</strong><small>{{ t('common.productFocus') }}</small></div>
      </div>
      <div class="sidebar__profile">
        <span class="user-avatar">{{ userInitial }}</span>
        <div><strong>{{ session?.username }}</strong><small>{{ primaryRole }}</small></div>
      </div>
    </aside>
    <div v-if="menuOpen" class="sidebar-backdrop" aria-hidden="true" @click="menuOpen = false" />
    <div class="workspace">
      <header class="topbar">
        <div class="topbar__context"><span>{{ t('common.workbench') }}</span><b>/</b><strong>{{ pageTitle }}</strong></div>
        <div class="runtime-chip"><span></span>{{ session?.runtimeMode ?? t('common.unknown') }}</div>
        <LanguageSwitcher />
        <form action="/logout" method="post">
          <input v-if="csrfToken" type="hidden" name="_csrf" :value="csrfToken" />
          <button class="logout-button" type="submit" :aria-label="t('auth.signOut')">
            <svg aria-hidden="true" viewBox="0 0 24 24"><path d="M10 5H5v14h5m4-3 4-4-4-4m4 4H9" /></svg>
            <span>{{ t('auth.signOut') }}</span>
          </button>
        </form>
      </header>
      <main id="main-content" tabindex="-1">
        <RouterView />
      </main>
    </div>
  </div>
</template>
