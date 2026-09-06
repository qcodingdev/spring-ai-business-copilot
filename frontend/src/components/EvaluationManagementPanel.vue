<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { api, ApiError, jsonBody } from '@/api/client'
import RequestId from './RequestId.vue'
import StatusBadge from './StatusBadge.vue'
import ToastMessage from './ToastMessage.vue'

type Row = Record<string, any>
const { t, te, locale } = useI18n()
const loading = ref(false)
const datasets = ref<Row[]>([])
const policies = ref<Row[]>([])
const runs = ref<Row[]>([])
const selectedDatasetId = ref<number | null>(null)
const selectedVersionId = ref<number | null>(null)
const selectedRunId = ref('')
const requestId = ref<string | null>(null)
const toast = ref('')
const toastTone = ref<'success' | 'danger' | 'info'>('info')
let toastTimer: ReturnType<typeof setTimeout> | undefined
let runTimer: ReturnType<typeof setTimeout> | undefined
let disposed = false

const datasetForm = ref({ datasetKey: '', moduleKey: 'DATA', nameZh: '', nameEn: '', descriptionZh: '', descriptionEn: '' })
const versionNote = ref('')
const reviewNote = ref('')
const importJson = ref('')
const externalJson = ref('')
const caseEditingId = ref<number | null>(null)
const caseForm = ref({
  caseKey: '', titleZh: '', titleEn: '', executionType: 'PROMPT', promptKey: '',
  variablesJson: '{}', expectedJson: '{}', forbiddenJson: '[]', critical: false,
  enabled: true, maxLatencyMs: 120000, maxModelCalls: 1,
})
const runForm = ref({ environment: 'LOCAL', promptVersionId: '' })

const selectedDataset = computed(() => datasets.value.find((item) => Number(item.id) === selectedDatasetId.value) ?? null)
const selectedVersion = computed(() => selectedDataset.value?.versions?.find((item: Row) => Number(item.id) === selectedVersionId.value) ?? null)
const selectedRun = computed(() => runs.value.find((item) => String(item.id) === selectedRunId.value) ?? null)
const modules = ['DATA', 'KNOWLEDGE', 'SUPPORT', 'REPORT', 'HR', 'CROSS_MODULE']
const environments = ['LOCAL', 'MODEL', 'VENDOR', 'PRE_PRODUCTION']

function showToast(message: string, tone: 'success' | 'danger' | 'info' = 'success'): void {
  if (toastTimer) clearTimeout(toastTimer)
  toast.value = message; toastTone.value = tone
  toastTimer = setTimeout(() => { toast.value = '' }, 5000)
}

function errorText(error: unknown): string {
  requestId.value = error instanceof ApiError ? error.requestId : requestId.value
  const code = error instanceof ApiError ? error.errorCode : 'generic'
  return t(`errors.${code}`)
}

function title(item: Row): string {
  return locale.value.startsWith('zh') ? String(item.nameZh ?? item.titleZh ?? item.datasetKey ?? item.caseKey)
    : String(item.nameEn ?? item.titleEn ?? item.datasetKey ?? item.caseKey)
}

function statusTone(status: string): 'success' | 'warning' | 'danger' | 'info' {
  if (['PASSED', 'PUBLISHED', 'ALLOW_RELEASE', 'REVIEWED'].includes(status)) return 'success'
  if (['FAILED', 'BLOCK_RELEASE', 'CANCELED'].includes(status)) return 'danger'
  if (['RUNNING', 'PENDING', 'QUEUED', 'IN_REVIEW', 'NOT_VERIFIED'].includes(status)) return 'warning'
  return 'info'
}

function statusLabel(status: unknown): string {
  const value = String(status ?? '')
  return value && te(`statuses.${value}`) ? t(`statuses.${value}`) : (value || t('common.unknown'))
}

async function load(): Promise<void> {
  if (loading.value) return
  loading.value = true
  try {
    const [datasetResponse, gateResponse, runResponse] = await Promise.all([
      api<Row[]>('/api/governance/evaluations/datasets'),
      api<Row[]>('/api/governance/evaluations/gate-policies'),
      api<Row[]>('/api/governance/evaluations/runs'),
    ])
    datasets.value = datasetResponse.data ?? []
    policies.value = gateResponse.data ?? []
    runs.value = runResponse.data ?? []
    requestId.value = runResponse.requestId ?? gateResponse.requestId ?? datasetResponse.requestId
    if (!selectedDatasetId.value && datasets.value[0]) selectedDatasetId.value = Number(datasets.value[0].id)
    if (selectedDataset.value && !selectedVersion.value) selectedVersionId.value = Number(selectedDataset.value.versions?.[0]?.id ?? 0) || null
    if (!selectedRunId.value && runs.value[0]) selectedRunId.value = String(runs.value[0].id)
  } catch (error) { showToast(errorText(error), 'danger') } finally { loading.value = false }
}

