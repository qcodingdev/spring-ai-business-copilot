<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { api, ApiError, jsonBody } from '@/api/client'
import RequestId from '@/components/RequestId.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import ToastMessage from '@/components/ToastMessage.vue'

type OverallStatus = 'READY' | 'ATTENTION' | 'BLOCKED' | 'NOT_CONFIGURED'
type CheckStatus = 'PASS' | 'WARNING' | 'BLOCKER'
type ModuleName = 'PLATFORM' | 'DATA' | 'KNOWLEDGE' | 'SUPPORT' | 'REPORT' | 'HR'

interface ReadinessCheck {
  checkId: string
  module: ModuleName
  status: CheckStatus
  affectedCount: number
  threshold: string | null
  actionPath: string
}

interface ReadinessAssessment {
  schemaVersion: number
  applicationVersion: string
  runtimeMode: string
  status: OverallStatus
  passedCount: number
  warningCount: number
  blockerCount: number
  checks: ReadinessCheck[]
  contentHash: string
  generatedAt: string
  validUntil: string
}

interface ReadinessSnapshot extends ReadinessAssessment {
  id: number
  snapshotReference: string
  purpose: string
  generatedBy: string
}

interface PageResponse<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

const { t, te, locale } = useI18n()
const assessment = ref<ReadinessAssessment | null>(null)
const snapshots = ref<ReadinessSnapshot[]>([])
const historyPage = ref(0)
const historyTotalPages = ref(0)
const historyTotalElements = ref(0)
const purpose = ref('')
const loading = ref(false)
const saving = ref(false)
const requestId = ref<string | null>(null)
const error = ref('')
const toast = ref('')
const toastTone = ref<'success' | 'danger' | 'info'>('info')
let toastTimer: ReturnType<typeof setTimeout> | undefined

const moduleOrder: ModuleName[] = ['PLATFORM', 'DATA', 'KNOWLEDGE', 'SUPPORT', 'REPORT', 'HR']
const groupedChecks = computed(() => moduleOrder
  .map((module) => ({ module, checks: assessment.value?.checks.filter((check) => check.module === module) ?? [] }))
  .filter((group) => group.checks.length > 0))

function showToast(message: string, tone: 'success' | 'danger' | 'info' = 'info'): void {
  if (toastTimer) clearTimeout(toastTimer)
  toast.value = message
  toastTone.value = tone
  toastTimer = setTimeout(() => { toast.value = '' }, 5000)
}

function errorText(value: unknown): string {
  const code = value instanceof ApiError ? value.errorCode : 'generic'
  return t(`errors.${te(`errors.${code}`) ? code : 'generic'}`)
}

function statusTone(status: OverallStatus | CheckStatus): 'success' | 'warning' | 'danger' {
  if (status === 'READY' || status === 'PASS') return 'success'
  if (status === 'ATTENTION' || status === 'WARNING') return 'warning'
  return 'danger'
}

function checkTitle(checkId: string): string {
  const key = `admin.readinessChecks.${checkId}.title`
  return te(key) ? t(key) : checkId
}

function checkDescription(checkId: string): string {
  const key = `admin.readinessChecks.${checkId}.description`
  return te(key) ? t(key) : checkId
}

function number(value: unknown): string {
  return new Intl.NumberFormat(locale.value).format(Number(value ?? 0))
}

function dateTime(value: string): string {
  return new Intl.DateTimeFormat(locale.value, { dateStyle: 'medium', timeStyle: 'short' })
    .format(new Date(value))
}

function expired(snapshot: ReadinessSnapshot): boolean {
  return Date.parse(snapshot.validUntil) <= Date.now()
}

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const [liveResult, historyResult] = await Promise.allSettled([
      api<ReadinessAssessment>('/api/admin/readiness'),
      api<PageResponse<ReadinessSnapshot>>('/api/admin/readiness/snapshots?page=0&size=20'),
    ])
    let failure: unknown = null
    if (liveResult.status === 'fulfilled') {
      assessment.value = liveResult.value.data
      requestId.value = liveResult.value.requestId
    } else {
      assessment.value = null
      failure = liveResult.reason
    }
    if (historyResult.status === 'fulfilled') {
      const historyResponse = historyResult.value
      snapshots.value = historyResponse.data.content ?? []
      historyPage.value = historyResponse.data.page
      historyTotalPages.value = historyResponse.data.totalPages
      historyTotalElements.value = historyResponse.data.totalElements
      requestId.value ??= historyResponse.requestId
    } else {
      failure ??= historyResult.reason
    }
    if (failure) {
      requestId.value = failure instanceof ApiError ? failure.requestId ?? requestId.value : requestId.value
      error.value = errorText(failure)
    }
  } finally {
    loading.value = false
  }
}

