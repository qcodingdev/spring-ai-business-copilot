<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
const { t } = useI18n()
const expanded = ref(false)

const props = withDefaults(defineProps<{ items: unknown[]; embedded?: boolean }>(), {
  embedded: false,
})
const visibleItems = computed(() => expanded.value ? props.items : props.items.slice(0, 3))

watch(() => props.items, () => { expanded.value = false })

function revealLinkedEvidence(): void {
  const match = location.hash.match(/^#evidence-source-(\d+)$/)
  const position = Number(match?.[1] ?? 0)
  if (!Number.isInteger(position) || position <= 0 || position > props.items.length) return
  if (position > 3) expanded.value = true
  void nextTick(() => document.getElementById(`evidence-source-${position}`)?.scrollIntoView({ block: 'nearest' }))
}

onMounted(() => {
  window.addEventListener('hashchange', revealLinkedEvidence)
  revealLinkedEvidence()
})
onUnmounted(() => window.removeEventListener('hashchange', revealLinkedEvidence))

interface EvidenceView {
  title: string
  section: string
  detail: string
  reason: string
  url: string | null
}

function evidenceView(item: unknown, index: number): EvidenceView {
  if (typeof item === 'string') return { title: `${t('common.evidenceReference')} ${index + 1}`, section: '', detail: item, reason: '', url: null }
  if (!item || typeof item !== 'object') return { title: `${t('common.evidenceReference')} ${index + 1}`, section: '', detail: String(item ?? ''), reason: '', url: null }
  const evidence = item as Record<string, unknown>
  const title = evidence.sourceTitle ?? evidence.title ?? evidence.displayName ?? evidence.source
    ?? evidence.metricName ?? evidence.citationId ?? evidence.evidenceId
    ?? (evidence.chunkId == null ? null : `${t('common.evidenceReference')} #${evidence.chunkId}`)
  const detail = evidence.excerpt ?? evidence.snippet ?? evidence.sanitizedText
    ?? evidence.text ?? evidence.description ?? evidence.summary
  const section = evidence.sectionTitle ?? evidence.section ?? evidence.category ?? ''
  const reason = evidence.reason ?? evidence.versionReference
    ?? (evidence.version == null ? '' : `v${String(evidence.version)}`)
  const candidateUrl = evidence.sourceUrl ?? evidence.url ?? evidence.link
  const url = typeof candidateUrl === 'string' && (/^https?:\/\//.test(candidateUrl) || candidateUrl.startsWith('/') || candidateUrl.startsWith('#'))
    ? candidateUrl : null
  return {
    title: String(title ?? `${t('common.evidenceReference')} ${index + 1}`),
    section: String(section ?? ''),
    detail: String(detail ?? ''),
    reason: String(reason ?? ''),
    url,
  }
}

function opensNewTab(url: string | null): boolean {
  // Route links must not replace the in-progress Copilot task. Open the
  // referenced governance/source page separately; hash-only links stay in
  // this page so the citation card can be revealed inline.
  return Boolean(url && !url.startsWith('#'))
}
</script>

<template>
  <section :class="['panel', { 'evidence-panel--embedded': embedded }]" aria-live="polite">
    <h2>{{ t('common.evidence') }}</h2>
    <ol v-if="items.length" class="evidence-list evidence-list--bounded">
      <li v-for="(item, index) in visibleItems" :id="`evidence-source-${index + 1}`" :key="index" class="evidence-source-card">
        <span class="evidence-source-card__number">{{ index + 1 }}</span>
        <div>
          <a v-if="evidenceView(item, index).url" :href="evidenceView(item, index).url ?? undefined" :target="opensNewTab(evidenceView(item, index).url) ? '_blank' : undefined" :rel="opensNewTab(evidenceView(item, index).url) ? 'noopener noreferrer' : undefined">{{ evidenceView(item, index).title }} <span aria-hidden="true">↗</span></a>
          <strong v-else>{{ evidenceView(item, index).title }}</strong>
          <small v-if="evidenceView(item, index).section">{{ evidenceView(item, index).section }}</small>
          <p v-if="evidenceView(item, index).detail">{{ evidenceView(item, index).detail }}</p>
          <small v-if="evidenceView(item, index).reason">{{ evidenceView(item, index).reason }}</small>
        </div>
      </li>
    </ol>
    <p v-else class="empty-state">{{ t('common.noData') }}</p>
    <button v-if="items.length > 3" class="button button--text" type="button" @click="expanded = !expanded">
      {{ expanded ? t('common.showLess') : t('common.showAllEvidence', { count: items.length }) }}
    </button>
  </section>
</template>