async function mutate(path: string, body: unknown = undefined, method: 'POST' | 'PUT' = 'POST'): Promise<any | null> {
  if (loading.value) return null
  loading.value = true
  try {
    const response = await api<any>(path, { method, ...(body === undefined ? {} : jsonBody(body)) })
    requestId.value = response.requestId
    showToast(t('admin.evaluationSaved'))
    return response.data
  } catch (error) { showToast(errorText(error), 'danger'); return null } finally { loading.value = false }
}

async function createDataset(): Promise<void> {
  const created = await mutate('/api/governance/evaluations/datasets', datasetForm.value)
  if (!created) return
  datasetForm.value = { datasetKey: '', moduleKey: 'DATA', nameZh: '', nameEn: '', descriptionZh: '', descriptionEn: '' }
  await load(); selectedDatasetId.value = Number(created.id); selectedVersionId.value = Number(created.versions?.[0]?.id ?? 0) || null
}

function chooseDataset(id: number): void {
  selectedDatasetId.value = id
  selectedVersionId.value = Number(selectedDataset.value?.versions?.[0]?.id ?? 0) || null
  resetCase()
}

async function archiveDataset(): Promise<void> {
  if (!selectedDatasetId.value || selectedDataset.value?.status !== 'ACTIVE') return
  if (await mutate(`/api/governance/evaluations/datasets/${selectedDatasetId.value}/archive`)) await load()
}

async function cloneVersion(): Promise<void> {
  if (!selectedVersionId.value || !versionNote.value.trim()) return
  const created = await mutate(`/api/governance/evaluations/versions/${selectedVersionId.value}/clone`, { note: versionNote.value.trim() })
  if (!created) return
  versionNote.value = ''; await load(); selectedVersionId.value = Number(created.id)
}

function resetCase(): void {
  caseEditingId.value = null
  caseForm.value = { caseKey: '', titleZh: '', titleEn: '', executionType: 'PROMPT', promptKey: '', variablesJson: '{}', expectedJson: '{}', forbiddenJson: '[]', critical: false, enabled: true, maxLatencyMs: 120000, maxModelCalls: 1 }
}

function editCase(item: Row): void {
  caseEditingId.value = Number(item.id)
  caseForm.value = {
    caseKey: item.caseKey, titleZh: item.titleZh, titleEn: item.titleEn,
    executionType: item.executionType, promptKey: item.promptKey ?? '',
    variablesJson: JSON.stringify(item.variables ?? {}, null, 2),
    expectedJson: JSON.stringify(item.expected ?? {}, null, 2),
    forbiddenJson: JSON.stringify(item.forbidden ?? [], null, 2),
    critical: item.critical === true, enabled: item.enabled !== false,
    maxLatencyMs: Number(item.maxLatencyMs ?? 120000), maxModelCalls: Number(item.maxModelCalls ?? 1),
  }
}

function casePayload(): Row | null {
  try {
    return {
      caseKey: caseForm.value.caseKey, titleZh: caseForm.value.titleZh, titleEn: caseForm.value.titleEn,
      executionType: caseForm.value.executionType,
      promptKey: caseForm.value.executionType === 'PROMPT' ? caseForm.value.promptKey : null,
      variables: JSON.parse(caseForm.value.variablesJson), expected: JSON.parse(caseForm.value.expectedJson),
      forbidden: JSON.parse(caseForm.value.forbiddenJson), critical: caseForm.value.critical,
      enabled: caseForm.value.enabled, maxLatencyMs: caseForm.value.maxLatencyMs,
      maxModelCalls: caseForm.value.maxModelCalls,
    }
  } catch { showToast(t('admin.invalidJson'), 'danger'); return null }
}