async function createSnapshot(): Promise<void> {
  if (!purpose.value.trim()) return
  saving.value = true
  error.value = ''
  try {
    const response = await api<ReadinessSnapshot>('/api/admin/readiness/snapshots', {
      method: 'POST',
      ...jsonBody({ purpose: purpose.value.trim() }),
    })
    const previousPage = historyPage.value
    if (previousPage === 0) {
      snapshots.value = [response.data, ...snapshots.value.filter((item) => item.id !== response.data.id)]
        .slice(0, 20)
    }
    historyTotalElements.value += 1
    historyTotalPages.value = Math.max(1, Math.ceil(historyTotalElements.value / 20))
    assessment.value = response.data
    requestId.value = response.requestId
    purpose.value = ''
    showToast(t('admin.readinessSnapshotCreated'), 'success')
    if (previousPage !== 0) void changeHistoryPage(0)
  } catch (value) {
    requestId.value = value instanceof ApiError ? value.requestId : requestId.value
    error.value = errorText(value)
    showToast(error.value, 'danger')
  } finally {
    saving.value = false
  }
}

async function changeHistoryPage(page: number): Promise<void> {
  if (page < 0 || page >= historyTotalPages.value || page === historyPage.value) return
  loading.value = true
  error.value = ''
  try {
    const response = await api<PageResponse<ReadinessSnapshot>>(
      `/api/admin/readiness/snapshots?page=${page}&size=20`,
    )
    snapshots.value = response.data.content ?? []
    historyPage.value = response.data.page
    historyTotalPages.value = response.data.totalPages
    historyTotalElements.value = response.data.totalElements
    requestId.value = response.requestId
  } catch (value) {
    requestId.value = value instanceof ApiError ? value.requestId : requestId.value
    error.value = errorText(value)
  } finally {
    loading.value = false
  }
}

defineExpose({ load })
onMounted(load)
onUnmounted(() => { if (toastTimer) clearTimeout(toastTimer) })
</script>

