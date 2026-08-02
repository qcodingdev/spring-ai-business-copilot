<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { api, ApiError, jsonBody } from '@/api/client'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import EnterprisePanel from '@/components/EnterprisePanel.vue'
import EvidenceList from '@/components/EvidenceList.vue'
import ModuleIcon from '@/components/ModuleIcon.vue'
import PageHeader from '@/components/PageHeader.vue'
import RequestId from '@/components/RequestId.vue'
import SqlPreview from '@/components/SqlPreview.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import ToastMessage from '@/components/ToastMessage.vue'
import { safeJson } from '@/utils/safeDisplay'

type ModuleKey = 'data' | 'knowledge' | 'support' | 'report' | 'hr'
type ConfirmAction = 'data-execute' | 'support-confirm' | 'support-cancel' | 'report-confirm' | 'report-cancel' | 'criteria-confirm' | 'assessment-review' | 'assessment-cancel' | null
const props = defineProps<{ module: ModuleKey }>()
const { t, te } = useI18n()
const route = useRoute()
const router = useRouter()

const input = ref('')
const secondary = ref('')
const activeTab = ref('')
const loading = ref(false)
const result = ref<Record<string, any> | null>(null)
const requestId = ref<string | null>(null)
const confirmAction = ref<ConfirmAction>(null)
const toast = ref('')
const toastTone = ref<'success' | 'danger' | 'info'>('info')
let toastTimer: ReturnType<typeof setTimeout> | undefined

const supportDraftText = ref('')
const supportEditReason = ref('')
const reportContent = ref<Record<string, any> | null>(null)
const jobDraft = ref<Record<string, any> | null>(null)
const criteriaDraft = ref<Record<string, any> | null>(null)
const confirmedJobs = ref<Record<string, any>[]>([])
const selectedJobId = ref('')
const resumeText = ref('')
const resumeFile = ref<File | null>(null)
const assessment = ref<Record<string, any> | null>(null)
const hrData = ref<any>(null)
const interviewAssessmentId = ref('')
const interviewSessionId = ref('')
const interviewEvidence = ref('')
const interviewGaps = ref('')
const interviewOpinion = ref('')
const consentReference = ref('')
const candidateReference = ref('')
const consentPurpose = ref('仅用于本次候选人证据评估')
const consentDays = ref(30)

const config = computed(() => ({
  data: { tabs: ['query', 'governance', 'records', 'handoff'], field: 'question', placeholder: 'questionPlaceholder', action: 'generate' },
  knowledge: { tabs: ['qa', 'sources', 'quality'], field: 'question', placeholder: 'questionPlaceholder', action: 'ask' },
  support: { tabs: ['tickets', 'review', 'connections', 'quality'], field: 'ticket', placeholder: 'ticketPlaceholder', action: 'analyze' },
  report: { tabs: ['generate', 'records', 'sources', 'schedules'], field: 'source', placeholder: 'sourcePlaceholder', action: 'generate' },
  hr: { tabs: ['criteria', 'assessment', 'interview', 'authorization', 'employeeQa', 'onboarding'], field: 'requirements', placeholder: 'requirementsPlaceholder', action: 'draft' },
} as const)[props.module])

const hrSection = computed(() => route.query.section === 'employee' ? 'employee' : 'recruiting')
const visibleTabs = computed(() => props.module === 'hr'
  ? (hrSection.value === 'employee' ? ['employeeQa', 'onboarding'] : ['criteria', 'assessment', 'interview', 'authorization'])
  : [...config.value.tabs])
const primaryTab = computed(() => props.module === 'hr' ? (hrSection.value === 'employee' ? 'employeeQa' : 'criteria') : config.value.tabs[0])
const isPrimaryTab = computed(() => activeTab.value === primaryTab.value)