async function saveCase(): Promise<void> {
  if (!selectedVersionId.value) return
  const payload = casePayload(); if (!payload) return
  const path = caseEditingId.value
    ? `/api/governance/evaluations/versions/${selectedVersionId.value}/cases/${caseEditingId.value}`
    : `/api/governance/evaluations/versions/${selectedVersionId.value}/cases`
  if (await mutate(path, payload, caseEditingId.value ? 'PUT' : 'POST')) { resetCase(); await load() }
}

async function importCases(): Promise<void> {
  if (!selectedVersionId.value) return
  try {
    const value = JSON.parse(importJson.value)
    if (!Array.isArray(value)) throw new Error('array required')
    if (await mutate(`/api/governance/evaluations/versions/${selectedVersionId.value}/cases/import`, value)) {
      importJson.value = ''; await load()
    }
  } catch { showToast(t('admin.invalidJsonArray'), 'danger') }
}

async function setEnabled(item: Row): Promise<void> {
  if (!selectedVersionId.value) return
  if (await mutate(`/api/governance/evaluations/versions/${selectedVersionId.value}/cases/${item.id}/enabled`, { enabled: !item.enabled })) await load()
}

async function versionAction(action: 'submit' | 'publish'): Promise<void> {
  if (!selectedVersionId.value) return
  if (await mutate(`/api/governance/evaluations/versions/${selectedVersionId.value}/${action}`)) await load()
}

async function reviewVersion(approve: boolean): Promise<void> {
  if (!selectedVersionId.value || !reviewNote.value.trim()) return
  if (await mutate(`/api/governance/evaluations/versions/${selectedVersionId.value}/review`, { approve, note: reviewNote.value.trim() })) {
    reviewNote.value = ''; await load()
  }
}

async function startRun(): Promise<void> {
  if (!selectedVersionId.value) return
  const started = await mutate('/api/governance/evaluations/runs', {
    versionId: selectedVersionId.value,
    promptVersionId: runForm.value.promptVersionId ? Number(runForm.value.promptVersionId) : null,
    environment: runForm.value.environment,
    idempotencyKey: `ui-${selectedVersionId.value}-${runForm.value.environment}-${Date.now()}`,
  })
  if (!started) return
  selectedRunId.value = String(started.id); await load(); scheduleRunPoll()
}

function scheduleRunPoll(): void {
  if (runTimer) clearTimeout(runTimer)
  if (!disposed && selectedRun.value && ['QUEUED', 'RUNNING'].includes(String(selectedRun.value.status))) {
    const runId = selectedRunId.value
    runTimer = setTimeout(async () => {
      try {
        const response = await api<Row>(`/api/governance/evaluations/runs/${encodeURIComponent(runId)}`)
        if (disposed || selectedRunId.value !== runId) return
        runs.value = runs.value.map((run) => String(run.id) === runId ? response.data : run)
        requestId.value = response.requestId
      } catch (error) {
        if (!disposed) showToast(errorText(error), 'danger')
      }
      if (!disposed) scheduleRunPoll()
    }, 1200)
  }
}

async function cancelRun(): Promise<void> {
  if (!selectedRunId.value || !selectedRun.value || !['QUEUED', 'RUNNING'].includes(String(selectedRun.value.status))) return
  if (await mutate(`/api/governance/evaluations/runs/${selectedRunId.value}/cancel`)) await load()
}

async function submitExternalResults(): Promise<void> {
  if (!selectedRunId.value) return
  try {
    const value = JSON.parse(externalJson.value)
    if (!Array.isArray(value)) throw new Error('array required')
    if (await mutate(`/api/governance/evaluations/runs/${selectedRunId.value}/external-results`, value)) {
      externalJson.value = ''; await load()
    }
  } catch { showToast(t('admin.invalidJsonArray'), 'danger') }
}

async function savePolicy(item: Row): Promise<void> {
  if (await mutate(`/api/governance/evaluations/gate-policies/${item.moduleKey}`, {
    minimumPassRate: Number(item.minimumPassRate), requireCriticalPass: item.requireCriticalPass === true,
    maximumAverageLatency: item.maximumAverageLatency ? Number(item.maximumAverageLatency) : null,
    maximumTotalTokens: item.maximumTotalTokens ? Number(item.maximumTotalTokens) : null,
  }, 'PUT')) await load()
}

onMounted(async () => { await load(); if (!disposed) scheduleRunPoll() })
onUnmounted(() => { disposed = true; if (toastTimer) clearTimeout(toastTimer); if (runTimer) clearTimeout(runTimer) })
</script>