<template>
  <div class="admin-content readiness-panel">
    <section class="panel admin-section readiness-intro">
      <div class="section-heading">
        <div>
          <h2>{{ t('admin.readinessTitle') }}</h2>
          <p>{{ t('admin.readinessDescription') }}</p>
        </div>
        <button class="button button--secondary" type="button" :disabled="loading" @click="load">
          {{ loading ? t('common.loading') : t('admin.rerunReadiness') }}
        </button>
      </div>
      <p class="field-hint">{{ t('admin.readinessBoundary') }}</p>
      <p v-if="error" class="alert alert--danger" role="alert">{{ error }}</p>
    </section>

    <div v-if="assessment" class="metric-grid readiness-metrics" aria-live="polite">
      <article class="metric-card">
        <span>{{ t('admin.overallReadiness') }}</span>
        <StatusBadge :label="t(`admin.readinessStatuses.${assessment.status}`)" :tone="statusTone(assessment.status)" />
        <small>{{ assessment.applicationVersion }} · {{ assessment.runtimeMode }}</small>
      </article>
      <article class="metric-card"><span>{{ t('admin.passedChecks') }}</span><strong>{{ number(assessment.passedCount) }}</strong></article>
      <article class="metric-card"><span>{{ t('admin.warningChecks') }}</span><strong>{{ number(assessment.warningCount) }}</strong></article>
      <article class="metric-card"><span>{{ t('admin.blockerChecks') }}</span><strong>{{ number(assessment.blockerCount) }}</strong></article>
    </div>

    <section v-if="assessment" class="panel admin-section">
      <div class="section-heading"><div><h2>{{ t('admin.liveReadinessChecks') }}</h2><p>{{ t('admin.generatedAt', { value: dateTime(assessment.generatedAt) }) }}</p></div><code class="readiness-hash">{{ assessment.contentHash }}</code></div>
      <div class="readiness-groups">
        <section v-for="group in groupedChecks" :key="group.module" class="readiness-group">
          <h3>{{ t(`admin.readinessModules.${group.module}`) }}</h3>
          <div class="readiness-checks">
            <article v-for="check in group.checks" :key="check.checkId" class="readiness-check" :class="`readiness-check--${check.status.toLowerCase()}`">
              <div class="readiness-check__body">
                <div class="readiness-check__heading"><strong>{{ checkTitle(check.checkId) }}</strong><StatusBadge :label="t(`admin.readinessStatuses.${check.status}`)" :tone="statusTone(check.status)" /></div>
                <p>{{ checkDescription(check.checkId) }}</p>
                <small>{{ t('admin.affectedCount', { count: number(check.affectedCount) }) }}<template v-if="check.threshold"> · {{ t('admin.readinessWindow', { value: check.threshold }) }}</template></small>
              </div>
              <RouterLink v-if="check.status !== 'PASS'" class="button button--secondary" :to="check.actionPath">{{ t('admin.openRemediation') }}</RouterLink>
            </article>
          </div>
        </section>
      </div>
    </section>

    <section class="panel admin-section readiness-evidence">
      <div class="section-heading"><div><h2>{{ t('admin.readinessEvidence') }}</h2><p>{{ t('admin.readinessEvidenceDescription') }}</p></div></div>
      <form class="readiness-snapshot-form" @submit.prevent="createSnapshot">
        <label>{{ t('admin.snapshotPurpose') }}<input v-model="purpose" required maxlength="200" :placeholder="t('admin.snapshotPurposePlaceholder')"></label>
        <button class="button button--primary" type="submit" :disabled="saving || !assessment || !purpose.trim()">{{ saving ? t('common.loading') : t('admin.saveReadinessSnapshot') }}</button>
      </form>
      <p class="field-hint">{{ t('admin.snapshotNotice') }}</p>
    </section>

    <section class="panel admin-section">
      <div class="section-heading"><div><h2>{{ t('admin.readinessHistory') }}</h2><p>{{ t('admin.readinessHistoryDescription') }}</p></div></div>
      <div v-if="snapshots.length" class="readiness-history">
        <details v-for="snapshot in snapshots" :key="snapshot.id" class="readiness-snapshot">
          <summary>
            <span><strong>{{ snapshot.purpose }}</strong><small>{{ dateTime(snapshot.generatedAt) }} · {{ snapshot.generatedBy }}</small></span>
            <span class="readiness-snapshot__status"><StatusBadge :label="t(`admin.readinessStatuses.${snapshot.status}`)" :tone="statusTone(snapshot.status)" /><StatusBadge :label="expired(snapshot) ? t('admin.snapshotExpired') : t('admin.snapshotValid')" :tone="expired(snapshot) ? 'warning' : 'success'" /></span>
          </summary>
          <div class="readiness-snapshot__details">
            <p><strong>{{ t('admin.snapshotReference') }}:</strong> <code>{{ snapshot.snapshotReference }}</code></p>
            <p><strong>{{ t('admin.validUntil') }}:</strong> {{ dateTime(snapshot.validUntil) }}</p>
            <p><strong>{{ t('admin.contentHash') }}:</strong> <code>{{ snapshot.contentHash }}</code></p>
            <div class="readiness-snapshot__checks"><span v-for="check in snapshot.checks" :key="check.checkId"><StatusBadge :label="check.checkId" :tone="statusTone(check.status)" /><small>{{ number(check.affectedCount) }}</small></span></div>
          </div>
        </details>
      </div>
      <p v-else class="empty-state">{{ t('admin.noReadinessSnapshots') }}</p>
      <div v-if="historyTotalPages > 1" class="readiness-pagination">
        <button class="button button--secondary" type="button" :disabled="loading || historyPage === 0" @click="changeHistoryPage(historyPage - 1)">{{ t('admin.previousEvidencePage') }}</button>
        <span>{{ t('admin.evidencePage', { current: historyPage + 1, total: historyTotalPages, count: number(historyTotalElements) }) }}</span>
        <button class="button button--secondary" type="button" :disabled="loading || historyPage + 1 >= historyTotalPages" @click="changeHistoryPage(historyPage + 1)">{{ t('admin.nextEvidencePage') }}</button>
      </div>
    </section>
    <RequestId :value="requestId" />
    <ToastMessage :message="toast" :tone="toastTone" />
  </div>
</template>