const evidence = computed(() => {
  if (!isPrimaryTab.value || !result.value) return []
  const value = result.value
  const candidates = [value.evidence, value.citations, value.content?.citations, value.assumptions, value.warnings, value.draft?.citations, value.reasons]
  return candidates.flatMap((item) => Array.isArray(item) ? item : []).filter(Boolean)
})
const sql = computed(() => props.module === 'data' && isPrimaryTab.value ? String(result.value?.sql ?? '') : '')
const executable = computed(() => props.module === 'data' && result.value?.executable === true)
const promptKey = computed(() => props.module === 'hr' && hrSection.value === 'employee' ? 'hr.employeeExamples' : `${props.module}.examples`)
const quickPrompts = computed(() => [t(`${promptKey.value}.first`), t(`${promptKey.value}.second`), t(`${promptKey.value}.third`)])
const formFieldLabel = computed(() => props.module === 'hr' && hrSection.value === 'employee' ? t('hr.employeeQuestion') : t(`${props.module}.${config.value.field}`))
const formPlaceholder = computed(() => props.module === 'hr' && hrSection.value === 'employee' ? t('hr.employeeQuestionPlaceholder') : t(`${props.module}.${config.value.placeholder}`))
const submitLabel = computed(() => props.module === 'hr' && hrSection.value === 'employee' ? t('hr.askEmployee') : t(`${props.module}.${config.value.action}`))
const resultStatus = computed(() => {
  const status = String(result.value?.status ?? assessment.value?.status ?? (result.value?.draft ? 'DRAFTED' : ''))
  return status && te(`statuses.${status}`) ? t(`statuses.${status}`) : t('common.operationSucceeded')
})
const processingVisible = computed(() => (isPrimaryTab.value && Boolean(result.value || jobDraft.value || criteriaDraft.value))
  || (props.module === 'hr' && activeTab.value === 'assessment' && Boolean(assessment.value)))

function showToast(message: string, tone: 'success' | 'danger' | 'info' = 'info'): void {
  if (toastTimer) clearTimeout(toastTimer)
  toast.value = message
  toastTone.value = tone
  toastTimer = setTimeout(() => { toast.value = '' }, 5000)
}

function errorText(error: unknown): string {
  const code = error instanceof ApiError ? error.errorCode : 'generic'
  requestId.value = error instanceof ApiError ? error.requestId : requestId.value
  return t(`errors.${te(`errors.${code}`) ? code : 'generic'}`)
}

function resetTransient(): void {
  result.value = null
  requestId.value = null
  supportDraftText.value = ''
  reportContent.value = null
  assessment.value = null
  hrData.value = null
}

async function selectTab(tab: string): Promise<void> {
  activeTab.value = tab
  resetTransient()
  await router.replace({ query: { ...route.query, tab } })
  if (props.module === 'hr' && tab === 'assessment') await loadConfirmedJobs()
  if (props.module === 'hr' && tab === 'interview') await loadHrData('/api/resume-copilot/enterprise/question-bank')
  if (props.module === 'hr' && tab === 'onboarding') await loadHrData('/api/resume-copilot/enterprise/onboarding-checklists')
}

function usePrompt(prompt: string, index: number): void {
  input.value = prompt
  if (props.module === 'hr' && !secondary.value) secondary.value = t(`hr.exampleTitles.${['first', 'second', 'third'][index]}`)
}

function genericPayload(): { path: string; body: unknown } {
  const today = new Date()
  const end = today.toISOString().slice(0, 10)
  const startDate = new Date(today)
  startDate.setDate(today.getDate() - 7)
  switch (props.module) {
    case 'data': return { path: '/api/data-copilot/sql-candidates', body: { question: input.value } }
    case 'knowledge': return { path: '/api/knowledge-copilot/questions', body: { question: input.value, category: null } }
    case 'support': return { path: '/api/support-copilot/tickets/analyze', body: { customerMessage: input.value, channel: 'workbench' } }
    case 'report': return { path: '/api/report-copilot/reports/generate', body: { reportType: 'TEAM_WEEKLY', period: { periodStart: startDate.toISOString().slice(0, 10), periodEnd: end }, title: secondary.value, metrics: [], tasks: [], meetingNotes: [{ title: secondary.value, content: input.value, recordedAt: new Date().toISOString() }], importedSources: [], templateId: null, templateVersion: null } }
    case 'hr': return hrSection.value === 'employee'
      ? { path: '/api/knowledge-copilot/questions', body: { question: input.value, category: 'EMPLOYEE_SERVICE' } }
      : { path: '/api/resume-copilot/jobs/draft', body: { title: secondary.value, requirements: input.value } }
  }
}

