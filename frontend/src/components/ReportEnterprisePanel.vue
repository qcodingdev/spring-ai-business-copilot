<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { api, ApiError, jsonBody } from '@/api/client'
import { useSession } from '@/composables/useSession'
import RequestId from './RequestId.vue'
import StatusBadge from './StatusBadge.vue'
import ToastMessage from './ToastMessage.vue'
import { formatDate } from '@/locales/format'

const props = defineProps<{ tab: 'records' | 'schedules' }>()
const emit = defineEmits<{ openReview: [draftId: number] }>()
const { t, te, locale } = useI18n()
const { isAdmin } = useSession()
type RecordItem = Record<string, any>
const loading = ref(false)
const errorCode = ref('')
const requestId = ref<string | null>(null)
const reports = ref<RecordItem[]>([])
const schedules = ref<RecordItem[]>([])
const scheduleRuns = ref<RecordItem[]>([])
const toast = ref('')
const toastTone = ref<'success' | 'danger'>('success')
let toastTimer: ReturnType<typeof setTimeout> | undefined
const schedule = ref({
  scheduleKey: '', reportType: 'TEAM_WEEKLY', titleTemplate: '', cronExpression: '0 0 9 ? * MON',
  zoneId: 'Asia/Shanghai', templateId: 'business-weekly', templateVersion: 'v1', enabled: true,
  includeSupportMetrics: true, connectionIds: '',
})
const reportTypes = ['TEAM_WEEKLY', 'BUSINESS_WEEKLY', 'PROJECT_STATUS', 'INCIDENT_REVIEW', 'SALES_REVIEW']

const localizedError = computed(() => {
  if (!errorCode.value) return ''
  return t(`errors.${te(`errors.${errorCode.value}`) ? errorCode.value : 'generic'}`)
})

function showToast(message: string, tone: 'success' | 'danger' = 'success'): void {
  if (toastTimer) clearTimeout(toastTimer)
  toast.value = message; toastTone.value = tone
  toastTimer = setTimeout(() => { toast.value = '' }, 4500)
}

function date(value: string | null): string {
  if (!value) return '—'
  return formatDate(value, locale.value) || '—'
}

function statusLabel(value: string | null): string {
  return value && te(`statuses.${value}`) ? t(`statuses.${value}`) : (value || t('common.unknown'))
}

function reportTypeLabel(value: string | null): string {
  return value && te(`report.types.${value}`) ? t(`report.types.${value}`) : (value || t('common.unknown'))
}

async function load(): Promise<void> {
  loading.value = true; errorCode.value = ''
  try {
    const response = await api<RecordItem[]>(props.tab === 'records' ? '/api/report-copilot/enterprise/reports' : '/api/report-copilot/enterprise/schedules')
    if (props.tab === 'records') reports.value = response.data ?? []
    else schedules.value = response.data ?? []
    requestId.value = response.requestId
  } catch (error) {
    errorCode.value = error instanceof ApiError ? error.errorCode : 'generic'
    requestId.value = error instanceof ApiError ? error.requestId : null
    showToast(localizedError.value, 'danger')
  } finally { loading.value = false }
}

async function saveSchedule(): Promise<void> {
  loading.value = true
  try {
    const response = await api<RecordItem>('/api/report-copilot/enterprise/schedules', {
      method: 'POST', ...jsonBody({
        ...schedule.value,
        selection: {
          connectionIds: schedule.value.connectionIds.split(',').map((item) => Number(item.trim())).filter((item) => Number.isInteger(item) && item > 0),
          dataHandoffReferences: [],
          includeSupportMetrics: schedule.value.includeSupportMetrics,
          previousDataHandoffReference: null,
        },
      }),
    })
    schedules.value = [response.data, ...schedules.value.filter((item) => item.scheduleKey !== response.data.scheduleKey)]
    requestId.value = response.requestId
    showToast(t('report.scheduleSaved'))
  } catch (error) {
    errorCode.value = error instanceof ApiError ? error.errorCode : 'generic'
    requestId.value = error instanceof ApiError ? error.requestId : null
    showToast(localizedError.value, 'danger')
  } finally { loading.value = false }
}

