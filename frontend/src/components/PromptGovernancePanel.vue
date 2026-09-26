<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { api, ApiError, jsonBody } from '@/api/client'
import RequestId from './RequestId.vue'
import StatusBadge from './StatusBadge.vue'
import ToastMessage from './ToastMessage.vue'

type Row = Record<string, any>
const { t, te } = useI18n()
const loading = ref(false)
const definitions = ref<Row[]>([])
const runs = ref<Row[]>([])
const selectedDefinitionId = ref<number | null>(null)
const selectedVersionId = ref<number | null>(null)
const content = ref('')
const changeNote = ref('')
const reviewNote = ref('')
const rolloutPercent = ref(100)
const evaluationRunId = ref('')
const rollbackNote = ref('')
const auditRows = ref<Row[]>([])
const requestId = ref<string | null>(null)
const toast = ref('')
const toastTone = ref<'success' | 'danger' | 'info'>('info')
let toastTimer: ReturnType<typeof setTimeout> | undefined

const selectedDefinition = computed(() => definitions.value.find((item) => Number(item.id) === selectedDefinitionId.value) ?? null)
const selectedVersion = computed(() => selectedDefinition.value?.versions?.find((item: Row) => Number(item.id) === selectedVersionId.value) ?? null)
const activeVersion = computed(() => {
  const definition = selectedDefinition.value
  return definition?.versions?.find((item: Row) => Number(item.id) === Number(definition.activeVersionId)) ?? null
})
const eligibleRuns = computed(() => runs.value.filter((item) => item.status === 'PASSED'
  && item.gateDecision === 'ALLOW_RELEASE' && Number(item.promptVersionId) === selectedVersionId.value))

function showToast(message: string, tone: 'success' | 'danger' | 'info' = 'success'): void {
  if (toastTimer) clearTimeout(toastTimer)
  toast.value = message; toastTone.value = tone
  toastTimer = setTimeout(() => { toast.value = '' }, 5000)
}

function errorText(error: unknown): string {
  requestId.value = error instanceof ApiError ? error.requestId : requestId.value
  return error instanceof ApiError ? t(`errors.${error.errorCode}`) : t('errors.generic')
}

function tone(status: string): 'success' | 'warning' | 'danger' | 'info' {
  if (['PUBLISHED', 'REVIEWED'].includes(status)) return 'success'
  if (['IN_REVIEW'].includes(status)) return 'warning'
  if (['RETIRED'].includes(status)) return 'info'
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
    const [promptResponse, runResponse] = await Promise.all([
      api<Row[]>('/api/governance/prompts'),
      api<Row[]>('/api/governance/evaluations/runs'),
    ])
    definitions.value = promptResponse.data ?? []
    runs.value = runResponse.data ?? []
    requestId.value = promptResponse.requestId ?? runResponse.requestId
    if (!selectedDefinitionId.value && definitions.value[0]) selectDefinition(Number(definitions.value[0].id))
    else syncVersion()
  } catch (error) { showToast(errorText(error), 'danger') } finally { loading.value = false }
}

function selectDefinition(id: number): void {
  selectedDefinitionId.value = id
  const definition = definitions.value.find((item) => Number(item.id) === id)
  selectedVersionId.value = Number(definition?.versions?.[0]?.id ?? 0) || null
  syncVersion()
  void loadAudit()
}

function syncVersion(): void {
  const version = selectedVersion.value
  content.value = String(version?.content ?? activeVersion.value?.content ?? '')
  changeNote.value = String(version?.changeNote ?? '')
  evaluationRunId.value = String(eligibleRuns.value[0]?.id ?? '')
}

async function mutate(path: string, body?: unknown, method: 'POST' | 'PUT' = 'POST'): Promise<any | null> {
  if (loading.value) return null
  loading.value = true
  try {
    const response = await api<any>(path, { method, ...(body === undefined ? {} : jsonBody(body)) })
    requestId.value = response.requestId
    showToast(t('admin.promptSaved'))
    return response.data
  } catch (error) { showToast(errorText(error), 'danger'); return null } finally { loading.value = false }
}

async function createDraft(): Promise<void> {
  if (!selectedDefinitionId.value || !content.value.trim() || !changeNote.value.trim()) return
  const created = await mutate(`/api/governance/prompts/definitions/${selectedDefinitionId.value}/versions`, { content: content.value, changeNote: changeNote.value })
  if (!created) return
  await load(); selectedVersionId.value = Number(created.id); syncVersion()
}

