<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { api, ApiError, jsonBody } from '@/api/client'
import { useSession } from '@/composables/useSession'
import ConfirmDialog from './ConfirmDialog.vue'
import DataExecutionResult from './DataExecutionResult.vue'
import RequestId from './RequestId.vue'
import StatusBadge from './StatusBadge.vue'
import ToastMessage from './ToastMessage.vue'

const props = defineProps<{ tab: string }>()
const { t, te } = useI18n()
const { isAdmin, canOperate, canReview, session } = useSession()

type DataRecord = Record<string, any>
type PendingAction = 'query-launch' | 'query-execute' | 'handoff' | null

const loading = ref(false)
const errorCode = ref('')
const requestId = ref<string | null>(null)
const metrics = ref<DataRecord[]>([])
const templates = ref<DataRecord[]>([])
const auditLogs = ref<DataRecord[]>([])
const results = ref<DataRecord[]>([])
const handoffs = ref<DataRecord[]>([])
const queryCandidate = ref<DataRecord | null>(null)
const queryExecution = ref<DataRecord | null>(null)
const templateForm = ref({ templateKey: '', name: '', description: '', sql: '' })
const metricForm = ref({ metricKey: '', displayName: '', description: '', unit: '', expressionSql: '' })
const handoffTitle = ref('')
const selectedResultId = ref<number | null>(null)
const pendingAction = ref<PendingAction>(null)
const pendingQuery = ref<{ kind: 'template' | 'metric'; item: DataRecord } | null>(null)
const toast = ref('')
const toastTone = ref<'success' | 'danger'>('success')
let toastTimer: ReturnType<typeof setTimeout> | undefined

const localizedError = computed(() => {
  if (!errorCode.value) return ''
  return t(`errors.${te(`errors.${errorCode.value}`) ? errorCode.value : 'generic'}`)
})

function showToast(message: string, tone: 'success' | 'danger' = 'success'): void {
  if (toastTimer) clearTimeout(toastTimer)
  toast.value = message
  toastTone.value = tone
  toastTimer = setTimeout(() => { toast.value = '' }, 5000)
}

function resetTransient(): void {
  queryCandidate.value = null
  queryExecution.value = null
  selectedResultId.value = null
  pendingAction.value = null
  pendingQuery.value = null
}

async function load(): Promise<void> {
  loading.value = true
  errorCode.value = ''
  resetTransient()
  try {
    if (props.tab === 'governance') {
      const [metricResponse, templateResponse] = await Promise.all([
        api<DataRecord[]>('/api/data-copilot/metrics'),
        api<DataRecord[]>('/api/data-copilot/query-templates'),
      ])
      metrics.value = metricResponse.data ?? []
      templates.value = templateResponse.data ?? []
      requestId.value = templateResponse.requestId ?? metricResponse.requestId
    } else if (props.tab === 'records') {
      const resultResponse = await api<DataRecord[]>('/api/data-copilot/query-results')
      results.value = resultResponse.data ?? []
      requestId.value = resultResponse.requestId
      if (canReview.value) {
        const auditResponse = await api<DataRecord[]>('/api/data-copilot/audit-logs')
        auditLogs.value = auditResponse.data ?? []
        requestId.value = auditResponse.requestId ?? requestId.value
      } else {
        auditLogs.value = []
      }
    } else if (props.tab === 'handoff') {
      const [handoffResponse, resultResponse] = await Promise.all([
        api<DataRecord[]>('/api/data-copilot/report-handoffs'),
        api<DataRecord[]>('/api/data-copilot/query-results'),
      ])
      handoffs.value = handoffResponse.data ?? []
      results.value = resultResponse.data ?? []
      requestId.value = handoffResponse.requestId ?? resultResponse.requestId
    }
  } catch (error) {
    errorCode.value = error instanceof ApiError ? error.errorCode : 'generic'
    requestId.value = error instanceof ApiError ? error.requestId : null
    showToast(localizedError.value, 'danger')
  } finally {
    loading.value = false
  }
}

