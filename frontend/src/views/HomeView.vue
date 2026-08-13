<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'
import ModuleIcon from '@/components/ModuleIcon.vue'
import PublicDemoPanel from '@/components/PublicDemoPanel.vue'
import { useSession } from '@/composables/useSession'

const { t } = useI18n()
const { session, isReviewer } = useSession()
const modules = computed(() => [
  { route: '/data', key: 'data' as const, title: t('navigation.data'), description: t('data.cardDescription'), tag: t('data.cardTag'), number: '01' },
  { route: '/knowledge', key: 'knowledge' as const, title: t('navigation.knowledge'), description: t('knowledge.cardDescription'), tag: t('knowledge.cardTag'), number: '02' },
  { route: '/support', key: 'support' as const, title: t('navigation.support'), description: t('support.cardDescription'), tag: t('support.cardTag'), number: '03' },
  { route: '/report', key: 'report' as const, title: t('navigation.report'), description: t('report.cardDescription'), tag: t('report.cardTag'), number: '04' },
  { route: '/hr', key: 'hr' as const, title: t('navigation.hr'), description: t('hr.cardDescription'), tag: t('hr.cardTag'), number: '05' },
].filter((item) => !isReviewer.value || item.key !== 'report'))
</script>

<template>
  <template v-if="session?.publicDemo">
    <section class="workspace-hero workspace-hero--compact">
      <div>
        <p class="eyebrow">{{ t('common.workspaceEyebrow') }}</p>
        <h1>{{ t('common.publicScenarios') }}</h1>
        <p>{{ t('common.publicScenarioNotice') }}</p>
      </div>
    </section>
    <PublicDemoPanel />
  </template>

  <template v-else>
    <section class="workspace-hero">
      <div class="workspace-hero__copy">
        <p class="eyebrow">{{ t('common.workspaceEyebrow') }}</p>
        <h1>{{ t('common.welcome') }}，{{ session?.username }}</h1>
        <p>{{ t('common.welcomeDescription') }}</p>
        <div class="hero-assurance">
          <span><i></i>{{ t('common.operatingNormally') }}</span>
          <span><i></i>{{ t('common.secureByDesign') }}</span>
        </div>
      </div>
      <div class="workspace-hero__visual" aria-hidden="true">
        <div class="signal-card signal-card--one"><ModuleIcon name="data" /><span>Data</span></div>
        <div class="signal-card signal-card--two"><ModuleIcon name="knowledge" /><span>Knowledge</span></div>
        <div class="signal-card signal-card--three"><ModuleIcon name="support" /><span>Support</span></div>
        <div class="signal-core"><img :src="'/images/qcoding-logo.png'" alt="" /></div>
        <span class="signal-ring signal-ring--one"></span><span class="signal-ring signal-ring--two"></span>
      </div>
    </section>

    <section class="home-section" aria-labelledby="module-heading">
      <div class="section-heading section-heading--home">
        <div>
          <p class="eyebrow">{{ t('common.businessModules') }}</p>
          <h2 id="module-heading">{{ t('common.businessModulesDescription') }}</h2>
        </div>
        <span class="module-count">{{ modules.length }} Copilots</span>
      </div>
      <div class="module-grid" :aria-label="t('common.copilotNavigation')">
        <RouterLink v-for="item in modules" :key="item.route" class="module-card" :class="`module-card--${item.key}`" :to="item.route">
          <div class="module-card__top">
            <span class="module-card__icon"><ModuleIcon :name="item.key" /></span>
            <span class="module-card__number">{{ item.number }}</span>
          </div>
          <div class="module-card__content">
            <h3>{{ item.title }}</h3>
            <p>{{ item.description }}</p>
          </div>
          <div class="module-card__footer">
            <span>{{ item.tag }}</span>
            <strong>{{ t('common.enterModule') }} <b aria-hidden="true">→</b></strong>
          </div>
        </RouterLink>
      </div>
    </section>
  </template>
</template>