async function saveDraft(): Promise<void> {
  if (!selectedVersion.value || selectedVersion.value.status !== 'DRAFT') return
  const updated = await mutate(`/api/governance/prompts/versions/${selectedVersion.value.id}`, {
    expectedContentHash: selectedVersion.value.contentHash, content: content.value, changeNote: changeNote.value,
  }, 'PUT')
  if (updated) { await load(); selectedVersionId.value = Number(updated.id); syncVersion() }
}

async function versionAction(action: 'submit'): Promise<void> {
  if (!selectedVersionId.value) return
  if (await mutate(`/api/governance/prompts/versions/${selectedVersionId.value}/${action}`)) await load()
}

async function review(approve: boolean): Promise<void> {
  if (!selectedVersionId.value || !reviewNote.value.trim()) return
  if (await mutate(`/api/governance/prompts/versions/${selectedVersionId.value}/review`, { approve, note: reviewNote.value.trim() })) {
    reviewNote.value = ''; await load()
  }
}

async function publish(): Promise<void> {
  if (!selectedVersionId.value || !evaluationRunId.value) return
  if (await mutate(`/api/governance/prompts/versions/${selectedVersionId.value}/publish`, {
    rolloutPercent: rolloutPercent.value, evaluationRunId: evaluationRunId.value,
  })) { await load(); await loadAudit() }
}

async function rollback(): Promise<void> {
  if (!selectedDefinitionId.value || !rollbackNote.value.trim()) return
  if (await mutate(`/api/governance/prompts/${selectedDefinitionId.value}/rollback`, { note: rollbackNote.value.trim() })) {
    rollbackNote.value = ''; await load(); await loadAudit()
  }
}

async function loadAudit(): Promise<void> {
  if (!selectedDefinitionId.value) return
  try {
    const response = await api<Row[]>(`/api/governance/prompts/${selectedDefinitionId.value}/audit`)
    auditRows.value = response.data ?? []; requestId.value = response.requestId
  } catch (error) { showToast(errorText(error), 'danger') }
}

onMounted(load)
onUnmounted(() => { if (toastTimer) clearTimeout(toastTimer) })
</script>