async function saveTemplate(): Promise<void> {
  loading.value = true
  errorCode.value = ''
  try {
    const response = await api<DataRecord>('/api/data-copilot/query-templates', {
      method: 'POST',
      ...jsonBody(templateForm.value),
    })
    templates.value = [response.data, ...templates.value]
    templateForm.value = { templateKey: '', name: '', description: '', sql: '' }
    requestId.value = response.requestId
    showToast(t('data.templateSaved'))
  } catch (error) {
    errorCode.value = error instanceof ApiError ? error.errorCode : 'generic'
    requestId.value = error instanceof ApiError ? error.requestId : null
    showToast(localizedError.value, 'danger')
  } finally {
    loading.value = false
  }
}

async function saveMetric(): Promise<void> {
  loading.value = true
  try {
    const response = await api<DataRecord>('/api/data-copilot/metrics', {
      method: 'POST', ...jsonBody(metricForm.value),
    })
    metrics.value = [response.data, ...metrics.value]
    metricForm.value = { metricKey: '', displayName: '', description: '', unit: '', expressionSql: '' }
    requestId.value = response.requestId
    showToast(t('data.metricSaved'))
  } catch (error) {
    errorCode.value = error instanceof ApiError ? error.errorCode : 'generic'
    requestId.value = error instanceof ApiError ? error.requestId : null
    showToast(localizedError.value, 'danger')
  } finally { loading.value = false }
}

async function govern(path: string, collection: 'metric' | 'template', id: number, message: string): Promise<void> {
  loading.value = true
  try {
    const response = await api<DataRecord>(path, { method: 'POST' })
    if (collection === 'metric') metrics.value = metrics.value.map((item) => item.id === id ? response.data : item)
    else templates.value = templates.value.map((item) => item.id === id ? response.data : item)
    requestId.value = response.requestId
    showToast(message)
  } catch (error) {
    errorCode.value = error instanceof ApiError ? error.errorCode : 'generic'
    requestId.value = error instanceof ApiError ? error.requestId : null
    showToast(localizedError.value, 'danger')
  } finally { loading.value = false }
}

async function approveTemplate(template: DataRecord): Promise<void> {
  await govern(`/api/data-copilot/query-templates/${encodeURIComponent(String(template.id))}/approve`, 'template', template.id, t('data.templateApproved'))
}

function requestTemplateLaunch(template: DataRecord): void {
  pendingQuery.value = { kind: 'template', item: template }
  pendingAction.value = 'query-launch'
}

function requestMetricLaunch(metric: DataRecord): void {
  pendingQuery.value = { kind: 'metric', item: metric }
  pendingAction.value = 'query-launch'
}

function selectResultForHandoff(resultId: number): void {
  selectedResultId.value = resultId
  if (!handoffTitle.value) handoffTitle.value = t('data.defaultHandoffTitle')
}

function requestHandoff(): void {
  if (selectedResultId.value === null || !handoffTitle.value.trim()) return
  pendingAction.value = 'handoff'
}

async function runPendingAction(): Promise<void> {
  if (pendingAction.value === 'query-launch' && pendingQuery.value) {
    await launchGovernedQuery(pendingQuery.value.kind, pendingQuery.value.item)
  } else if (pendingAction.value === 'query-execute') {
    await executeQueryCandidate()
  } else if (pendingAction.value === 'handoff' && selectedResultId.value !== null) {
    await createHandoff(selectedResultId.value)
  }
  pendingAction.value = null
  pendingQuery.value = null
}

async function launchGovernedQuery(kind: 'template' | 'metric', item: DataRecord): Promise<void> {
  loading.value = true
  try {
    const path = kind === 'template' ? 'query-templates' : 'metrics'
    const response = await api<DataRecord>(`/api/data-copilot/${path}/${encodeURIComponent(String(item.id))}/launch`, { method: 'POST' })
    queryCandidate.value = response.data
    requestId.value = response.requestId
    showToast(t(kind === 'template' ? 'data.templateLaunched' : 'data.metricLaunched'))
  } catch (error) {
    errorCode.value = error instanceof ApiError ? error.errorCode : 'generic'
    requestId.value = error instanceof ApiError ? error.requestId : null
    showToast(localizedError.value, 'danger')
  } finally {
    loading.value = false
  }
}

