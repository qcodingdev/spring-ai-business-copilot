<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { api, ApiError, jsonBody } from '@/api/client'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import EnterpriseReadinessPanel from '@/components/EnterpriseReadinessPanel.vue'
import KnowledgeDocumentAdmin from '@/components/KnowledgeDocumentAdmin.vue'
import PageHeader from '@/components/PageHeader.vue'
import RequestId from '@/components/RequestId.vue'
import RoleGuard from '@/components/RoleGuard.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import ToastMessage from '@/components/ToastMessage.vue'
import { useSession } from '@/composables/useSession'

type AdminTab = 'overview' | 'readiness' | 'observability' | 'documents' | 'experience'
interface DemoJob { id: string; jobType: string; status: string; requestedBy?: string; summaryJson?: string; errorCategory?: string; createdAt?: string; finishedAt?: string }

const { t, te, locale } = useI18n()
const { session } = useSession()
const route = useRoute()
const router = useRouter()
const tabs: AdminTab[] = ['overview', 'readiness', 'observability', 'documents', 'experience']
const initialTab = String(route.query.tab ?? '')
const activeTab = ref<AdminTab>(tabs.includes(initialTab as AdminTab) ? initialTab as AdminTab : 'overview')
const readinessPanel = ref<{ load: () => Promise<void> } | null>(null)
const diagnostics = ref<Record<string, any> | null>(null)
const loading = ref(false)
const requestId = ref<string | null>(null)
const initializeOpen = ref(false)
const resetOpen = ref(false)
const currentJob = ref<DemoJob | null>(null)
const resetIntent = ref<{ willDelete: Record<string, number>; resetToken: string; expiresAt: string; requiredConfirmationText: string } | null>(null)
const resetConfirmation = ref('')
const toast = ref('')
const toastTone = ref<'success' | 'danger' | 'info'>('info')
let toastTimer: ReturnType<typeof setTimeout> | undefined
let jobTimer: ReturnType<typeof setTimeout> | undefined

const isPublicDemo = computed(() => session.value?.publicDemo === true)
const usage = computed<Record<string, any>[]>(() => diagnostics.value?.usage ?? [])
const demoJobs = computed<DemoJob[]>(() => diagnostics.value?.demoJobs ?? [])

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

async function load(): Promise<void> {
  loading.value = true
  try {
    const response = await api<Record<string, any>>('/api/admin/diagnostics')
    diagnostics.value = response.data
    requestId.value = response.requestId
  } catch (error) {
    requestId.value = error instanceof ApiError ? error.requestId : null
    showToast(errorText(error), 'danger')
  } finally {
    loading.value = false
  }
}

async function refresh(): Promise<void> {
  if (activeTab.value === 'readiness' && readinessPanel.value) {
    await Promise.all([load(), readinessPanel.value.load()])
    return
  }
  await load()
}

async function selectTab(tab: AdminTab): Promise<void> {
  activeTab.value = tab
  await router.replace({ query: { ...route.query, tab } })
}

async function pollJob(jobId: string): Promise<void> {
  if (jobTimer) clearTimeout(jobTimer)
  try {
    const response = await api<DemoJob>(`/api/admin/demo-data/jobs/${encodeURIComponent(jobId)}`)
    currentJob.value = response.data
    requestId.value = response.requestId
    if (['PENDING', 'RUNNING'].includes(response.data.status)) {
      jobTimer = setTimeout(() => { void pollJob(jobId) }, 1000)
      return
    }
    showToast(response.data.status === 'COMPLETED' ? t('admin.initializationCompleted') : t('admin.initializationFailed'), response.data.status === 'COMPLETED' ? 'success' : 'danger')
    await load()
  } catch (error) {
    showToast(errorText(error), 'danger')
  }
}

async function initialize(): Promise<void> {
  loading.value = true
  try {
    const response = await api<DemoJob>('/api/admin/demo-data/initialize', { method: 'POST' })
    currentJob.value = response.data
    requestId.value = response.requestId
    initializeOpen.value = false
    showToast(t('admin.initializationStarted'), 'info')
    void pollJob(response.data.id)
  } catch (error) {
    showToast(errorText(error), 'danger')
  } finally {
    loading.value = false
  }
}

async function prepareReset(): Promise<void> {
  if (!isPublicDemo.value) {
    showToast(t('admin.resetPublicDemoOnly'), 'info')
    return
  }
  loading.value = true
  try {
    const response = await api<NonNullable<typeof resetIntent.value>>('/api/admin/demo-data/reset-intents', { method: 'POST' })
    resetIntent.value = response.data
    requestId.value = response.requestId
  } catch (error) {
    showToast(errorText(error), 'danger')
  } finally {
    loading.value = false
  }
}