async function submit(): Promise<void> {
  loading.value = true
  resetTransient()
  try {
    const request = genericPayload()
    const response = await api<Record<string, any>>(request.path, { method: 'POST', ...jsonBody(request.body) })
    requestId.value = response.requestId ?? response.data.requestId ?? null
    if (props.module === 'hr' && hrSection.value === 'recruiting') {
      jobDraft.value = response.data
    } else {
      result.value = response.data
    }
    if (props.module === 'support') supportDraftText.value = response.data.draft?.replyText ?? ''
    if (props.module === 'report') reportContent.value = structuredClone(response.data.content ?? {})
  } catch (error) {
    showToast(errorText(error), 'danger')
  } finally {
    loading.value = false
  }
}

async function executeData(): Promise<void> {
  if (!result.value?.candidateId || !result.value?.confirmationToken) return
  await businessAction(`/api/data-copilot/sql-candidates/${encodeURIComponent(result.value.candidateId)}/execute`, { confirmationToken: result.value.confirmationToken }, (data) => {
    result.value = { ...result.value, execution: data, status: 'COMPLETED' }
    showToast(t('data.executionCompleted'), 'success')
  })
}

async function saveSupportEdit(): Promise<void> {
  const draft = result.value?.draft
  if (!draft?.draftId) return
  await businessAction(`/api/support-copilot/reply-drafts/${draft.draftId}/edit`, { editedText: supportDraftText.value, reason: supportEditReason.value }, (data) => {
    result.value = { ...result.value, draft: { ...draft, replyText: data.editedText } }
    showToast(t('support.editSaved'), 'success')
  })
}

async function supportDecision(action: 'confirm' | 'cancel'): Promise<void> {
  const draft = result.value?.draft
  if (!draft?.draftId || !draft.confirmationToken) return
  await businessAction(`/api/support-copilot/reply-drafts/${draft.draftId}/${action}`, { confirmationToken: draft.confirmationToken }, (data) => {
    result.value = { ...result.value, status: data.status, draft: { ...draft, status: data.status } }
    showToast(action === 'confirm' ? t('support.confirmed') : t('support.cancelled'), 'success')
  })
}

async function saveReportEdit(): Promise<void> {
  if (!result.value?.draftId || !result.value?.confirmationToken || !reportContent.value) return
  await businessAction(`/api/report-copilot/reports/${result.value.draftId}/edit`, { confirmationToken: result.value.confirmationToken, content: reportContent.value }, (data) => {
    reportContent.value = data.content
    result.value = { ...result.value, content: data.content, status: data.status }
    showToast(t('report.editSaved'), 'success')
  })
}

async function reportDecision(action: 'confirm' | 'cancel'): Promise<void> {
  if (!result.value?.draftId || !result.value?.confirmationToken) return
  await businessAction(`/api/report-copilot/reports/${result.value.draftId}/${action}`, { confirmationToken: result.value.confirmationToken }, (data) => {
    result.value = { ...result.value, status: data.status }
    showToast(action === 'confirm' ? t('report.confirmed') : t('report.cancelled'), 'success')
  })
}

async function extractCriteria(): Promise<void> {
  if (!jobDraft.value) return
  loading.value = true
  try {
    const response = await api<Record<string, any>>('/api/resume-copilot/jobs/criteria', { method: 'POST', ...jsonBody({ title: secondary.value, jobDescription: jobDraft.value.jdDraft, logicalJobId: null }) })
    criteriaDraft.value = response.data
    requestId.value = response.requestId
    showToast(t('hr.criteriaReady'), 'success')
  } catch (error) { showToast(errorText(error), 'danger') } finally { loading.value = false }
}

async function saveCriteriaEdits(): Promise<void> {
  if (!criteriaDraft.value?.jobId) return
  await businessAction(`/api/resume-copilot/jobs/${criteriaDraft.value.jobId}/criteria`, { criteria: criteriaDraft.value.criteria }, (data) => {
    criteriaDraft.value = data
    showToast(t('hr.criteriaSaved'), 'success')
  }, 'PUT')
}

async function confirmCriteria(): Promise<void> {
  if (!criteriaDraft.value?.jobId || !criteriaDraft.value?.confirmationToken) return
  await businessAction(`/api/resume-copilot/jobs/${criteriaDraft.value.jobId}/criteria/confirm`, { token: criteriaDraft.value.confirmationToken }, (data) => {
    criteriaDraft.value = { ...criteriaDraft.value, status: data.status }
    showToast(t('hr.criteriaConfirmed'), 'success')
  })
}

