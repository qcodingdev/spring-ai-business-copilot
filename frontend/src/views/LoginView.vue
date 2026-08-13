<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import LanguageSwitcher from '@/components/LanguageSwitcher.vue'
import ModuleIcon from '@/components/ModuleIcon.vue'
import { fetchSession } from '@/api/session'

const { t } = useI18n()
const username = ref('')
const password = ref('')
const csrf = ref('')
const query = new URLSearchParams(location.search)
const message = computed(() => query.has('error') ? t('auth.invalid') : query.has('expired') ? t('auth.sessionExpired') : '')
const capabilities = computed(() => [
  { key: 'data' as const, title: t('navigation.data'), description: t('data.cardDescription'), tag: t('data.cardTag') },
  { key: 'knowledge' as const, title: t('navigation.knowledge'), description: t('knowledge.cardDescription'), tag: t('knowledge.cardTag') },
  { key: 'support' as const, title: t('navigation.support'), description: t('support.cardDescription'), tag: t('support.cardTag') },
  { key: 'report' as const, title: t('navigation.report'), description: t('report.cardDescription'), tag: t('report.cardTag') },
  { key: 'hr' as const, title: t('navigation.hr'), description: t('hr.cardDescription'), tag: t('hr.cardTag') },
])

onMounted(async () => {
  try {
    await fetchSession()
  } catch {
    // Anonymous access initializes the CSRF cookie for the login form.
  }
  const match = document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]+)/)
  csrf.value = match ? decodeURIComponent(match[1] ?? '') : ''
})
</script>

<template>
  <div class="public-page">
    <header class="public-header">
      <a class="public-brand" href="#top" aria-label="QCoding AI Copilot">
        <img :src="'/images/qcoding-logo.png'" alt="QCoding Logo" />
        <span><strong>{{ t('common.appName') }}</strong><small>{{ t('common.brandTagline') }}</small></span>
      </a>
      <nav class="public-nav" :aria-label="t('common.primaryNavigation')">
        <a href="#capabilities">{{ t('common.publicNavCapabilities') }}</a>
        <a href="#trust">{{ t('common.publicNavTrust') }}</a>
        <a href="#technology">{{ t('common.publicNavTechnology') }}</a>
      </nav>
      <div class="public-header__actions">
        <LanguageSwitcher />
        <a class="button button--primary" href="#login-form">{{ t('common.loginExperience') }}</a>
      </div>
    </header>

    <main id="main-content" class="public-main">
      <section id="top" class="public-hero" aria-labelledby="public-title">
        <div class="public-hero__copy">
          <p class="eyebrow">{{ t('common.publicHeroEyebrow') }}</p>
          <h1 id="public-title">{{ t('common.publicHeroTitle') }}</h1>
          <p class="public-hero__lead">{{ t('common.publicHeroDescription') }}</p>
          <div class="public-hero__actions">
            <a class="button button--primary button--large" href="#capabilities">{{ t('common.exploreCapabilities') }}</a>
            <a class="button button--glass button--large" href="#login-form">{{ t('common.loginExperience') }}</a>
          </div>
          <div class="public-metrics">
            <div><strong>5</strong><span>{{ t('common.fiveApplications') }}</span></div>
            <div><strong>100%</strong><span>{{ t('common.auditableActions') }}</span></div>
            <div><strong>Human</strong><span>{{ t('common.humanDecision') }}</span></div>
          </div>
        </div>

        <aside id="login-form" class="public-login-card" aria-labelledby="login-title">
          <div class="login-card__accent"></div>
          <div class="login-card__heading">
            <span class="login-card__mark"><img :src="'/images/qcoding-logo.png'" alt="" /></span>
            <div><p>{{ t('common.roleWorkspace') }}</p><h2 id="login-title">{{ t('auth.title') }}</h2></div>
          </div>
          <p class="login-subtitle">{{ t('auth.subtitle') }}</p>
          <div v-if="message" class="alert alert--warning" role="status">{{ message }}</div>
          <form action="/login" method="post">
            <input type="hidden" name="_csrf" :value="csrf" />
            <label>{{ t('auth.username') }}<input v-model="username" name="username" autocomplete="username" required /></label>
            <label>{{ t('auth.password') }}<input v-model="password" name="password" type="password" autocomplete="current-password" required /></label>
            <button class="button button--primary button--full button--large" type="submit">{{ t('auth.signIn') }} <span aria-hidden="true">→</span></button>
          </form>
          <div class="login-assurance">
            <span>✓ {{ t('common.trustControl') }}</span>
            <span>✓ {{ t('common.trustAudit') }}</span>
          </div>
        </aside>
        <span class="public-orb public-orb--one" aria-hidden="true"></span>
        <span class="public-orb public-orb--two" aria-hidden="true"></span>
      </section>

      <section id="capabilities" class="public-section" aria-labelledby="capabilities-title">
        <div class="public-section__heading">
          <p class="eyebrow">{{ t('common.publicSectionEyebrow') }}</p>
          <h2 id="capabilities-title">{{ t('common.publicSectionTitle') }}</h2>
          <p>{{ t('common.publicSectionDescription') }}</p>
        </div>
        <div class="public-capability-grid">
          <article v-for="(item, index) in capabilities" :key="item.key" :class="`public-capability public-capability--${item.key}`">
            <div class="public-capability__top"><span><ModuleIcon :name="item.key" /></span><b>0{{ index + 1 }}</b></div>
            <h3>{{ item.title }}</h3>
            <p>{{ item.description }}</p>
            <small>{{ item.tag }}</small>
          </article>
        </div>
      </section>

      <section id="trust" class="public-trust" aria-labelledby="trust-title">
        <div class="public-trust__copy">
          <p class="eyebrow">TRUST BY DESIGN</p>
          <h2 id="trust-title">{{ t('common.trustTitle') }}</h2>
          <p>{{ t('common.trustDescription') }}</p>
        </div>
        <div class="trust-grid">
          <article><span>01</span><h3>{{ t('common.trustEvidence') }}</h3><p>{{ t('common.trustEvidenceDescription') }}</p></article>
          <article><span>02</span><h3>{{ t('common.trustControl') }}</h3><p>{{ t('common.trustControlDescription') }}</p></article>
          <article><span>03</span><h3>{{ t('common.trustAudit') }}</h3><p>{{ t('common.trustAuditDescription') }}</p></article>
        </div>
      </section>

      <section id="technology" class="technology-section">
        <div><p class="eyebrow">ENTERPRISE FOUNDATION</p><h2>{{ t('common.technologyTitle') }}</h2><p>{{ t('common.technologyDescription') }}</p></div>
        <div class="technology-list"><span>Java 21</span><span>Spring Boot</span><span>Spring AI</span><span>PostgreSQL</span><span>pgvector</span><span>Guardrails</span><span>Observability</span></div>
      </section>
    </main>

    <footer class="public-footer">
      <div class="public-brand"><img :src="'/images/qcoding-logo.png'" alt="" /><span><strong>{{ t('common.productBy') }}</strong><small>{{ t('common.productFocus') }}</small></span></div>
      <span>© 2026 QCoding</span>
    </footer>
  </div>
</template>