async function reset(): Promise<void> {
  if (!resetIntent.value) return
  loading.value = true
  try {
    const response = await api<DemoJob>('/api/admin/demo-data/reset', {
      method: 'POST', ...jsonBody({ resetToken: resetIntent.value.resetToken, confirmationText: resetConfirmation.value }),
    })
    currentJob.value = response.data
    requestId.value = response.requestId
    resetOpen.value = false
    resetIntent.value = null
    resetConfirmation.value = ''
    showToast(response.data.status === 'COMPLETED' ? t('admin.resetCompleted') : t('admin.resetFailed'), response.data.status === 'COMPLETED' ? 'success' : 'danger')
    await load()
  } catch (error) {
    showToast(errorText(error), 'danger')
  } finally {
    loading.value = false
  }
}

function number(value: unknown): string { return new Intl.NumberFormat(locale.value).format(Number(value ?? 0)) }
function latency(row: Record<string, any>): string { return row.calls ? `${Math.round(Number(row.total_latency_ms ?? 0) / Number(row.calls))} ms` : '—' }
function parsedSummary(job: DemoJob): string {
  if (!job.summaryJson) return ''
  try { return JSON.stringify(JSON.parse(job.summaryJson), null, 2) } catch { return job.summaryJson }
}

watch(() => route.query.tab, (value) => {
  const requested = String(value ?? '') as AdminTab
  activeTab.value = tabs.includes(requested) ? requested : 'overview'
})
onMounted(load)
onUnmounted(() => { if (toastTimer) clearTimeout(toastTimer); if (jobTimer) clearTimeout(jobTimer) })
</script>