async function loadConfirmedJobs(): Promise<void> {
  loading.value = true
  try {
    const response = await api<Record<string, any>[]>('/api/resume-copilot/jobs/confirmed')
    confirmedJobs.value = response.data
    requestId.value = response.requestId
    if (!selectedJobId.value && response.data[0]) selectedJobId.value = String(response.data[0].jobId)
  } catch (error) { showToast(errorText(error), 'danger') } finally { loading.value = false }
}

function chooseResume(event: Event): void { resumeFile.value = (event.target as HTMLInputElement).files?.[0] ?? null }

async function assessCandidate(): Promise<void> {
  if (!selectedJobId.value) return
  loading.value = true
  try {
    let response
    if (resumeFile.value) {
      const body = new FormData(); body.append('jobId', selectedJobId.value); body.append('file', resumeFile.value)
      response = await api<Record<string, any>>('/api/resume-copilot/assessments/file', { method: 'POST', body })
    } else {
      response = await api<Record<string, any>>('/api/resume-copilot/assessments', { method: 'POST', ...jsonBody({ jobId: Number(selectedJobId.value), resumeText: resumeText.value }) })
    }
    assessment.value = response.data
    requestId.value = response.requestId
    showToast(t('hr.assessmentReady'), 'success')
  } catch (error) { showToast(errorText(error), 'danger') } finally { loading.value = false }
}

async function assessmentDecision(action: 'review' | 'cancel'): Promise<void> {
  if (!assessment.value?.assessmentId || !assessment.value?.reviewToken) return
  const body = action === 'review' ? { token: assessment.value.reviewToken, correctedContent: null, reviewerFeedback: null } : { token: assessment.value.reviewToken }
  await businessAction(`/api/resume-copilot/assessments/${assessment.value.assessmentId}/${action}`, body, (data) => {
    assessment.value = { ...assessment.value, status: data.status }
    showToast(action === 'review' ? t('hr.assessmentReviewed') : t('hr.assessmentCancelled'), 'success')
  })
}

async function loadHrData(path: string): Promise<void> {
  loading.value = true
  try { const response = await api(path); hrData.value = response.data; requestId.value = response.requestId }
  catch (error) { showToast(errorText(error), 'danger') } finally { loading.value = false }
}

async function openInterview(): Promise<void> {
  await businessAction('/api/resume-copilot/enterprise/interview-sessions', { assessmentId: Number(interviewAssessmentId.value) }, (data) => {
    interviewSessionId.value = String(data.id ?? data.sessionId)
    hrData.value = data
    showToast(t('hr.interviewOpened'), 'success')
  })
}

async function saveInterviewOpinion(): Promise<void> {
  await businessAction(`/api/resume-copilot/enterprise/interview-sessions/${encodeURIComponent(interviewSessionId.value)}/opinions`, { evidence: lines(interviewEvidence.value), gaps: lines(interviewGaps.value), opinion: interviewOpinion.value }, (data) => {
    hrData.value = data
    showToast(t('hr.opinionSaved'), 'success')
  })
}

async function saveConsent(): Promise<void> {
  const grantedAt = new Date(); const expiresAt = new Date(grantedAt); expiresAt.setDate(grantedAt.getDate() + consentDays.value)
  await businessAction('/api/resume-copilot/enterprise/consents', { consentReference: consentReference.value, candidateReference: candidateReference.value, purpose: consentPurpose.value, grantedAt: grantedAt.toISOString(), expiresAt: expiresAt.toISOString() }, (data) => {
    hrData.value = data
    showToast(t('hr.consentSaved'), 'success')
  })
}

function lines(value: string): string[] { return value.split(/\n+/).map((item) => item.trim()).filter(Boolean) }

async function businessAction(path: string, body: unknown, success: (data: any) => void, method: 'POST' | 'PUT' = 'POST'): Promise<void> {
  loading.value = true
  try { const response = await api<any>(path, { method, ...jsonBody(body) }); requestId.value = response.requestId; success(response.data); confirmAction.value = null }
  catch (error) { showToast(errorText(error), 'danger'); confirmAction.value = null } finally { loading.value = false }
}

function runConfirmedAction(): void {
  const action = confirmAction.value
  if (action === 'data-execute') void executeData()
  if (action === 'support-confirm') void supportDecision('confirm')
  if (action === 'support-cancel') void supportDecision('cancel')
  if (action === 'report-confirm') void reportDecision('confirm')
  if (action === 'report-cancel') void reportDecision('cancel')
  if (action === 'criteria-confirm') void confirmCriteria()
  if (action === 'assessment-review') void assessmentDecision('review')
  if (action === 'assessment-cancel') void assessmentDecision('cancel')
}