async function executeQueryCandidate(): Promise<void> {
  if (!queryCandidate.value?.candidateId || !queryCandidate.value?.confirmationToken) return
  loading.value = true
  try {
    const candidate = queryCandidate.value
    const response = await api<DataRecord>(`/api/data-copilot/sql-candidates/${encodeURIComponent(String(candidate.candidateId))}/execute`, {
      method: 'POST',
      ...jsonBody({ confirmationToken: candidate.confirmationToken }),
    })
    queryExecution.value = response.data
    queryCandidate.value = null
    requestId.value = response.requestId
    showToast(t('data.executionCompleted'))
  } catch (error) {
    errorCode.value = error instanceof ApiError ? error.errorCode : 'generic'
    requestId.value = error instanceof ApiError ? error.requestId : null
    showToast(localizedError.value, 'danger')
  } finally {
    loading.value = false
  }
}

async function createHandoff(resultId: number): Promise<void> {
  loading.value = true
  try {
    const response = await api<DataRecord>(`/api/data-copilot/query-results/${encodeURIComponent(String(resultId))}/report-handoff`, {
      method: 'POST',
      ...jsonBody({ title: handoffTitle.value }),
    })
    handoffs.value = [response.data, ...handoffs.value]
    requestId.value = response.requestId
    handoffTitle.value = ''
    showToast(t('data.handoffCreated'))
    await load()
  } catch (error) {
    errorCode.value = error instanceof ApiError ? error.errorCode : 'generic'
    requestId.value = error instanceof ApiError ? error.requestId : null
    showToast(localizedError.value, 'danger')
  } finally {
    loading.value = false
  }
}

watch(() => props.tab, () => { void load() }, { immediate: true })
onUnmounted(() => { if (toastTimer) clearTimeout(toastTimer) })
</script>