<template>
  <div class="admin-content evaluation-management">
    <section class="panel admin-section">
      <div class="section-heading"><div><h2>{{ t('admin.evaluationManagement') }}</h2><p>{{ t('admin.evaluationManagementDescription') }}</p></div><button class="button button--secondary" type="button" :disabled="loading" @click="load">{{ t('common.refresh') }}</button></div>
      <p class="alert alert--info">{{ t('admin.evaluationBoundary') }}</p>
      <form class="form-grid" @submit.prevent="createDataset">
        <label>{{ t('admin.datasetKey') }}<input v-model="datasetForm.datasetKey" required pattern="[a-z0-9][a-z0-9._-]+" maxlength="100"></label>
        <label>{{ t('admin.module') }}<select v-model="datasetForm.moduleKey"><option v-for="item in modules" :key="item" :value="item">{{ item }}</option></select></label>
        <label>{{ t('admin.nameZh') }}<input v-model="datasetForm.nameZh" required maxlength="200"></label>
        <label>{{ t('admin.nameEn') }}<input v-model="datasetForm.nameEn" required maxlength="200"></label>
        <label>{{ t('admin.descriptionZh') }}<textarea v-model="datasetForm.descriptionZh" rows="2" maxlength="1000"></textarea></label>
        <label>{{ t('admin.descriptionEn') }}<textarea v-model="datasetForm.descriptionEn" rows="2" maxlength="1000"></textarea></label>
        <button class="button button--primary" type="submit" :disabled="loading">{{ t('admin.createDataset') }}</button>
      </form>
    </section>

    <section class="panel admin-section">
      <div class="evaluation-selector">
        <label>{{ t('admin.dataset') }}<select :value="selectedDatasetId ?? ''" @change="chooseDataset(Number(($event.target as HTMLSelectElement).value))"><option v-for="item in datasets" :key="item.id" :value="item.id">{{ title(item) }} · {{ item.moduleKey }}</option></select></label>
        <button v-if="selectedDataset?.status === 'ACTIVE'" class="button button--danger" type="button" :disabled="loading" @click="archiveDataset">{{ t('admin.archiveDataset') }}</button>
        <label>{{ t('admin.datasetVersion') }}<select v-model.number="selectedVersionId"><option v-for="item in selectedDataset?.versions ?? []" :key="item.id" :value="item.id">v{{ item.versionNumber }} · {{ statusLabel(item.status) }}</option></select></label>
      </div>
      <div v-if="selectedVersion" class="workflow-card">
        <div class="section-heading"><div><h3>{{ title(selectedDataset ?? {}) }} · v{{ selectedVersion.versionNumber }}</h3><p>{{ selectedVersion.changeNote }}</p></div><StatusBadge :label="statusLabel(selectedVersion.status)" :tone="statusTone(selectedVersion.status)" /></div>
        <div class="button-row">
          <input v-model="versionNote" :placeholder="t('admin.cloneNote')" maxlength="1000">
          <button class="button button--secondary" type="button" :disabled="loading || !versionNote.trim()" @click="cloneVersion">{{ t('admin.cloneVersion') }}</button>
          <button v-if="selectedVersion.status === 'DRAFT'" class="button button--primary" type="button" :disabled="loading || !selectedVersion.cases?.some((item: Row) => item.enabled)" @click="versionAction('submit')">{{ t('admin.submitReview') }}</button>
          <button v-if="selectedVersion.status === 'REVIEWED'" class="button button--primary" type="button" :disabled="loading" @click="versionAction('publish')">{{ t('admin.publishVersion') }}</button>
          <a class="button button--secondary" :href="`/api/governance/evaluations/versions/${selectedVersion.id}/export`">CSV</a>
          <a class="button button--secondary" :href="`/api/governance/evaluations/versions/${selectedVersion.id}/export.json`">JSON</a>
        </div>
        <div v-if="selectedVersion.status === 'IN_REVIEW'" class="primary-workflow-form"><label>{{ t('admin.reviewNote') }}<textarea v-model="reviewNote" required rows="2" maxlength="1000"></textarea></label><div class="button-row"><button class="button button--primary" type="button" :disabled="loading || !reviewNote.trim()" @click="reviewVersion(true)">{{ t('common.approve') }}</button><button class="button button--danger" type="button" :disabled="loading || !reviewNote.trim()" @click="reviewVersion(false)">{{ t('common.reject') }}</button></div></div>
      </div>
    </section>

    <section v-if="selectedVersion" class="panel admin-section">
      <h2>{{ t('admin.testCases') }} ({{ selectedVersion.cases?.length ?? 0 }})</h2>
      <form v-if="selectedVersion.status === 'DRAFT'" class="primary-workflow-form" @submit.prevent="saveCase">
        <div class="form-grid"><label>{{ t('admin.caseKey') }}<input v-model="caseForm.caseKey" required maxlength="100"></label><label>{{ t('admin.executionType') }}<select v-model="caseForm.executionType"><option value="PROMPT">PROMPT</option><option value="EXTERNAL">EXTERNAL / HARNESS</option></select></label><label>{{ t('admin.titleZh') }}<input v-model="caseForm.titleZh" required maxlength="200"></label><label>{{ t('admin.titleEn') }}<input v-model="caseForm.titleEn" required maxlength="200"></label><label v-if="caseForm.executionType === 'PROMPT'">Prompt key<input v-model="caseForm.promptKey" required maxlength="300"></label><label>{{ t('admin.maxLatency') }}<input v-model.number="caseForm.maxLatencyMs" type="number" min="1"></label><label>{{ t('admin.maxModelCalls') }}<input v-model.number="caseForm.maxModelCalls" type="number" min="1"></label><label class="checkbox-label"><input v-model="caseForm.critical" type="checkbox"> {{ t('admin.criticalCase') }}</label><label class="checkbox-label"><input v-model="caseForm.enabled" type="checkbox"> {{ t('common.enabled') }}</label></div>
        <label>{{ t('admin.variablesJson') }}<textarea v-model="caseForm.variablesJson" rows="5" spellcheck="false"></textarea></label><label>{{ t('admin.expectedJson') }}<textarea v-model="caseForm.expectedJson" rows="5" spellcheck="false"></textarea></label><label>{{ t('admin.forbiddenJson') }}<textarea v-model="caseForm.forbiddenJson" rows="3" spellcheck="false"></textarea></label>
        <div class="button-row"><button class="button button--primary" type="submit" :disabled="loading">{{ caseEditingId ? t('common.save') : t('admin.addCase') }}</button><button v-if="caseEditingId" class="button button--secondary" type="button" :disabled="loading" @click="resetCase">{{ t('common.cancel') }}</button></div>
      </form>
      <div class="table-scroll"><table class="data-table"><thead><tr><th>{{ t('admin.testCase') }}</th><th>{{ t('admin.executionType') }}</th><th>{{ t('common.status') }}</th><th>{{ t('admin.limits') }}</th><th>{{ t('common.actions') }}</th></tr></thead><tbody><tr v-for="item in selectedVersion.cases ?? []" :key="item.id"><td><strong>{{ title(item) }}</strong><small>{{ item.caseKey }}</small></td><td>{{ item.executionType }}<small>{{ item.promptKey }}</small></td><td><StatusBadge :label="item.enabled ? (item.critical ? t('admin.criticalEnabled') : t('statuses.ACTIVE')) : t('statuses.DISABLED')" :tone="item.enabled ? 'success' : 'info'" /></td><td>{{ item.maxLatencyMs ?? '—' }} ms · {{ item.maxModelCalls ?? '—' }} calls</td><td><div v-if="selectedVersion.status === 'DRAFT'" class="button-row"><button class="button button--secondary" type="button" :disabled="loading" @click="editCase(item)">{{ t('common.edit') }}</button><button class="button button--secondary" type="button" :disabled="loading" @click="setEnabled(item)">{{ item.enabled ? t('common.disable') : t('common.enable') }}</button></div></td></tr></tbody></table></div>
      <details v-if="selectedVersion.status === 'DRAFT'"><summary>{{ t('admin.importCases') }}</summary><label>{{ t('admin.importCasesHint') }}<textarea v-model="importJson" rows="8" spellcheck="false"></textarea></label><button class="button button--secondary" type="button" :disabled="loading || !importJson.trim()" @click="importCases">{{ t('admin.importCases') }}</button></details>
    </section>

    <section class="panel admin-section">
      <h2>{{ t('admin.evaluationRuns') }}</h2>
      <div class="form-grid"><label>{{ t('admin.environment') }}<select v-model="runForm.environment"><option v-for="item in environments" :key="item" :value="item">{{ item }}</option></select></label><label>{{ t('admin.promptVersionOptional') }}<input v-model="runForm.promptVersionId" inputmode="numeric" pattern="[0-9]*"></label><button class="button button--primary" type="button" :disabled="loading || selectedDataset?.status !== 'ACTIVE' || selectedVersion?.status !== 'PUBLISHED'" @click="startRun">{{ t('admin.startEvaluation') }}</button></div>
      <p v-if="selectedVersion && selectedVersion.status !== 'PUBLISHED'" class="field-hint">{{ t('admin.evaluationRequiresPublishedVersion') }}</p>
      <div class="evaluation-selector"><label>{{ t('admin.evaluationRun') }}<select v-model="selectedRunId" @change="scheduleRunPoll"><option v-for="item in runs" :key="item.id" :value="item.id">{{ item.id }} · {{ statusLabel(item.status) }} · {{ item.environment }}</option></select></label></div>
      <div v-if="selectedRun" class="workflow-card"><div class="section-heading"><div><h3>{{ selectedRun.id }}</h3><p>{{ selectedRun.passedCases }}/{{ selectedRun.totalCases }} · {{ Math.round(Number(selectedRun.passRate ?? 0)) }}% · {{ selectedRun.averageLatencyMs ?? '—' }} ms</p></div><div class="button-row"><StatusBadge :label="statusLabel(selectedRun.status)" :tone="statusTone(selectedRun.status)" /><StatusBadge :label="statusLabel(selectedRun.gateDecision)" :tone="statusTone(selectedRun.gateDecision)" /></div></div><div class="button-row"><a class="button button--secondary" :href="`/api/governance/evaluations/runs/${selectedRun.id}/report`">{{ t('admin.exportEvaluationReport') }}</a><button v-if="['QUEUED', 'RUNNING'].includes(String(selectedRun.status))" class="button button--danger" type="button" :disabled="loading" @click="cancelRun">{{ t('admin.cancelEvaluation') }}</button></div><div class="table-scroll"><table class="data-table"><thead><tr><th>{{ t('admin.testCase') }}</th><th>{{ t('common.status') }}</th><th>{{ t('admin.latencyTokens') }}</th><th>{{ t('admin.failureReason') }}</th></tr></thead><tbody><tr v-for="item in selectedRun.results ?? []" :key="item.id"><td><strong>{{ title(item) }}</strong><small>{{ item.caseKey }}</small></td><td><StatusBadge :label="statusLabel(item.status)" :tone="statusTone(item.status)" /></td><td>{{ item.latencyMs ?? '—' }} ms · {{ item.inputTokens == null || item.outputTokens == null ? t('common.unknown') : item.inputTokens + item.outputTokens }}</td><td>{{ item.failureReason || item.outputSummary || '—' }}</td></tr></tbody></table></div><details v-if="selectedRun.notVerifiedCases"><summary>{{ t('admin.submitHarnessResults') }}</summary><label>{{ t('admin.externalResultsHint') }}<textarea v-model="externalJson" rows="8" spellcheck="false"></textarea></label><button class="button button--secondary" type="button" :disabled="loading || !externalJson.trim()" @click="submitExternalResults">{{ t('admin.submitHarnessResults') }}</button></details></div>
    </section>

    <section class="panel admin-section"><h2>{{ t('admin.releaseGates') }}</h2><p>{{ t('admin.releaseGatesDescription') }}</p><div class="record-grid"><article v-for="item in policies" :key="item.moduleKey"><h3>{{ item.moduleKey }}</h3><label>{{ t('admin.minimumPassRate') }}<input v-model.number="item.minimumPassRate" type="number" min="0" max="100" step="1"></label><label>{{ t('admin.maximumAverageLatency') }}<input v-model.number="item.maximumAverageLatency" type="number" min="1"></label><label>{{ t('admin.maximumTotalTokens') }}<input v-model.number="item.maximumTotalTokens" type="number" min="1"></label><label class="checkbox-label"><input v-model="item.requireCriticalPass" type="checkbox"> {{ t('admin.requireCriticalPass') }}</label><button class="button button--secondary" type="button" :disabled="loading" @click="savePolicy(item)">{{ t('common.save') }}</button></article></div></section>
    <RequestId :value="requestId" />
  </div>
  <ToastMessage :message="toast" :tone="toastTone" />
</template>