const confirmMeta = computed(() => ({
  operation: confirmAction.value ? t(`common.confirmActions.${confirmAction.value}`) : '',
  target: String(result.value?.candidateId ?? result.value?.draft?.draftId ?? result.value?.draftId ?? criteriaDraft.value?.jobId ?? assessment.value?.assessmentId ?? ''),
  current: String(result.value?.status ?? criteriaDraft.value?.status ?? assessment.value?.status ?? 'DRAFTED'),
  targetState: confirmAction.value?.includes('cancel') ? 'CANCELED' : 'CONFIRMED',
  expiresAt: result.value?.expiresAt ?? result.value?.draft?.expiresAt ?? criteriaDraft.value?.expiresAt ?? assessment.value?.expiresAt,
}))

watch(() => [props.module, route.query.section, route.query.tab], () => {
  const allowed = visibleTabs.value
  const requested = String(route.query.tab ?? '')
  activeTab.value = allowed.includes(requested) ? requested : primaryTab.value
  input.value = ''; secondary.value = ''; jobDraft.value = null; criteriaDraft.value = null; resetTransient()
}, { immediate: true })

onMounted(() => { if (props.module === 'hr' && activeTab.value === 'assessment') void loadConfirmedJobs() })
onUnmounted(() => { if (toastTimer) clearTimeout(toastTimer) })
</script>