<template>
  <section class="panel data-enterprise-panel">
    <div class="section-heading">
      <div>
        <p class="enterprise-panel__description">{{ t(`data.${tab}Description`) }}</p>
      </div>
      <button class="button button--secondary" type="button" :disabled="loading" @click="load">
        {{ loading ? t('common.loading') : t('common.refresh') }}
      </button>
    </div>

    <template v-if="tab === 'governance'">
      <form v-if="canOperate" class="connection-form" @submit.prevent="saveTemplate">
        <h3>{{ t('data.createTemplate') }}</h3>
        <div class="form-grid">
          <label>{{ t('data.templateKey') }}<input v-model="templateForm.templateKey" required maxlength="100"></label>
          <label>{{ t('data.templateName') }}<input v-model="templateForm.name" required maxlength="200"></label>
        </div>
        <label>{{ t('data.templateDescription') }}<input v-model="templateForm.description" required maxlength="1000"></label>
        <label>{{ t('data.templateSql') }}<textarea v-model="templateForm.sql" required maxlength="10000" rows="5"></textarea></label>
        <p class="field-hint">{{ t('data.templateSaveHint') }}</p>
        <button class="button button--primary" type="submit" :disabled="loading">{{ t('common.save') }}</button>
      </form>

      <section class="workflow-card">
        <div class="section-heading"><div><h3>{{ t('data.queryTemplates') }}</h3><p>{{ t('data.queryTemplatesDescription') }}</p></div></div>
        <div v-if="templates.length" class="record-grid">
          <article v-for="template in templates" :key="template.id">
            <div class="section-heading"><h4>{{ template.name }}</h4><StatusBadge :label="template.active ? t('data.approved') : t('data.pendingApproval')" :tone="template.active ? 'success' : 'warning'" /></div>
            <p>{{ template.description }}</p>
            <small>{{ template.templateKey }} · v{{ template.version }}</small>
            <pre class="code-block">{{ template.sql }}</pre>
            <div class="button-row">
              <button v-if="isAdmin && !template.active && template.ownerActorId !== session?.username" class="button button--secondary" type="button" :disabled="loading" @click="approveTemplate(template)">{{ t('data.approveTemplate') }}</button>
              <button v-if="canOperate && template.active" class="button button--primary" type="button" :disabled="loading" @click="requestTemplateLaunch(template)">{{ t('data.launchTemplate') }}</button>
              <button v-if="isAdmin && template.active" class="button button--danger" type="button" :disabled="loading" @click="govern(`/api/data-copilot/query-templates/${template.id}/deactivate`, 'template', template.id, t('data.templateDeactivated'))">{{ t('data.deactivate') }}</button>
            </div>
          </article>
        </div>
        <p v-else class="empty-state">{{ t('common.noData') }}</p>
      </section>

      <section class="workflow-card">
        <h3>{{ t('data.metricDictionary') }}</h3>
        <form v-if="canOperate" class="connection-form" @submit.prevent="saveMetric">
          <div class="form-grid"><label>{{ t('data.metricKey') }}<input v-model="metricForm.metricKey" required maxlength="100"></label><label>{{ t('data.metricName') }}<input v-model="metricForm.displayName" required maxlength="200"></label><label>{{ t('data.metricUnit') }}<input v-model="metricForm.unit" maxlength="50"></label></div>
          <label>{{ t('data.metricDescription') }}<input v-model="metricForm.description" required maxlength="1000"></label>
          <label>{{ t('data.metricSql') }}<textarea v-model="metricForm.expressionSql" required maxlength="10000" rows="4"></textarea></label>
          <button class="button button--primary" type="submit" :disabled="loading">{{ t('data.createMetric') }}</button>
        </form>
        <div v-if="metrics.length" class="record-grid">
          <article v-for="metric in metrics" :key="metric.id"><div class="section-heading"><h4>{{ metric.displayName }}</h4><StatusBadge :label="metric.active ? t('data.approved') : t('data.pendingApproval')" :tone="metric.active ? 'success' : 'warning'" /></div><p>{{ metric.description }}</p><small>{{ metric.metricKey }} · {{ metric.unit || t('common.unknown') }} · v{{ metric.version }}</small><pre class="code-block">{{ metric.expressionSql }}</pre><div class="button-row"><button v-if="isAdmin && !metric.active && metric.ownerActorId !== session?.username" class="button button--secondary" type="button" :disabled="loading" @click="govern(`/api/data-copilot/metrics/${metric.id}/approve`, 'metric', metric.id, t('data.metricApproved'))">{{ t('data.approveMetric') }}</button><button v-if="canOperate && metric.active" class="button button--primary" type="button" :disabled="loading" @click="requestMetricLaunch(metric)">{{ t('data.launchMetric') }}</button><button v-if="isAdmin && metric.active" class="button button--danger" type="button" :disabled="loading" @click="govern(`/api/data-copilot/metrics/${metric.id}/deactivate`, 'metric', metric.id, t('data.metricDeactivated'))">{{ t('data.deactivate') }}</button></div></article>
        </div>
        <p v-else class="empty-state">{{ t('common.noData') }}</p>
      </section>
    </template>

    <template v-else-if="tab === 'records'">
      <section class="workflow-card">
        <h3>{{ t('data.resultSnapshots') }}</h3>
        <div v-if="results.length" class="table-scroll">
          <table class="data-table"><thead><tr><th>{{ t('data.resultId') }}</th><th>{{ t('data.candidateId') }}</th><th>{{ t('data.rows') }}</th><th>{{ t('data.createdAt') }}</th><th>{{ t('data.expiresAt') }}</th><th>{{ t('common.download') }}</th></tr></thead>
            <tbody><tr v-for="item in results" :key="item.id"><td><strong>#{{ item.id }}</strong></td><td><code>{{ item.candidateId }}</code></td><td>{{ item.rowCount }}<small v-if="item.truncated">{{ t('data.truncated') }}</small></td><td>{{ item.createdAt }}</td><td>{{ item.expiresAt }}</td><td><div class="table-actions"><a class="button button--secondary" :href="`/api/data-copilot/query-results/${item.id}/csv`">CSV</a><a class="button button--secondary" :href="`/api/data-copilot/query-results/${item.id}/xlsx`">XLSX</a></div></td></tr></tbody>
          </table>
        </div>
        <p v-else class="empty-state">{{ t('data.noResults') }}</p>
      </section>
      <section v-if="canReview" class="workflow-card">
        <h3>{{ t('data.auditRecords') }}</h3>
        <div v-if="auditLogs.length" class="table-scroll">
          <table class="data-table"><thead><tr><th>{{ t('data.createdAt') }}</th><th>{{ t('data.status') }}</th><th>{{ t('data.confirmed') }}</th><th>{{ t('data.rows') }}</th><th>{{ t('data.actor') }}</th><th>{{ t('data.question') }}</th></tr></thead>
            <tbody><tr v-for="item in auditLogs" :key="item.id"><td>{{ item.createdAt }}</td><td>{{ item.executionStatus || item.validationStatus }}</td><td>{{ item.confirmed ? t('common.yes') : t('common.no') }}</td><td>{{ item.rowCount ?? '—' }}</td><td>{{ item.actorId || item.actionActorId || '—' }}</td><td>{{ item.userQuestion || t('data.confirmedQuery') }}</td></tr></tbody>
          </table>
        </div>
        <p v-else class="empty-state">{{ t('data.noAuditRecords') }}</p>
      </section>
    </template>

    <template v-else>
      <section class="workflow-card">
        <div class="section-heading"><div><h3>{{ t('data.readyHandoffs') }}</h3><p>{{ t('data.handoffDescription') }}</p></div></div>
        <div v-if="handoffs.length" class="record-grid">
          <article v-for="handoff in handoffs" :key="handoff.id">
            <div class="section-heading"><h4>{{ handoff.title }}</h4><StatusBadge :label="handoff.status" :tone="handoff.status === 'READY' ? 'success' : 'warning'" /></div>
            <p>{{ t('data.sourceReference') }}: <code>{{ handoff.sourceReference }}</code></p>
            <small>{{ t('data.resultId') }} #{{ handoff.resultId }} · {{ handoff.rowCount }} {{ t('data.rows') }} · {{ handoff.createdAt }}</small>
          </article>
        </div>
        <p v-else class="empty-state">{{ t('data.noHandoffs') }}</p>
      </section>
      <section class="workflow-card">
        <h3>{{ t('data.createHandoffFromResult') }}</h3>
        <div v-if="results.length" class="table-scroll">
          <table class="data-table"><thead><tr><th>{{ t('data.resultId') }}</th><th>{{ t('data.rows') }}</th><th>{{ t('data.createdAt') }}</th><th>{{ t('common.controlledAction') }}</th></tr></thead>
            <tbody><tr v-for="item in results" :key="item.id"><td>#{{ item.id }}</td><td>{{ item.rowCount }}</td><td>{{ item.createdAt }}</td><td><button class="button button--secondary" type="button" :disabled="loading" @click="selectResultForHandoff(item.id)">{{ t('data.selectResult') }}</button></td></tr></tbody>
          </table>
        </div>
        <p v-else class="empty-state">{{ t('data.noResultsForHandoff') }}</p>
        <div v-if="selectedResultId !== null" class="form-actions"><label>{{ t('data.handoffTitle') }}<input v-model="handoffTitle" required maxlength="300"></label><button class="button button--primary" type="button" :disabled="loading || !handoffTitle.trim()" @click="requestHandoff">{{ t('data.createHandoff') }}</button></div>
      </section>
    </template>

    <section v-if="queryCandidate" class="data-query-flow">
      <div class="data-query-flow__heading"><div><p class="panel-kicker">{{ t('data.templateCandidate') }}</p><h3>{{ t('data.templateReady') }}</h3></div><StatusBadge :label="t('data.awaitingConfirmation')" tone="warning" /></div>
      <p>{{ t('common.reviewBeforeConfirm') }}</p>
      <div class="button-row"><button class="button button--primary" type="button" :disabled="loading" @click="pendingAction = 'query-execute'">{{ t('data.confirmTemplateExecution') }}</button><button class="button button--secondary" type="button" @click="queryCandidate = null">{{ t('common.cancel') }}</button></div>
    </section>
    <DataExecutionResult v-if="queryExecution" :execution="queryExecution" />
    <RequestId :value="requestId" />
  </section>

  <ConfirmDialog
    :open="pendingAction !== null"
    :operation="pendingAction === 'query-launch' ? t(pendingQuery?.kind === 'metric' ? 'data.launchMetric' : 'data.launchTemplate') : pendingAction === 'query-execute' ? t('data.confirmTemplateExecution') : t('data.createHandoff')"
    :target="pendingAction === 'query-launch' ? String(pendingQuery?.item?.name ?? pendingQuery?.item?.displayName ?? '') : pendingAction === 'query-execute' ? String(queryCandidate?.candidateId ?? '') : `query-result-${selectedResultId ?? ''}`"
    current-state="READY"
    target-state="REQUESTED"
    :impact="t('common.reviewBeforeConfirm')"
    :recoverable="false"
    :risk="t('data.handoffRisk')"
    :busy="loading"
    @confirm="runPendingAction"
    @cancel="pendingAction = null"
  />
  <ToastMessage :message="toast" :tone="toastTone" />
</template>