<template>
  <div class="admin-content prompt-management">
    <section class="panel admin-section">
      <div class="section-heading"><div><h2>{{ t('admin.promptGovernance') }}</h2><p>{{ t('admin.promptGovernanceDescription') }}</p></div><button class="button button--secondary" type="button" :disabled="loading" @click="load">{{ t('common.refresh') }}</button></div>
      <p class="alert alert--info">{{ t('admin.promptGovernanceBoundary') }}</p>
      <div class="evaluation-selector"><label>{{ t('admin.promptDefinition') }}<select :value="selectedDefinitionId ?? ''" @change="selectDefinition(Number(($event.target as HTMLSelectElement).value))"><option v-for="item in definitions" :key="item.id" :value="item.id">{{ item.displayName }} · {{ item.moduleKey }}</option></select></label><label>{{ t('admin.promptVersion') }}<select v-model.number="selectedVersionId" @change="syncVersion"><option v-for="item in selectedDefinition?.versions ?? []" :key="item.id" :value="item.id">v{{ item.versionNumber }} · {{ statusLabel(item.status) }}</option></select></label></div>
      <div v-if="selectedDefinition" class="workflow-card"><div class="section-heading"><div><h3>{{ selectedDefinition.promptKey }}</h3><p>{{ selectedDefinition.description }}</p></div><div class="button-row"><StatusBadge :label="statusLabel(selectedVersion?.status)" :tone="tone(selectedVersion?.status ?? '')" /><StatusBadge :label="`${selectedDefinition.rolloutPercent}% rollout`" tone="info" /></div></div><p><strong>{{ t('admin.promptActiveVersion') }}:</strong> {{ activeVersion ? `v${activeVersion.versionNumber}` : '—' }} · <code>{{ activeVersion?.contentHash?.slice(0, 16) }}</code></p></div>
    </section>

    <section v-if="selectedDefinition && selectedVersion" class="panel admin-section">
      <h2>{{ t('admin.promptEditor') }}</h2>
      <label>{{ t('admin.promptContent') }}<textarea v-model="content" rows="18" spellcheck="false" :disabled="selectedVersion.status !== 'DRAFT'"></textarea></label>
      <label>{{ t('admin.changeNote') }}<textarea v-model="changeNote" rows="3" maxlength="1000" :disabled="selectedVersion.status !== 'DRAFT'"></textarea></label>
      <div class="button-row"><button v-if="selectedVersion.status === 'DRAFT'" class="button button--primary" type="button" :disabled="loading || !content.trim() || !changeNote.trim()" @click="saveDraft">{{ t('common.save') }}</button><button v-if="selectedVersion.status === 'DRAFT'" class="button button--secondary" type="button" :disabled="loading || !changeNote.trim()" @click="versionAction('submit')">{{ t('admin.submitReview') }}</button><button v-if="selectedVersion.status !== 'DRAFT'" class="button button--secondary" type="button" :disabled="loading || !content.trim() || !changeNote.trim()" @click="createDraft">{{ t('admin.createPromptDraft') }}</button></div>
      <div v-if="selectedVersion.status === 'IN_REVIEW'" class="primary-workflow-form"><label>{{ t('admin.reviewNote') }}<textarea v-model="reviewNote" required rows="3" maxlength="1000"></textarea></label><div class="button-row"><button class="button button--primary" type="button" :disabled="loading || !reviewNote.trim()" @click="review(true)">{{ t('common.approve') }}</button><button class="button button--danger" type="button" :disabled="loading || !reviewNote.trim()" @click="review(false)">{{ t('common.reject') }}</button></div></div>
    </section>

    <section v-if="selectedVersion?.status === 'REVIEWED'" class="panel admin-section">
      <h2>{{ t('admin.promptRelease') }}</h2><p>{{ t('admin.promptReleaseDescription') }}</p>
      <div class="form-grid"><label>{{ t('admin.rolloutPercent') }}<input v-model.number="rolloutPercent" type="number" min="1" max="100"></label><label>{{ t('admin.passedEvaluationRun') }}<select v-model="evaluationRunId" required><option value="" disabled>{{ t('admin.selectPassedEvaluationRun') }}</option><option v-for="item in eligibleRuns" :key="item.id" :value="item.id">{{ item.id }} · {{ Math.round(Number(item.passRate ?? 0)) }}%</option></select></label><button class="button button--primary" type="button" :disabled="loading || !evaluationRunId" @click="publish">{{ t('admin.publishPrompt') }}</button></div><p v-if="!eligibleRuns.length" class="alert alert--warning">{{ t('admin.noEligiblePromptRun') }}</p>
    </section>

    <section v-if="selectedDefinition?.previousVersionId" class="panel admin-section"><h2>{{ t('admin.promptRollback') }}</h2><p>{{ t('admin.promptRollbackDescription') }}</p><label>{{ t('admin.rollbackReason') }}<textarea v-model="rollbackNote" rows="3" maxlength="1000"></textarea></label><button class="button button--danger" type="button" :disabled="loading || !rollbackNote.trim()" @click="rollback">{{ t('admin.rollbackPrompt') }}</button></section>

    <section class="panel admin-section"><div class="section-heading"><h2>{{ t('admin.promptAudit') }}</h2><button class="button button--secondary" type="button" :disabled="loading" @click="loadAudit">{{ t('common.refresh') }}</button></div><div class="table-scroll"><table class="data-table"><thead><tr><th>{{ t('admin.action') }}</th><th>{{ t('admin.actor') }}</th><th>{{ t('admin.promptVersion') }}</th><th>{{ t('admin.evaluationRun') }}</th><th>{{ t('admin.note') }}</th></tr></thead><tbody><tr v-for="item in auditRows" :key="item.id"><td>{{ item.action }}</td><td>{{ item.actorId }}</td><td>#{{ item.versionId ?? '—' }}</td><td>{{ item.evaluationRunId ?? '—' }}</td><td>{{ item.note || '—' }}</td></tr><tr v-if="!auditRows.length"><td colspan="5" class="empty-state">{{ t('common.noData') }}</td></tr></tbody></table></div></section>
    <RequestId :value="requestId" />
  </div>
  <ToastMessage :message="toast" :tone="toastTone" />
</template>