/** DATA-05：查看报告草稿的数据追溯链（草稿 ← 交接 ← 结果快照 ← SQL 候选）。 */
const traceDraftId = ref<number | null>(null)
const traceLoading = ref(false)
const traceLinks = ref<Record<string, any>[]>([])

async function loadTrace(draftId: number): Promise<void> {
  if (traceDraftId.value === draftId) {
    traceDraftId.value = null
    return
  }
  traceDraftId.value = draftId
  traceLoading.value = true
  try {
    const response = await api<Record<string, any>[]>(`/api/report-copilot/enterprise/drafts/${draftId}/data-trace`)
    traceLinks.value = response.data ?? []
    requestId.value = response.requestId
  } catch (error) {
    errorCode.value = error instanceof ApiError ? error.errorCode : 'generic'
    showToast(localizedError.value, 'danger')
  } finally { traceLoading.value = false }
}

function openReport(item: RecordItem): void {
  const draftId = Number(item.draftId)
  if (Number.isInteger(draftId) && draftId > 0) emit('openReview', draftId)
}

async function loadRuns(scheduleId: number): Promise<void> {
  loading.value = true
  try {
    const response = await api<RecordItem[]>(`/api/report-copilot/enterprise/schedules/${scheduleId}/runs`)
    scheduleRuns.value = response.data ?? []
    requestId.value = response.requestId
  } catch (error) {
    errorCode.value = error instanceof ApiError ? error.errorCode : 'generic'
    showToast(localizedError.value, 'danger')
  } finally { loading.value = false }
}

watch(() => props.tab, () => { void load() }, { immediate: true })
onUnmounted(() => { if (toastTimer) clearTimeout(toastTimer) })
</script>