<template>
  <PageHeader :title="t(`${module}.title`)" :description="t(`${module}.description`)" :status="t('common.workspaceEyebrow')"><span class="page-module-icon"><ModuleIcon :name="module" /></span><StatusBadge :label="t('statuses.ACTIVE')" tone="success" /></PageHeader>

  <div class="tabs" role="tablist" :aria-label="t(`${module}.title`)">
    <button v-for="tab in visibleTabs" :key="tab" role="tab" :aria-selected="activeTab === tab" :class="{ active: activeTab === tab }" @click="selectTab(tab)">{{ t(`${module}.tabs.${tab}`) }}</button>
  </div>

  <div class="workflow-grid">
    <section class="panel task-panel" :class="`task-panel--${module}`">
      <div class="task-panel__heading"><div><p class="panel-kicker">{{ t('common.currentTask') }}</p><h2>{{ t(`${module}.tabs.${activeTab}`) }}</h2></div><div v-if="isPrimaryTab" class="workflow-stages" :aria-label="t('common.taskFlow')" tabindex="0"><span class="active"><b>1</b>{{ t('common.stageInput') }}</span><span :class="{ active: processingVisible }"><b>2</b>{{ t('common.stageReview') }}</span><span :class="{ active: executable || criteriaDraft || assessment || result?.draft || result?.draftId }"><b>3</b>{{ t('common.stageConfirm') }}</span></div></div>

      <form v-if="isPrimaryTab" class="primary-workflow-form" @submit.prevent="submit">
        <label v-if="(module === 'hr' && hrSection === 'recruiting') || module === 'report'">{{ module === 'hr' ? t('hr.jobTitle') : t('report.reportTitle') }}<input v-model="secondary" required maxlength="300"></label>
        <label>{{ formFieldLabel }}<textarea v-model="input" :placeholder="formPlaceholder" required maxlength="4000" rows="7" /></label>
        <div class="quick-start"><div><strong>{{ t('common.quickStart') }}</strong><span>{{ t('common.chooseExample') }}</span></div><div class="prompt-chips"><button v-for="(prompt, index) in quickPrompts" :key="prompt" type="button" @click="usePrompt(prompt, index)">{{ prompt }}</button></div></div>
        <div class="form-actions"><button class="button button--primary button--large" type="submit" :disabled="loading">{{ loading ? t('common.loading') : submitLabel }} <span aria-hidden="true">→</span></button><small>{{ module === 'knowledge' || (module === 'hr' && hrSection === 'employee') ? t('knowledge.answerBoundary') : t('common.reviewBeforeConfirm') }}</small></div>
      </form>

      <template v-else-if="module === 'hr' && activeTab === 'assessment'">
        <section class="workflow-card"><div class="section-heading"><div><h3>{{ t('hr.selectCriteria') }}</h3><p>{{ t('hr.assessmentDescription') }}</p></div><button class="button button--secondary" type="button" @click="loadConfirmedJobs">{{ t('common.refresh') }}</button></div><form class="primary-workflow-form" @submit.prevent="assessCandidate"><label>{{ t('hr.confirmedCriteria') }}<select v-model="selectedJobId" required><option value="" disabled>{{ t('hr.selectCriteriaPlaceholder') }}</option><option v-for="job in confirmedJobs" :key="job.jobId" :value="String(job.jobId)">{{ job.title }} · v{{ job.criteriaVersion }}</option></select></label><label>{{ t('hr.resumeText') }}<textarea v-model="resumeText" rows="8" :required="!resumeFile" :placeholder="t('hr.resumePlaceholder')"></textarea></label><label>{{ t('hr.resumeFile') }}<input type="file" accept=".txt,.md,.pdf,.docx" @change="chooseResume"></label><p class="field-hint">{{ t('hr.resumePrivacy') }}</p><button class="button button--primary" type="submit" :disabled="loading || !selectedJobId || (!resumeText && !resumeFile)">{{ t('hr.assessCandidate') }}</button></form></section>
      </template>
      <template v-else-if="module === 'hr' && activeTab === 'interview'">
        <section class="workflow-card"><h3>{{ t('hr.interviewPurpose') }}</h3><p>{{ t('hr.interviewDescription') }}</p><form class="primary-workflow-form" @submit.prevent="openInterview"><label>{{ t('hr.assessmentId') }}<input v-model="interviewAssessmentId" required inputmode="numeric" pattern="[0-9]+"></label><button class="button button--primary" type="submit">{{ t('hr.openInterview') }}</button></form><form v-if="interviewSessionId" class="primary-workflow-form" @submit.prevent="saveInterviewOpinion"><p><strong>{{ t('hr.sessionId') }}:</strong> {{ interviewSessionId }}</p><label>{{ t('hr.interviewEvidence') }}<textarea v-model="interviewEvidence" required rows="4" :placeholder="t('hr.onePerLine')"></textarea></label><label>{{ t('hr.interviewGaps') }}<textarea v-model="interviewGaps" rows="3" :placeholder="t('hr.onePerLine')"></textarea></label><label>{{ t('hr.interviewOpinion') }}<textarea v-model="interviewOpinion" required rows="4"></textarea></label><button class="button button--primary" type="submit">{{ t('hr.saveOpinion') }}</button></form></section>
      </template>
      <template v-else-if="module === 'hr' && activeTab === 'authorization'">
        <section class="workflow-card"><h3>{{ t('hr.authorizationPurpose') }}</h3><p>{{ t('hr.authorizationDescription') }}</p><form class="primary-workflow-form" @submit.prevent="saveConsent"><label>{{ t('hr.consentReference') }}<input v-model="consentReference" required maxlength="200"></label><label>{{ t('hr.candidateReference') }}<input v-model="candidateReference" required maxlength="200"></label><label>{{ t('hr.consentPurpose') }}<input v-model="consentPurpose" required maxlength="300"></label><label>{{ t('hr.consentDays') }}<input v-model.number="consentDays" type="number" min="1" max="365" required></label><button class="button button--primary" type="submit">{{ t('hr.recordConsent') }}</button></form><pre v-if="hrData" class="result-preview result-preview--bounded">{{ safeJson(hrData) }}</pre></section>
      </template>
      <template v-else-if="module === 'hr' && activeTab === 'onboarding'">
        <section class="workflow-card"><div class="section-heading"><div><h3>{{ t('hr.onboardingPurpose') }}</h3><p>{{ t('hr.onboardingDescription') }}</p></div><button class="button button--secondary" type="button" @click="loadHrData('/api/resume-copilot/enterprise/onboarding-checklists')">{{ t('common.refresh') }}</button></div><div v-if="Array.isArray(hrData)" class="record-grid"><article v-for="item in hrData" :key="item.id"><h4>{{ item.title }}</h4><p>{{ item.roleScope || t('hr.allRoles') }} · v{{ item.version }}</p><ul><li v-for="(step, index) in item.items" :key="index">{{ typeof step === 'string' ? step : step.title ?? safeJson(step) }}</li></ul></article></div><p v-else class="empty-state">{{ t('common.noData') }}</p></section>
      </template>
      <EnterprisePanel v-else :module="module" :tab="activeTab" />

      <section v-if="processingVisible" class="business-result" aria-live="polite">
        <div class="business-result__heading"><div><span class="result-check">✓</span><h3>{{ t('common.processingResult') }}</h3></div><StatusBadge :label="resultStatus" tone="success" /></div>

        <template v-if="module === 'knowledge' && result"><div class="answer-content"><h4>{{ t('knowledge.answer') }}</h4><p>{{ result.answer || t('knowledge.noGroundedAnswer') }}</p><p v-if="result.status && result.status !== 'ANSWERED'" class="field-hint">{{ t('knowledge.statusExplanation') }}</p></div></template>
        <template v-else-if="module === 'support' && result"><div class="review-editor"><h4>{{ t('support.replyDraft') }}</h4><textarea v-model="supportDraftText" rows="9" :disabled="!result.draft || ['CONFIRMED','CANCELED'].includes(result.status)"></textarea><label>{{ t('support.editReason') }}<input v-model="supportEditReason" maxlength="500"></label><div class="button-row"><button class="button button--secondary" type="button" :disabled="!result.draft" @click="saveSupportEdit">{{ t('support.saveEdit') }}</button><button class="button button--primary" type="button" :disabled="!result.draft" @click="confirmAction = 'support-confirm'">{{ t('support.confirmDraft') }}</button><button class="button button--danger" type="button" :disabled="!result.draft" @click="confirmAction = 'support-cancel'">{{ t('common.cancel') }}</button></div><p v-if="result.needsHuman" class="alert alert--warning">{{ t('support.needsHumanExplanation') }}</p></div></template>
        <template v-else-if="module === 'report' && result"><div v-if="reportContent" class="review-editor"><h4>{{ t('report.reportContent') }}</h4><label>{{ t('report.executiveSummary') }}<textarea v-model="reportContent.executiveSummary" rows="5" :disabled="result.status !== 'DRAFTED'"></textarea><small v-if="reportContent.executiveSummarySourceIds?.length">{{ t('common.evidence') }}: {{ reportContent.executiveSummarySourceIds.join(', ') }}</small></label><div v-if="reportContent.metricHighlights?.length" class="report-edit-section"><h5>{{ t('report.metricHighlights') }}</h5><label v-for="(item, index) in reportContent.metricHighlights" :key="index"><span>{{ item.metricName }}: {{ item.metricValue }} {{ item.unit }}</span><textarea v-model="item.summary" rows="2" :disabled="result.status !== 'DRAFTED'"></textarea><small v-if="item.sourceIds?.length">{{ t('common.evidence') }}: {{ item.sourceIds.join(', ') }}</small></label></div><div v-for="section in ['completedItems','risks','actionItems','suggestions']" :key="section" class="report-edit-section"><h5>{{ t(`report.sections.${section}`) }}</h5><label v-for="(item, index) in reportContent[section] ?? []" :key="index"><textarea v-model="item.text" rows="3" :disabled="result.status !== 'DRAFTED'"></textarea><small v-if="item.sourceIds?.length">{{ t('common.evidence') }}: {{ item.sourceIds.join(', ') }}</small></label></div><div class="button-row"><button class="button button--secondary" type="button" :disabled="result.status !== 'DRAFTED'" @click="saveReportEdit">{{ t('report.saveEdit') }}</button><button class="button button--primary" type="button" :disabled="result.status !== 'DRAFTED'" @click="confirmAction = 'report-confirm'">{{ t('report.confirmReport') }}</button><button class="button button--danger" type="button" :disabled="result.status !== 'DRAFTED'" @click="confirmAction = 'report-cancel'">{{ t('common.cancel') }}</button></div><p class="field-hint">{{ t('report.confirmBoundary') }}</p></div><div v-else class="alert alert--warning">{{ (result.reviewReasons ?? []).join('；') || t('report.needsReview') }}</div></template>
        <template v-else-if="module === 'hr' && hrSection === 'recruiting'"><div v-if="jobDraft" class="review-editor"><h4>{{ t('hr.jdDraft') }}</h4><textarea v-model="jobDraft.jdDraft" rows="16"></textarea><div class="button-row"><button class="button button--primary" type="button" @click="extractCriteria">{{ t('hr.extractCriteria') }}</button></div></div><div v-if="criteriaDraft" class="criteria-editor"><h4>{{ t('hr.criteriaReview') }}</h4><label v-for="criterion in criteriaDraft.criteria" :key="criterion.criterionId"><span>{{ criterion.requirementType }} · {{ criterion.category }}</span><textarea v-model="criterion.description" rows="2" :disabled="criteriaDraft.status !== 'DRAFTED'"></textarea></label><div class="button-row"><button class="button button--secondary" type="button" :disabled="criteriaDraft.status !== 'DRAFTED'" @click="saveCriteriaEdits">{{ t('hr.saveCriteriaEdits') }}</button><button class="button button--primary" type="button" :disabled="criteriaDraft.status !== 'DRAFTED'" @click="confirmAction = 'criteria-confirm'">{{ t('hr.confirmCriteria') }}</button></div></div></template>
        <template v-else-if="module === 'data' && result"><p class="business-result__summary">{{ result.summary }}</p><div v-if="result.validation" class="validation-summary"><strong>{{ t('data.guardrail') }}</strong><span>{{ result.executable ? t('data.guardrailPassed') : t('data.guardrailBlocked') }}</span></div></template>
        <template v-else-if="result"><p class="business-result__summary">{{ result.answer || result.summary || result.message }}</p></template>

        <template v-if="assessment"><div class="assessment-review"><h4>{{ t('hr.assessmentResult') }}</h4><p v-if="assessment.content?.anonymousSummary">{{ assessment.content.anonymousSummary }}</p><ul v-if="assessment.content?.criterionAssessments"><li v-for="item in assessment.content.criterionAssessments" :key="item.criterionId"><strong>{{ item.criterionId }} · {{ item.status }}</strong><span>{{ item.explanation }}</span></li></ul><div v-if="assessment.evidence?.length" class="assessment-evidence"><h5>{{ t('common.evidence') }}</h5><ul><li v-for="item in assessment.evidence.slice(0, 8)" :key="item.evidenceId"><strong>{{ item.evidenceId }} · {{ item.section }}</strong><span>{{ item.sanitizedText }}</span></li></ul></div><div v-if="assessment.reviewReasons?.length" class="alert alert--warning">{{ assessment.reviewReasons.join('；') }}</div><div class="button-row"><button v-if="assessment.status === 'DRAFTED'" class="button button--primary" type="button" @click="confirmAction = 'assessment-review'">{{ t('hr.confirmAssessmentReview') }}</button><button v-if="['DRAFTED','NEEDS_REVIEW'].includes(assessment.status)" class="button button--danger" type="button" @click="confirmAction = 'assessment-cancel'">{{ t('common.cancel') }}</button></div></div></template>

        <details><summary>{{ t('common.technicalDetails') }}</summary><pre class="result-preview result-preview--bounded">{{ safeJson(assessment ?? criteriaDraft ?? result ?? jobDraft) }}</pre></details>
      </section>
      <RequestId :value="requestId" />
    </section>

    <aside class="side-stack">
      <SqlPreview v-if="module === 'data' && isPrimaryTab" :sql="sql" />
      <EvidenceList v-if="isPrimaryTab" :items="evidence" />
      <section class="panel"><h2>{{ t('common.risks') }}</h2><p>{{ t(`${module}.riskBoundary`) }}</p><ul class="boundary-list"><li><span>✓</span>{{ t('common.trustEvidence') }}</li><li><span>✓</span>{{ t('common.trustControl') }}</li><li><span>✓</span>{{ t('common.trustAudit') }}</li></ul></section>
      <section class="panel next-step"><h2>{{ t('common.nextStep') }}</h2><p>{{ t(`${module}.next`) }}</p><button v-if="executable" class="button button--danger" type="button" @click="confirmAction = 'data-execute'">{{ t('data.execute') }}</button></section>
    </aside>
  </div>

  <ConfirmDialog :open="Boolean(confirmAction)" :operation="confirmMeta.operation" :target="confirmMeta.target" :current-state="confirmMeta.current" :target-state="confirmMeta.targetState" :impact="t('common.confirmImpact')" :recoverable="false" :expires-at="confirmMeta.expiresAt" :risk="t('common.reviewBeforeConfirm')" :busy="loading" @confirm="runConfirmedAction" @cancel="confirmAction = null" />
  <ToastMessage :message="toast" :tone="toastTone" />
</template>