<template>
  <RoleGuard :roles="['ADMIN']">
    <PageHeader :title="t('admin.title')" :description="t('admin.description')">
      <button class="button button--secondary" type="button" :disabled="loading" @click="refresh">{{ t('common.refresh') }}</button>
    </PageHeader>
    <div class="admin-layout">
      <nav class="admin-subnav" :aria-label="t('admin.submenu')">
        <button v-for="tab in tabs" :key="tab" type="button" :class="{ active: activeTab === tab }" @click="selectTab(tab)">
          {{ t(`admin.tabs.${tab}`) }}
        </button>
      </nav>

      <div v-if="activeTab === 'overview'" class="admin-content">
        <div class="metric-grid">
          <article class="metric-card"><span>{{ t('common.runtimeMode') }}</span><strong>{{ diagnostics?.runtimeMode ?? '—' }}</strong></article>
          <article class="metric-card"><span>{{ t('admin.chatModel') }}</span><strong>{{ diagnostics?.models?.chatModel ?? '—' }}</strong><small>{{ diagnostics?.models?.provider }}</small></article>
          <article class="metric-card"><span>{{ t('admin.embeddingModel') }}</span><strong>{{ diagnostics?.models?.embeddingModel ?? '—' }}</strong><small>{{ diagnostics?.models?.embeddingDimension }}</small></article>
          <article class="metric-card"><span>{{ t('admin.enabledScenarios') }}</span><strong>{{ number(diagnostics?.enabledScenarios) }}</strong></article>
        </div>
        <section class="panel admin-section">
          <h2>{{ t('admin.moduleHealth') }}</h2>
          <div class="status-grid"><div v-for="(healthy, name) in diagnostics?.modules ?? {}" :key="String(name)"><span>{{ name }}</span><StatusBadge :label="healthy ? t('admin.available') : t('admin.unavailable')" :tone="healthy ? 'success' : 'danger'" /></div></div>
        </section>
        <section class="panel admin-section">
          <h2>{{ t('admin.enterpriseOverview') }}</h2>
          <div class="status-grid"><div v-for="(count, name) in diagnostics?.enterpriseExpansion ?? {}" :key="String(name)"><span>{{ name }}</span><strong>{{ number(count) }}</strong></div></div>
        </section>
      </div>

      <EnterpriseReadinessPanel v-else-if="activeTab === 'readiness'" ref="readinessPanel" />

      <div v-else-if="activeTab === 'observability'" class="admin-content">
        <section class="panel admin-section">
          <div class="section-heading"><div><h2>{{ t('admin.callObservability') }}</h2><p>{{ t('admin.callObservabilityDescription') }}</p></div></div>
          <div class="table-scroll"><table class="data-table"><thead><tr><th>{{ t('admin.operation') }}</th><th>{{ t('admin.model') }}</th><th>{{ t('admin.calls') }}</th><th>{{ t('admin.successFailure') }}</th><th>{{ t('admin.tokens') }}</th><th>{{ t('admin.averageLatency') }}</th></tr></thead><tbody><tr v-for="(row, index) in usage" :key="index"><td><strong>{{ row.operation || row.call_type }}</strong><small>{{ row.usage_date }}</small></td><td>{{ row.provider_name }} / {{ row.model_name }}</td><td>{{ number(row.calls) }}</td><td>{{ number(row.successes) }} / {{ number(row.failures) }}</td><td>{{ number(row.input_tokens) }} + {{ number(row.output_tokens) }}</td><td>{{ latency(row) }}</td></tr><tr v-if="!usage.length"><td colspan="6" class="empty-state">{{ t('common.noData') }}</td></tr></tbody></table></div>
        </section>
        <section class="panel admin-section">
          <h2>{{ t('admin.resilience') }}</h2>
          <pre class="result-preview result-preview--bounded">{{ JSON.stringify(diagnostics?.aiResilience ?? {}, null, 2) }}</pre>
        </section>
        <section class="panel admin-section">
          <h2>{{ t('admin.promptVersions') }}</h2>
          <div class="prompt-list"><div v-for="prompt in diagnostics?.prompts ?? []" :key="prompt.name"><strong>{{ prompt.name }}</strong><code>{{ prompt.contentHash }}</code></div></div>
        </section>
      </div>

      <KnowledgeDocumentAdmin v-else-if="activeTab === 'documents'" />

      <div v-else class="admin-content">
        <section class="panel admin-section experience-explainer">
          <h2>{{ t('admin.demoData') }}</h2>
          <p>{{ t('admin.demoDataPurpose') }}</p>
          <ol><li>{{ t('admin.demoDataStepOne') }}</li><li>{{ t('admin.demoDataStepTwo') }}</li><li>{{ t('admin.demoDataStepThree') }}</li></ol>
          <div class="button-row"><button class="button button--primary" type="button" :disabled="loading" @click="initializeOpen = true">{{ t('admin.initialize') }}</button><button class="button button--danger" type="button" :disabled="loading || !isPublicDemo" @click="prepareReset">{{ t('admin.prepareReset') }}</button></div>
          <p v-if="!isPublicDemo" class="field-hint">{{ t('admin.resetPublicDemoOnly') }}</p>
        </section>
        <section v-if="currentJob" class="panel admin-section job-progress" aria-live="polite">
          <div class="section-heading"><div><h2>{{ t('admin.currentJob') }}</h2><p>{{ currentJob.id }}</p></div><StatusBadge :label="currentJob.status" :tone="currentJob.status === 'COMPLETED' ? 'success' : currentJob.status === 'FAILED' ? 'danger' : 'warning'" /></div>
          <p v-if="['PENDING', 'RUNNING'].includes(currentJob.status)">{{ t('admin.jobRunning') }}</p><pre v-if="parsedSummary(currentJob)" class="result-preview result-preview--bounded">{{ parsedSummary(currentJob) }}</pre><p v-if="currentJob.errorCategory" class="alert alert--danger">{{ currentJob.errorCategory }}</p>
        </section>
        <section v-if="resetIntent" class="panel admin-section reset-preview">
          <h2>{{ t('admin.resetImpact') }}</h2><div class="status-grid"><div v-for="(count, name) in resetIntent.willDelete" :key="name"><span>{{ name }}</span><strong>{{ number(count) }}</strong></div></div>
          <label>{{ t('admin.resetConfirmation') }}<input v-model="resetConfirmation" autocomplete="off" maxlength="100"></label><p class="field-hint">{{ resetIntent.requiredConfirmationText }}</p><button class="button button--danger" type="button" :disabled="resetConfirmation !== resetIntent.requiredConfirmationText" @click="resetOpen = true">{{ t('admin.reset') }}</button>
        </section>
        <section class="panel admin-section"><h2>{{ t('admin.recentJobs') }}</h2><div class="table-scroll"><table class="data-table"><thead><tr><th>ID</th><th>{{ t('admin.jobType') }}</th><th>{{ t('common.status') }}</th><th>{{ t('admin.requestedBy') }}</th><th>{{ t('admin.createdAt') }}</th></tr></thead><tbody><tr v-for="job in demoJobs" :key="job.id"><td><code>{{ job.id }}</code></td><td>{{ job.jobType }}</td><td>{{ job.status }}</td><td>{{ job.requestedBy }}</td><td>{{ job.createdAt }}</td></tr></tbody></table></div></section>
      </div>
      <RequestId v-if="activeTab !== 'documents' && activeTab !== 'readiness'" :value="requestId" />
    </div>

    <ConfirmDialog :open="initializeOpen" :operation="t('admin.initialize')" target="fictional-demo-dataset" current-state="CURRENT_DATA_RETAINED" target-state="INITIALIZING" :impact="t('admin.initializeImpact')" :recoverable="true" :risk="t('admin.initializeRisk')" :busy="loading" @confirm="initialize" @cancel="initializeOpen = false" />
    <ConfirmDialog :open="resetOpen" :operation="t('admin.reset')" target="fictional-demo-dataset" current-state="RESET_INTENT_CREATED" target-state="RESET_PENDING" :impact="t('admin.resetImpactDescription')" :recoverable="false" :expires-at="resetIntent?.expiresAt" :risk="t('admin.resetRisk')" :busy="loading" @confirm="reset" @cancel="resetOpen = false" />
    <ToastMessage :message="toast" :tone="toastTone" />
    <template #denied><p class="permission-denied">{{ t('admin.adminOnly') }}</p></template>
  </RoleGuard>
</template>