<template>
  <section class="panel enterprise-panel">
    <div class="section-heading">
      <div><p class="enterprise-panel__description">{{ tab === 'records' ? t('report.recordsDescription') : t('report.schedulesDescription') }}</p></div>
      <button class="button button--secondary" type="button" :disabled="loading" @click="load">{{ loading ? t('common.loading') : t('common.refresh') }}</button>
    </div>

    <template v-if="tab === 'records'">
      <div v-if="reports.length" class="record-grid">
        <article v-for="item in reports" :key="item.draftId">
          <div class="section-heading"><h3>{{ item.title }}</h3><div class="button-row"><StatusBadge :label="statusLabel(item.status)" :tone="item.status === 'CONFIRMED' ? 'success' : item.status === 'NEEDS_REVIEW' ? 'warning' : 'info'" /><StatusBadge v-if="item.approvalStatus && item.status !== 'CANCELED' && item.status !== 'CANCELLED'" :label="statusLabel(item.approvalStatus)" :tone="item.approvalStatus === 'APPROVED' ? 'success' : item.approvalStatus === 'REJECTED' ? 'danger' : 'warning'" /></div></div>
          <p>{{ reportTypeLabel(item.reportType) }} · {{ item.periodStart }} – {{ item.periodEnd }}</p>
          <small>{{ t('report.createdAt') }}：{{ date(item.createdAt) }} · #{{ item.draftId }}</small>
          <p v-if="item.reviewReasons" class="field-hint">{{ item.reviewReasons }}</p>
          <button class="button button--secondary" type="button" data-testid="report-data-trace" :disabled="loading || traceLoading" @click="loadTrace(item.draftId)">
            {{ traceDraftId === item.draftId ? t('report.hideDataTrace') : t('report.viewDataTrace') }}
          </button>
          <div v-if="traceDraftId === item.draftId" class="report-data-trace" data-testid="report-data-trace-detail">
            <p v-if="traceLoading" class="field-hint">{{ t('common.loading') }}</p>
            <template v-else>
              <p v-if="!traceLinks.length" class="field-hint">{{ t('report.noDataTrace') }}</p>
              <ul v-else>
                <li v-for="link in traceLinks" :key="`${link.draftId}-${link.sourceReference}`">
                  <code>{{ link.sourceReference }}</code> · {{ t('report.traceResult') }} #{{ link.queryResultId ?? '—' }} · {{ t('report.traceCandidate') }} <code>{{ link.candidateId ?? '—' }}</code>
                </li>
              </ul>
            </template>
          </div>
          <button v-if="['DRAFTED', 'NEEDS_REVIEW'].includes(item.status)" class="button button--primary" type="button" :disabled="loading" @click="openReport(item)">{{ t('report.openReview') }}</button>
          <div v-if="item.status === 'CONFIRMED'" class="button-row">
            <a class="button button--secondary" :href="`/api/report-copilot/enterprise/reports/${item.draftId}/docx`">DOCX</a>
            <a class="button button--secondary" :href="`/api/report-copilot/enterprise/reports/${item.draftId}/pdf`">PDF</a>
            <a class="button button--secondary" :href="`/api/report-copilot/enterprise/reports/${item.draftId}/pptx`">PPTX</a>
          </div>
        </article>
      </div>
      <p v-else class="empty-state">{{ t('common.noData') }}</p>
    </template>

    <template v-else>
      <form v-if="isAdmin" class="connection-form" @submit.prevent="saveSchedule">
        <h3>{{ t('report.createSchedule') }}</h3>
        <div class="form-grid">
          <label>{{ t('report.scheduleKey') }}<input v-model="schedule.scheduleKey" required maxlength="100"></label>
          <label>{{ t('report.scheduleTitle') }}<input v-model="schedule.titleTemplate" required maxlength="300"></label>
          <label>{{ t('report.reportType') }}<select v-model="schedule.reportType"><option v-for="type in reportTypes" :key="type" :value="type">{{ reportTypeLabel(type) }}</option></select></label>
          <label>{{ t('report.cronExpression') }}<input v-model="schedule.cronExpression" required maxlength="100"></label>
          <label>{{ t('report.zoneId') }}<input v-model="schedule.zoneId" required maxlength="80"></label>
          <label>{{ t('report.connectionIds') }}<input v-model="schedule.connectionIds" maxlength="300" placeholder="1,2"></label>
          <label class="checkbox-label"><input v-model="schedule.includeSupportMetrics" type="checkbox"> {{ t('report.includeSupportMetrics') }}</label>
          <label class="checkbox-label"><input v-model="schedule.enabled" type="checkbox"> {{ t('common.enabled') }}</label>
        </div>
        <p class="field-hint">{{ t('report.scheduleHint') }}</p>
        <button class="button button--primary" type="submit" :disabled="loading">{{ t('common.save') }}</button>
      </form>
      <div v-if="schedules.length" class="record-grid">
        <article v-for="item in schedules" :key="item.id"><div class="section-heading"><h3>{{ item.scheduleKey }}</h3><StatusBadge :label="item.enabled ? t('statuses.ACTIVE') : t('statuses.DISABLED')" :tone="item.enabled ? 'success' : 'info'" /></div><p>{{ reportTypeLabel(item.reportType) }} · {{ item.cronExpression }} · {{ item.zoneId }}</p><small>{{ t('report.nextRun') }}：{{ date(item.nextRunAt) }}</small><small v-if="item.lastRunAt">{{ t('report.lastRun') }}：{{ date(item.lastRunAt) }}</small><button class="button button--secondary" type="button" :disabled="loading" @click="loadRuns(item.id)">{{ t('report.viewRuns') }}</button></article>
      </div>
      <p v-else class="empty-state">{{ t('common.noData') }}</p>
      <div v-if="scheduleRuns.length" class="table-scroll"><table class="data-table"><thead><tr><th>ID</th><th>{{ t('common.status') }}</th><th>{{ t('report.createdAt') }}</th><th>{{ t('report.failureReason') }}</th></tr></thead><tbody><tr v-for="run in scheduleRuns" :key="run.id"><td>#{{ run.id }}</td><td>{{ statusLabel(run.status) }}</td><td>{{ date(run.startedAt) }}</td><td>{{ run.reason || '—' }}</td></tr></tbody></table></div>
    </template>
    <RequestId :value="requestId" />
  </section>
  <ToastMessage :message="toast" :tone="toastTone" />
</template>
<style scoped>
.report-data-trace {
  margin: var(--space-2) 0;
  padding: var(--space-2) var(--space-3);
  border: 1px dashed var(--border, #d7dbe7);
  border-radius: var(--radius-sm, 8px);
}
.report-data-trace ul {
  margin: var(--space-1) 0 0;
  padding-left: 1.2rem;
}
</style>
