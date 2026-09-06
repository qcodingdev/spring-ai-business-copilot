<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { api, ApiError } from '@/api/client'
import RequestId from './RequestId.vue'
import ToastMessage from './ToastMessage.vue'
import { useSession } from '@/composables/useSession'

/** SUP-05：只读质量案例库；案例由真实草稿的人工复核修改自动产生。 */

interface QualityCase {
  id: number
  ticketRef: string
  caseType: string
  failureSummary: string
  revisionReason?: string | null
  draftId?: number | null
  draftVersion?: string | null
  createdBy: string
  createdAt: string
}

interface QualityMetrics {
  accepted: number
  editedAccepted: number
  rejected: number
  handedOff: number
  slaAtRisk: number
  slaBreached: number
}

const { t, te } = useI18n()
const { isAdmin } = useSession()
const cases = ref<QualityCase[]>([])
const metrics = ref<QualityMetrics | null>(null)
const caseTypeFilter = ref('')
const loading = ref(false)
const requestId = ref<string | null>(null)
const toast = ref('')
const toastTone = ref<'success' | 'danger' | 'info'>('info')
let toastTimer: ReturnType<typeof setTimeout> | undefined

const caseTypes = ['NO_EVIDENCE', 'EVIDENCE_EXPIRED', 'RISKY_PROMISE', 'PERMISSION_LIMIT', 'TOOL_FAILURE', 'REVIEW_EDIT']

function showToast(message: string, tone: 'success' | 'danger' | 'info' = 'info'): void {
  if (toastTimer) clearTimeout(toastTimer)
  toast.value = message
  toastTone.value = tone
  toastTimer = setTimeout(() => { toast.value = '' }, 5000)
}

function errorText(error: unknown): string {
  const code = error instanceof ApiError ? error.errorCode : 'generic'
  return t(`errors.${te(`errors.${code}`) ? code : 'generic'}`)
}

function caseTypeLabel(value: string): string {
  return te(`support.qualityCases.types.${value}`) ? t(`support.qualityCases.types.${value}`) : value
}

async function load(): Promise<void> {
  loading.value = true
  try {
    const query = caseTypeFilter.value ? `?caseType=${encodeURIComponent(caseTypeFilter.value)}` : ''
    const [caseResponse, metricsResponse] = await Promise.all([
      api<QualityCase[]>(`/api/support-copilot/enterprise/quality-cases${query}`),
      api<QualityMetrics>('/api/support-copilot/enterprise/quality-metrics'),
    ])
    cases.value = caseResponse.data ?? []
    metrics.value = metricsResponse.data ?? null
    requestId.value = caseResponse.requestId ?? metricsResponse.requestId
  } catch (error) {
    requestId.value = error instanceof ApiError ? error.requestId : null
    showToast(errorText(error), 'danger')
  } finally {
    loading.value = false
  }
}

async function refreshSla(): Promise<void> {
  if (loading.value || !isAdmin.value) return
  loading.value = true
  try {
    const response = await api<number>('/api/support-copilot/enterprise/sla/refresh', { method: 'POST' })
    requestId.value = response.requestId
    showToast(t('support.qualityCases.slaRefreshed', { count: response.data }), 'success')
    await load()
  } catch (error) {
    requestId.value = error instanceof ApiError ? error.requestId : null
    showToast(errorText(error), 'danger')
  } finally { loading.value = false }
}

onMounted(load)
onUnmounted(() => { if (toastTimer) clearTimeout(toastTimer) })
</script>

<template>
  <section class="panel" :aria-label="t('support.qualityCases.title')" data-testid="support-quality-cases">
    <div class="section-heading">
      <div>
        <h2>{{ t('support.qualityCases.title') }}</h2>
        <p>{{ t('support.qualityCases.description') }}</p>
      </div>
      <button class="button" type="button" :disabled="loading" @click="load">
        {{ t('common.refresh') }}
      </button>
    </div>

    <div v-if="metrics" class="queue-summary" aria-live="polite">
      <article><span>{{ t('support.qualityCases.metrics.accepted') }}</span><strong>{{ metrics.accepted }}</strong></article>
      <article><span>{{ t('support.qualityCases.metrics.editedAccepted') }}</span><strong>{{ metrics.editedAccepted }}</strong></article>
      <article><span>{{ t('support.qualityCases.metrics.rejected') }}</span><strong>{{ metrics.rejected }}</strong></article>
      <article><span>{{ t('support.qualityCases.metrics.handedOff') }}</span><strong>{{ metrics.handedOff }}</strong></article>
      <article><span>{{ t('support.qualityCases.metrics.slaAtRisk') }}</span><strong>{{ metrics.slaAtRisk }}</strong></article>
      <article><span>{{ t('support.qualityCases.metrics.slaBreached') }}</span><strong>{{ metrics.slaBreached }}</strong></article>
    </div>
    <button v-if="isAdmin" class="button button--secondary" type="button" :disabled="loading" @click="refreshSla">{{ t('support.qualityCases.refreshSla') }}</button>

    <label class="quality-filter">{{ t('support.qualityCases.caseType') }}
      <select v-model="caseTypeFilter" data-testid="quality-case-filter" @change="load">
        <option value="">{{ t('common.all') }}</option>
        <option v-for="option in caseTypes" :key="option" :value="option">{{ caseTypeLabel(option) }}</option>
      </select>
    </label>

    <div class="table-scroll">
      <table class="data-table" data-testid="quality-cases-table">
        <thead>
          <tr>
            <th>{{ t('support.qualityCases.ticketRef') }}</th>
            <th>{{ t('support.qualityCases.caseType') }}</th>
            <th>{{ t('support.qualityCases.failureSummary') }}</th>
            <th>{{ t('support.qualityCases.revisionReason') }}</th>
            <th>{{ t('support.qualityCases.draftVersion') }}</th>
            <th>{{ t('support.qualityCases.createdBy') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in cases" :key="item.id">
            <td><code>{{ item.ticketRef }}</code></td>
            <td>{{ caseTypeLabel(item.caseType) }}</td>
            <td>{{ item.failureSummary }}</td>
            <td>{{ item.revisionReason || '—' }}</td>
            <td>{{ item.draftVersion || '—' }}</td>
            <td>{{ item.createdBy }}</td>
          </tr>
          <tr v-if="!cases.length && !loading">
            <td colspan="6" class="empty-state">{{ t('support.qualityCases.empty') }}</td>
          </tr>
        </tbody>
      </table>
    </div>
    <RequestId :value="requestId" />
    <ToastMessage :message="toast" :tone="toastTone" />
  </section>
</template>

<style scoped>
.quality-filter {
  display: grid;
  max-width: 22rem;
  gap: var(--space-2);
  margin-bottom: var(--space-4);
}
</style>
