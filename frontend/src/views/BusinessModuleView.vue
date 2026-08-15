<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { AI_GENERATION_REQUEST_TIMEOUT_MS, api, ApiError, jsonBody } from '@/api/client'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import DataExecutionResult from '@/components/DataExecutionResult.vue'
import DataEnterprisePanel from '@/components/DataEnterprisePanel.vue'
import EnterprisePanel from '@/components/EnterprisePanel.vue'
import KnowledgeQualityPanel from '@/components/KnowledgeQualityPanel.vue'
import ReportEnterprisePanel from '@/components/ReportEnterprisePanel.vue'
import ResumeReviewPanel from '@/components/ResumeReviewPanel.vue'
import SupportReviewPanel from '@/components/SupportReviewPanel.vue'
import EvidenceList from '@/components/EvidenceList.vue'
import ModuleIcon from '@/components/ModuleIcon.vue'
import PageHeader from '@/components/PageHeader.vue'
import RequestId from '@/components/RequestId.vue'
import SqlPreview from '@/components/SqlPreview.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import ToastMessage from '@/components/ToastMessage.vue'
import { safeJson } from '@/utils/safeDisplay'
import { useSession } from '@/composables/useSession'

type ModuleKey = 'data' | 'knowledge' | 'support' | 'report' | 'hr'
type ConfirmAction = 'data-execute' | 'support-confirm' | 'support-cancel' | 'support-writeback' | 'report-confirm' | 'report-cancel' | 'criteria-confirm' | 'assessment-review' | 'assessment-cancel' | null
const props = defineProps<{ module: ModuleKey }>()
const { t, te } = useI18n()
const { isAdmin, isReviewer, canOperate, session } = useSession()
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
const supportWriteback = ref<Record<string, any> | null>(null)
const supportWritebackEligible = ref<boolean | null>(null)
const supportRecoveryEvidence = ref('')
const knowledgeFeedbackRating = ref('')
const knowledgeFeedbackReason = ref('MISSING_EVIDENCE')
const knowledgeFeedbackComment = ref('')
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
const interviewMembers = ref('')
const interviewMember = ref('')
const interviewQuestions = ref<Record<string, any>[]>([])
const interviewSummaryData = ref<Record<string, any> | null>(null)
const interviewQuestionForm = ref({
  questionKey: '', category: '', questionText: '', evidenceGuidance: '', prohibitedTopics: '',
})
const consentReference = ref('')
const candidateReference = ref('')
const consentPurpose = ref<'ASSESSMENT' | 'ATS_IMPORT'>('ASSESSMENT')
const consentDays = ref(30)
const onboardingChecklists = ref<Record<string, any>[]>([])
const onboardingInstances = ref<Record<string, any>[]>([])
const selectedChecklistId = ref('')
const employeeReference = ref('')
const onboardingEvidence = ref('')
const onboardingChecklistForm = ref({
  checklistKey: '', title: '', roleScope: '', items: '', knowledgeReferences: '',
})
const activeOnboardingChecklists = computed(() => onboardingChecklists.value.filter((item) => item.active))
const reportHandoffs = ref<Record<string, any>[]>([])
const selectedReportHandoffRefs = ref<string[]>([])
const reportSourceFile = ref<File | null>(null)
const reportDataSummary = ref('')
const reportDataTitle = ref('')
const readyReportHandoffs = computed(() => reportHandoffs.value.filter((item) => item.status === 'READY'))
const reportEnterpriseTab = computed<'records' | 'schedules'>(() => activeTab.value === 'schedules' ? 'schedules' : 'records')
const criteriaEditable = computed(() => ['DRAFTED', 'CRITERIA_DRAFTED'].includes(String(criteriaDraft.value?.status ?? '')))

const config = computed(() => ({
  data: { tabs: ['query', 'governance', 'records', 'handoff'], field: 'question', placeholder: 'questionPlaceholder', action: 'generate' },
  knowledge: { tabs: ['qa', 'sources', 'quality'], field: 'question', placeholder: 'questionPlaceholder', action: 'ask' },
  support: { tabs: ['tickets', 'review', 'connections', 'quality'], field: 'ticket', placeholder: 'ticketPlaceholder', action: 'analyze' },
  report: { tabs: ['generate', 'records', 'sources', 'schedules'], field: 'source', placeholder: 'sourcePlaceholder', action: 'generate' },
  hr: { tabs: ['criteria', 'assessment', 'interview', 'authorization', 'employeeQa', 'onboarding'], field: 'requirements', placeholder: 'requirementsPlaceholder', action: 'draft' },
} as const)[props.module])

const hrSection = computed(() => route.query.section === 'employee' ? 'employee' : 'recruiting')
const visibleTabs = computed(() => {
  if (!isReviewer.value) {
    if (props.module === 'hr') {
      return hrSection.value === 'employee'
        ? ['employeeQa', 'onboarding']
        : ['criteria', 'assessment', 'interview', 'authorization']
    }
    if (!isAdmin.value && props.module === 'knowledge') return ['qa']
    if (!isAdmin.value && props.module === 'support') return ['tickets', 'review']
    if (!isAdmin.value && props.module === 'report') return ['generate', 'records']
    return [...config.value.tabs]
  }
  const reviewerTabs: Record<ModuleKey, string[]> = {
    data: ['records'],
    knowledge: ['quality'],
    support: ['review', 'quality'],
    report: [],
    hr: hrSection.value === 'employee' ? [] : ['assessment', 'interview'],
  }
  return reviewerTabs[props.module]
})
const primaryTab = computed(() => props.module === 'hr' ? (hrSection.value === 'employee' ? 'employeeQa' : 'criteria') : config.value.tabs[0])
const isPrimaryTab = computed(() => !isReviewer.value && activeTab.value === primaryTab.value)

const evidence = computed(() => {
  if (!isPrimaryTab.value || !result.value) return []
  const value = result.value
  const candidates = [value.evidence, value.citations, value.content?.citations, value.assumptions, value.warnings, value.draft?.citations, value.reasons]
  return candidates.flatMap((item) => Array.isArray(item) ? item : []).filter(Boolean)
})
const sql = computed(() => props.module === 'data' && isPrimaryTab.value ? String(result.value?.sql ?? '') : '')
const executable = computed(() => props.module === 'data' && result.value?.executable === true)
const dataExecution = computed<Record<string, any> | null>(() => props.module === 'data' ? result.value?.execution ?? null : null)
const hasDataExecution = computed(() => dataExecution.value !== null)
const hasBusinessResult = computed(() => props.module === 'data'
  ? hasDataExecution.value
  : processingVisible.value)
const promptKey = computed(() => props.module === 'hr' && hrSection.value === 'employee' ? 'hr.employeeExamples' : `${props.module}.examples`)
const quickPrompts = computed(() => [t(`${promptKey.value}.first`), t(`${promptKey.value}.second`), t(`${promptKey.value}.third`)])
const formFieldLabel = computed(() => props.module === 'hr' && hrSection.value === 'employee' ? t('hr.employeeQuestion') : t(`${props.module}.${config.value.field}`))
const formPlaceholder = computed(() => props.module === 'hr' && hrSection.value === 'employee' ? t('hr.employeeQuestionPlaceholder') : t(`${props.module}.${config.value.placeholder}`))
const submitLabel = computed(() => props.module === 'hr' && hrSection.value === 'employee' ? t('hr.askEmployee') : t(`${props.module}.${config.value.action}`))
const riskBoundaryText = computed(() => props.module === 'hr' && hrSection.value === 'employee'
  ? t('knowledge.riskBoundary') : t(`${props.module}.riskBoundary`))
const nextStepText = computed(() => props.module === 'hr' && hrSection.value === 'employee'
  ? t('knowledge.next') : t(`${props.module}.next`))
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
  supportWriteback.value = null
  supportWritebackEligible.value = null
  knowledgeFeedbackRating.value = ''
  knowledgeFeedbackComment.value = ''
  reportContent.value = null
  assessment.value = null
  hrData.value = null
}

async function selectTab(tab: string): Promise<void> {
  activeTab.value = tab
  resetTransient()
  await router.replace({ query: { ...route.query, tab } })
  if (props.module === 'hr' && shouldLoadConfirmedJobs(tab)) await loadConfirmedJobs()
  if (props.module === 'hr' && tab === 'interview') await loadInterviewSessions()
  if (props.module === 'hr' && tab === 'onboarding') await loadOnboarding()
  if (props.module === 'report' && tab === 'generate') await loadReportHandoffs()
}

async function loadReportHandoffs(): Promise<void> {
  try {
    const response = await api<Record<string, any>[]>('/api/data-copilot/report-handoffs')
    reportHandoffs.value = response.data ?? []
  } catch (error) {
    showToast(errorText(error), 'danger')
  }
}

function usePrompt(prompt: string, index: number): void {
  if (props.module === 'report') {
    selectedReportHandoffRefs.value = []
    reportSourceFile.value = null
    reportDataSummary.value = ''
    reportDataTitle.value = ''
    secondary.value = t(`report.exampleTitles.${['first', 'second', 'third'][index]}`)
    input.value = t(`report.exampleSources.${['first', 'second', 'third'][index]}`)
    return
  }
  input.value = prompt
  if (props.module === 'hr' && !secondary.value) secondary.value = t(`hr.exampleTitles.${['first', 'second', 'third'][index]}`)
}

function toggleReportHandoff(handoff: Record<string, any>): void {
  const reference = String(handoff.sourceReference)
  const previousSummary = reportDataSummary.value
  const previousTitle = reportDataTitle.value
  selectedReportHandoffRefs.value = selectedReportHandoffRefs.value.includes(reference)
    ? selectedReportHandoffRefs.value.filter((item) => item !== reference)
    : [...selectedReportHandoffRefs.value, reference]
  reportSourceFile.value = null
  const selected = readyReportHandoffs.value.filter((item) => selectedReportHandoffRefs.value.includes(String(item.sourceReference)))
  reportDataSummary.value = selected.map((item) => t('report.dataHandoffSourceSummary', {
    title: item.title,
    reference: item.sourceReference,
    rows: item.rowCount,
  })).join('\n')
  reportDataTitle.value = selected.length === 1 ? String(selected[0]?.title ?? '') : selected.map((item) => item.title).join(' / ')
  if (!input.value.trim() || input.value === previousSummary) input.value = reportDataSummary.value
  if (!secondary.value.trim() || secondary.value === previousTitle) secondary.value = reportDataTitle.value
}

function chooseReportSource(event: Event): void {
  reportSourceFile.value = (event.target as HTMLInputElement).files?.[0] ?? null
  if (reportSourceFile.value) {
    selectedReportHandoffRefs.value = []
    reportDataSummary.value = ''
    reportDataTitle.value = ''
  }
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
    case 'report': {
      const period = { periodStart: startDate.toISOString().slice(0, 10), periodEnd: end }
      if (selectedReportHandoffRefs.value.length) {
        return {
          path: '/api/report-copilot/enterprise/reports/generate',
          body: {
            reportType: 'TEAM_WEEKLY', period, title: secondary.value,
            selection: { connectionIds: [], dataHandoffReferences: selectedReportHandoffRefs.value, includeSupportMetrics: false, previousDataHandoffReference: null },
            templateId: 'business-weekly', templateVersion: 'v1',
          },
        }
      }
      return { path: '/api/report-copilot/reports/generate', body: { reportType: 'TEAM_WEEKLY', period, title: secondary.value, metrics: [], tasks: [], meetingNotes: [{ title: secondary.value, content: input.value, recordedAt: new Date().toISOString() }], importedSources: [], templateId: null, templateVersion: null } }
    }
    case 'hr': return hrSection.value === 'employee'
      ? { path: '/api/knowledge-copilot/questions', body: { question: input.value, category: 'HR_POLICY' } }
      : { path: '/api/resume-copilot/jobs/draft', body: { title: secondary.value, requirements: input.value } }
  }
}

async function submit(): Promise<void> {
  loading.value = true
  resetTransient()
  try {
    if (props.module === 'report' && reportSourceFile.value) {
      const today = new Date()
      const startDate = new Date(today)
      startDate.setDate(today.getDate() - 7)
      const query = new URLSearchParams({
        reportType: 'TEAM_WEEKLY',
        periodStart: startDate.toISOString().slice(0, 10),
        periodEnd: today.toISOString().slice(0, 10),
        title: secondary.value,
      })
      const body = new FormData()
      body.append('file', reportSourceFile.value)
      const response = await api<Record<string, any>>(`/api/report-copilot/reports/generate-from-file?${query.toString()}`, {
        method: 'POST', body, timeoutMs: AI_GENERATION_REQUEST_TIMEOUT_MS,
      })
      requestId.value = response.requestId ?? response.data.requestId ?? null
      result.value = response.data
      reportContent.value = response.data.content && typeof response.data.content === 'object'
        ? structuredClone(response.data.content) : null
      return
    }
    const request = genericPayload()
    const response = await api<Record<string, any>>(request.path, {
      method: 'POST',
      ...jsonBody(request.body),
      // Model calls may use the server's 120-second provider timeout. Do not
      // let the former shared 30-second browser timeout cancel them first.
      timeoutMs: AI_GENERATION_REQUEST_TIMEOUT_MS,
    })
    requestId.value = response.requestId ?? response.data.requestId ?? null
    if (props.module === 'hr' && hrSection.value === 'recruiting') {
      jobDraft.value = response.data
    } else {
      result.value = response.data
    }
    if (props.module === 'support') supportDraftText.value = response.data.draft?.replyText ?? ''
    if (props.module === 'report') {
      reportContent.value = response.data.content && typeof response.data.content === 'object'
        ? structuredClone(response.data.content)
        : null
    }
    if (props.module === 'report' && selectedReportHandoffRefs.value.length) {
      selectedReportHandoffRefs.value = []
      await loadReportHandoffs()
    }
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
  if (action === 'confirm' && result.value?.status === 'CONFIRMED') {
    await loadSupportWritebackCapability(draft.draftId)
  }
}

async function loadSupportWritebackCapability(draftId: number): Promise<void> {
  try {
    const response = await api<{ eligible: boolean }>(`/api/support-copilot/enterprise/drafts/${draftId}/writeback-capability`)
    supportWritebackEligible.value = response.data.eligible === true
    requestId.value = response.requestId ?? requestId.value
  } catch (error) {
    supportWritebackEligible.value = false
    showToast(errorText(error), 'danger')
  }
}

async function prepareSupportWriteback(): Promise<void> {
  const draftId = result.value?.draft?.draftId
  if (!draftId) return
  await businessAction(`/api/support-copilot/enterprise/drafts/${draftId}/writeback-intent`, {}, (data) => {
    supportWriteback.value = data
    showToast(t('support.writebackPrepared'), 'success')
  })
}

async function confirmSupportWriteback(): Promise<void> {
  if (!supportWriteback.value?.id || !supportWriteback.value?.confirmationToken) return
  const writebackId = Number(supportWriteback.value.id)
  loading.value = true
  try {
    const response = await api<Record<string, any>>(`/api/support-copilot/enterprise/writebacks/${writebackId}/confirm`, {
      method: 'POST', ...jsonBody({ confirmationToken: supportWriteback.value.confirmationToken }),
    })
    supportWriteback.value = { ...supportWriteback.value, ...response.data, confirmationToken: null }
    requestId.value = response.requestId
    showToast(t('support.writebackCompleted'), 'success')
  } catch (error) {
    await refreshSupportWriteback(writebackId)
    showToast(errorText(error), 'danger')
  } finally {
    confirmAction.value = null
    loading.value = false
  }
}

async function refreshSupportWriteback(writebackId = Number(supportWriteback.value?.id)): Promise<void> {
  if (!Number.isInteger(writebackId) || writebackId <= 0) return
  try {
    const response = await api<Record<string, any>>(`/api/support-copilot/enterprise/writebacks/${writebackId}`)
    supportWriteback.value = { ...supportWriteback.value, ...response.data, confirmationToken: null }
    requestId.value = response.requestId ?? requestId.value
  } catch (error) { showToast(errorText(error), 'danger') }
}

async function resolveSupportWriteback(resolution: 'COMPLETED' | 'SAFE_TO_RETRY'): Promise<void> {
  if (!supportWriteback.value?.id || !supportRecoveryEvidence.value.trim()) return
  await businessAction(`/api/support-copilot/enterprise/writebacks/${supportWriteback.value.id}/resolve`, {
    resolution, evidenceReference: supportRecoveryEvidence.value.trim(),
  }, (data) => {
    supportWriteback.value = { ...supportWriteback.value, ...data }
    supportRecoveryEvidence.value = ''
    showToast(resolution === 'COMPLETED' ? t('support.writebackResolved') : t('support.writebackRetryPrepared'), 'success')
  })
}

async function submitKnowledgeFeedback(): Promise<void> {
  if (!result.value?.answerId || !knowledgeFeedbackRating.value) return
  await businessAction(`/api/knowledge-copilot/answers/${result.value.answerId}/feedback`, {
    rating: knowledgeFeedbackRating.value,
    reason: knowledgeFeedbackRating.value === 'HELPFUL' ? null : knowledgeFeedbackReason.value,
    comment: knowledgeFeedbackComment.value || null,
  }, () => { showToast(t('knowledge.feedbackSaved'), 'success') })
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

async function openReportReview(event: Event): Promise<void> {
  const draftId = Number((event as CustomEvent<{ draftId?: number }>).detail?.draftId)
  if (!Number.isInteger(draftId) || draftId <= 0) return
  activeTab.value = 'generate'
  loading.value = true
  try {
    const response = await api<Record<string, any>>(`/api/report-copilot/reports/${draftId}/review-session`, { method: 'POST' })
    result.value = response.data
    reportContent.value = response.data.content && typeof response.data.content === 'object'
      ? structuredClone(response.data.content) : null
    requestId.value = response.requestId
    showToast(t('report.reviewOpened'), 'success')
  } catch (error) { showToast(errorText(error), 'danger') } finally { loading.value = false }
}

async function extractCriteria(): Promise<void> {
  if (!jobDraft.value) return
  loading.value = true
  try {
    const response = await api<Record<string, any>>('/api/resume-copilot/jobs/criteria', {
      method: 'POST',
      ...jsonBody({ title: secondary.value, jobDescription: jobDraft.value.jdDraft, logicalJobId: null }),
      timeoutMs: AI_GENERATION_REQUEST_TIMEOUT_MS,
    })
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

function shouldLoadConfirmedJobs(tab: string): boolean {
  return canOperate.value && ['assessment', 'authorization'].includes(tab)
}

async function loadConfirmedJobs(): Promise<void> {
  if (!shouldLoadConfirmedJobs(activeTab.value)) return
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
      const body = new FormData(); body.append('jobId', selectedJobId.value)
      body.append('candidateReference', candidateReference.value)
      body.append('consentReference', consentReference.value)
      body.append('file', resumeFile.value)
      response = await api<Record<string, any>>('/api/resume-copilot/assessments/file', {
        method: 'POST', body, timeoutMs: AI_GENERATION_REQUEST_TIMEOUT_MS,
      })
    } else {
      response = await api<Record<string, any>>('/api/resume-copilot/assessments', {
        method: 'POST',
        ...jsonBody({ jobId: Number(selectedJobId.value), candidateReference: candidateReference.value,
          consentReference: consentReference.value, resumeText: resumeText.value }),
        timeoutMs: AI_GENERATION_REQUEST_TIMEOUT_MS,
      })
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

async function openInterview(): Promise<void> {
  await businessAction('/api/resume-copilot/enterprise/interview-sessions', {
    assessmentId: Number(interviewAssessmentId.value), interviewerActorIds: lines(interviewMembers.value),
  }, (data) => {
    interviewSessionId.value = String(data.id ?? data.sessionId)
    hrData.value = [data, ...(Array.isArray(hrData.value) ? hrData.value : [])]
    showToast(t('hr.interviewOpened'), 'success')
  })
}

async function loadInterviewSessions(): Promise<void> {
  loading.value = true
  try {
    const [sessionResponse, questionResponse] = await Promise.all([
      api<Record<string, any>[]>('/api/resume-copilot/enterprise/interview-sessions'),
      api<Record<string, any>[]>('/api/resume-copilot/enterprise/question-bank'),
    ])
    hrData.value = sessionResponse.data ?? []
    interviewQuestions.value = questionResponse.data ?? []
    requestId.value = sessionResponse.requestId ?? questionResponse.requestId
  } catch (error) { showToast(errorText(error), 'danger') } finally { loading.value = false }
}

async function saveInterviewQuestion(): Promise<void> {
  const form = interviewQuestionForm.value
  await businessAction('/api/resume-copilot/enterprise/question-bank', {
    questionKey: form.questionKey, category: form.category,
    questionText: form.questionText, evidenceGuidance: form.evidenceGuidance,
    prohibitedTopics: lines(form.prohibitedTopics),
  }, (data) => {
    interviewQuestions.value = [data, ...interviewQuestions.value]
    interviewQuestionForm.value = { questionKey: '', category: '', questionText: '', evidenceGuidance: '', prohibitedTopics: '' }
    showToast(t('hr.questionSaved'), 'success')
  })
}

async function addInterviewMember(): Promise<void> {
  if (!interviewSessionId.value || !interviewMember.value.trim()) return
  await businessAction(`/api/resume-copilot/enterprise/interview-sessions/${encodeURIComponent(interviewSessionId.value)}/members`, { actorId: interviewMember.value }, (data) => {
    interviewMember.value = ''; showToast(t('hr.memberAdded'), 'success')
    if (data?.id) hrData.value = Array.isArray(hrData.value) ? hrData.value.map((item: Record<string, any>) => item.id === data.id ? data : item) : hrData.value
  })
}

async function closeInterview(): Promise<void> {
  if (!interviewSessionId.value) return
  await businessAction(`/api/resume-copilot/enterprise/interview-sessions/${encodeURIComponent(interviewSessionId.value)}/close`, {}, (data) => {
    hrData.value = Array.isArray(hrData.value) ? hrData.value.map((item: Record<string, any>) => item.id === data.id ? data : item) : data
    showToast(t('hr.interviewClosed'), 'success')
  })
}

async function saveInterviewOpinion(): Promise<void> {
  await businessAction(`/api/resume-copilot/enterprise/interview-sessions/${encodeURIComponent(interviewSessionId.value)}/opinions`, { evidence: lines(interviewEvidence.value), gaps: lines(interviewGaps.value), opinion: interviewOpinion.value }, (data) => {
    interviewSummaryData.value = data
    showToast(t('hr.opinionSaved'), 'success')
  })
}

async function loadInterviewSummary(sessionId: number): Promise<void> {
  loading.value = true
  try {
    const response = await api<Record<string, any>>(`/api/resume-copilot/enterprise/interview-sessions/${sessionId}/summary`)
    interviewSummaryData.value = response.data
    interviewSessionId.value = String(sessionId)
    requestId.value = response.requestId
  } catch (error) { showToast(errorText(error), 'danger') } finally { loading.value = false }
}

async function saveConsent(): Promise<void> {
  const grantedAt = new Date(); const expiresAt = new Date(grantedAt); expiresAt.setDate(grantedAt.getDate() + consentDays.value)
  await businessAction('/api/resume-copilot/enterprise/consents', { consentReference: consentReference.value, candidateReference: candidateReference.value, purpose: consentPurpose.value, grantedAt: grantedAt.toISOString(), expiresAt: expiresAt.toISOString() }, (data) => {
    hrData.value = data
    showToast(t('hr.consentSaved'), 'success')
  })
}

async function revokeConsent(): Promise<void> {
  if (!consentReference.value.trim()) return
  await businessAction(`/api/resume-copilot/enterprise/consents/${encodeURIComponent(consentReference.value.trim())}/revoke`, {}, (data) => {
    hrData.value = data
    showToast(t('hr.consentRevoked'), 'success')
  })
}

async function assessAuthorizedCandidate(): Promise<void> {
  if (!selectedJobId.value || !consentReference.value || !candidateReference.value || !resumeText.value) return
  await businessAction('/api/resume-copilot/enterprise/authorized-assessments', {
    jobId: Number(selectedJobId.value), candidateReference: candidateReference.value,
    consentReference: consentReference.value, resumeText: resumeText.value,
  }, (data) => { assessment.value = data; showToast(t('hr.authorizedAssessmentReady'), 'success') })
}

async function approveQuestion(item: Record<string, any>): Promise<void> {
  await businessAction(`/api/resume-copilot/enterprise/question-bank/${item.id}/approve`, {}, (data) => {
    interviewQuestions.value = interviewQuestions.value.map((current) => current.questionKey === data.questionKey
      ? (current.id === data.id ? data : { ...current, active: false }) : current)
    showToast(t('hr.questionApproved'), 'success')
  })
}

async function approveChecklist(item: Record<string, any>): Promise<void> {
  await businessAction(`/api/resume-copilot/enterprise/onboarding-checklists/${item.id}/approve`, {}, (data) => {
    onboardingChecklists.value = onboardingChecklists.value.map((current) => current.checklistKey === data.checklistKey
      ? (current.id === data.id ? data : { ...current, active: false }) : current)
    showToast(t('hr.checklistApproved'), 'success')
  })
}

async function saveOnboardingChecklist(): Promise<void> {
  const form = onboardingChecklistForm.value
  const items = lines(form.items).map((line) => {
    const [itemKey = '', title = '', guidance = '', ownerRole = '', requiredText = 'required'] = line.split('|').map((item) => item.trim())
    return { itemKey, title, guidance, required: !['optional', '选办', 'false'].includes(requiredText.toLowerCase()), ownerRole: ownerRole || null }
  })
  await businessAction('/api/resume-copilot/enterprise/onboarding-checklists', {
    checklistKey: form.checklistKey, title: form.title, roleScope: form.roleScope || null,
    items, knowledgeReferences: lines(form.knowledgeReferences),
  }, (data) => {
    onboardingChecklists.value = [data, ...onboardingChecklists.value]
    onboardingChecklistForm.value = { checklistKey: '', title: '', roleScope: '', items: '', knowledgeReferences: '' }
    showToast(t('hr.checklistSaved'), 'success')
  })
}

async function loadOnboarding(): Promise<void> {
  loading.value = true
  try {
    const [checklistResponse, instanceResponse] = await Promise.all([
      api<Record<string, any>[]>('/api/resume-copilot/enterprise/onboarding-checklists'),
      api<Record<string, any>[]>('/api/resume-copilot/enterprise/onboarding-instances'),
    ])
    onboardingChecklists.value = checklistResponse.data ?? []
    onboardingInstances.value = instanceResponse.data ?? []
    requestId.value = instanceResponse.requestId ?? checklistResponse.requestId
    if (!selectedChecklistId.value) {
      const active = onboardingChecklists.value.find((item) => item.active)
      if (active) selectedChecklistId.value = String(active.id)
    }
  } catch (error) { showToast(errorText(error), 'danger') } finally { loading.value = false }
}

async function startOnboarding(): Promise<void> {
  await businessAction('/api/resume-copilot/enterprise/onboarding-instances', {
    checklistId: Number(selectedChecklistId.value), employeeReference: employeeReference.value,
  }, (data) => {
    onboardingInstances.value = [data, ...onboardingInstances.value]
    employeeReference.value = ''; showToast(t('hr.onboardingStarted'), 'success')
  })
}

async function completeOnboardingTask(instanceId: number, taskId: number): Promise<void> {
  if (!onboardingEvidence.value.trim()) return
  await businessAction(`/api/resume-copilot/enterprise/onboarding-instances/${instanceId}/tasks/${taskId}/complete`, { evidenceReference: onboardingEvidence.value }, (data) => {
    onboardingInstances.value = onboardingInstances.value.map((item) => item.id === instanceId ? data : item)
    onboardingEvidence.value = ''; showToast(t('hr.onboardingTaskCompleted'), 'success')
  })
}

async function cancelOnboarding(instanceId: number): Promise<void> {
  await businessAction(`/api/resume-copilot/enterprise/onboarding-instances/${instanceId}/cancel`, {}, (data) => {
    onboardingInstances.value = onboardingInstances.value.map((item) => item.id === instanceId ? data : item)
    showToast(t('hr.onboardingCancelled'), 'success')
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
  if (action === 'support-writeback') void confirmSupportWriteback()
  if (action === 'report-confirm') void reportDecision('confirm')
  if (action === 'report-cancel') void reportDecision('cancel')
  if (action === 'criteria-confirm') void confirmCriteria()
  if (action === 'assessment-review') void assessmentDecision('review')
  if (action === 'assessment-cancel') void assessmentDecision('cancel')
}

const confirmMeta = computed(() => ({
  operation: confirmAction.value ? t(`common.confirmActions.${confirmAction.value}`) : '',
  target: String(result.value?.candidateId ?? result.value?.draft?.draftId ?? result.value?.draftId ?? supportWriteback.value?.id ?? criteriaDraft.value?.jobId ?? assessment.value?.assessmentId ?? ''),
  current: String(result.value?.status ?? criteriaDraft.value?.status ?? assessment.value?.status ?? 'DRAFTED'),
  targetState: confirmAction.value?.includes('cancel') ? 'CANCELED' : confirmAction.value === 'support-writeback' ? 'COMPLETED' : 'CONFIRMED',
  expiresAt: result.value?.expiresAt ?? result.value?.draft?.expiresAt ?? criteriaDraft.value?.expiresAt ?? assessment.value?.expiresAt,
}))

watch(() => [props.module, route.query.section, route.query.tab], async () => {
  const allowed = visibleTabs.value
  const requested = String(route.query.tab ?? '')
  activeTab.value = allowed.includes(requested) ? requested : (allowed[0] ?? primaryTab.value)
  input.value = ''; secondary.value = ''; jobDraft.value = null; criteriaDraft.value = null; resetTransient()
  if (props.module === 'report' && activeTab.value === 'generate') await loadReportHandoffs()
  if (props.module === 'hr' && shouldLoadConfirmedJobs(activeTab.value)) await loadConfirmedJobs()
  if (props.module === 'hr' && activeTab.value === 'interview') await loadInterviewSessions()
  if (props.module === 'hr' && activeTab.value === 'onboarding') await loadOnboarding()
}, { immediate: true })
onMounted(() => { window.addEventListener('report-review-open', openReportReview) })
onUnmounted(() => {
  if (toastTimer) clearTimeout(toastTimer)
  window.removeEventListener('report-review-open', openReportReview)
})
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
        <template v-if="module === 'report'">
          <section class="workflow-card report-source-picker">
            <div class="section-heading"><div><h3>{{ t('report.dataHandoffTitle') }}</h3><p>{{ t('report.dataHandoffDescription') }}</p></div><button class="button button--secondary" type="button" :disabled="loading" @click="loadReportHandoffs">{{ t('common.refresh') }}</button></div>
            <div v-if="readyReportHandoffs.length" class="data-handoff-picker">
              <button v-for="handoff in readyReportHandoffs" :key="handoff.id" class="data-handoff-option" :class="{ active: selectedReportHandoffRefs.includes(handoff.sourceReference) }" type="button" :aria-pressed="selectedReportHandoffRefs.includes(handoff.sourceReference)" @click="toggleReportHandoff(handoff)"><span class="data-handoff-option__check">{{ selectedReportHandoffRefs.includes(handoff.sourceReference) ? '✓' : '+' }}</span><span><strong>{{ handoff.title }}</strong><small>{{ handoff.sourceReference }} · {{ handoff.rowCount }} {{ t('data.rows') }}</small></span></button>
            </div>
            <p v-else class="empty-state">{{ t('report.noDataHandoffs') }}</p>
          </section>
          <label>{{ t('report.reportTitle') }}<input v-model="secondary" required maxlength="300"></label>
          <label>{{ formFieldLabel }}<textarea v-model="input" :placeholder="formPlaceholder" :required="!reportSourceFile" maxlength="4000" rows="7" /></label>
          <label class="report-file-field">{{ t('report.uploadSource') }}<input type="file" accept=".csv,.json,text/csv,application/json" @change="chooseReportSource"><small>{{ t('report.uploadSourceHint') }}</small></label>
          <div class="quick-start"><div><strong>{{ t('common.quickStart') }}</strong><span>{{ t('report.quickStartHint') }}</span></div><div class="prompt-chips"><button v-for="(prompt, index) in quickPrompts" :key="prompt" type="button" @click="usePrompt(prompt, index)">{{ prompt }}</button></div></div>
        </template>
        <template v-else>
          <label v-if="module === 'hr' && hrSection === 'recruiting'">{{ t('hr.jobTitle') }}<input v-model="secondary" required maxlength="300"></label>
          <label>{{ formFieldLabel }}<textarea v-model="input" :placeholder="formPlaceholder" required maxlength="4000" rows="7" /></label>
          <div class="quick-start"><div><strong>{{ t('common.quickStart') }}</strong><span>{{ t('common.chooseExample') }}</span></div><div class="prompt-chips"><button v-for="(prompt, index) in quickPrompts" :key="prompt" type="button" @click="usePrompt(prompt, index)">{{ prompt }}</button></div></div>
        </template>
        <div class="form-actions"><button class="button button--primary button--large" type="submit" :disabled="loading">{{ loading ? t('common.loading') : submitLabel }} <span aria-hidden="true">→</span></button><small>{{ module === 'knowledge' || (module === 'hr' && hrSection === 'employee') ? t('knowledge.answerBoundary') : t('common.reviewBeforeConfirm') }}</small></div>
      </form>

      <template v-else-if="module === 'hr' && activeTab === 'assessment' && canOperate">
        <section class="workflow-card"><div class="section-heading"><div><h3>{{ t('hr.selectCriteria') }}</h3><p>{{ t('hr.assessmentDescription') }}</p></div><button class="button button--secondary" type="button" @click="loadConfirmedJobs">{{ t('common.refresh') }}</button></div><form class="primary-workflow-form" @submit.prevent="assessCandidate"><label>{{ t('hr.confirmedCriteria') }}<select v-model="selectedJobId" required><option value="" disabled>{{ t('hr.selectCriteriaPlaceholder') }}</option><option v-for="job in confirmedJobs" :key="job.jobId" :value="String(job.jobId)">{{ job.title }} · v{{ job.criteriaVersion }}</option></select></label><label>{{ t('hr.candidateReference') }}<input v-model="candidateReference" required maxlength="200"></label><label>{{ t('hr.consentReference') }}<input v-model="consentReference" required maxlength="200"></label><label>{{ t('hr.resumeText') }}<textarea v-model="resumeText" rows="8" :required="!resumeFile" :placeholder="t('hr.resumePlaceholder')"></textarea></label><label>{{ t('hr.resumeFile') }}<input type="file" accept=".txt,.md,.pdf,.docx" @change="chooseResume"></label><p class="field-hint">{{ t('hr.resumePrivacy') }}</p><button class="button button--primary" type="submit" :disabled="loading || !selectedJobId || !candidateReference || !consentReference || (!resumeText && !resumeFile)">{{ t('hr.assessCandidate') }}</button></form></section>
      </template>
      <template v-else-if="module === 'hr' && activeTab === 'interview'">
        <section class="workflow-card">
          <h3>{{ t('hr.questionGovernance') }}</h3>
          <form v-if="canOperate" class="primary-workflow-form" @submit.prevent="saveInterviewQuestion">
            <label>{{ t('hr.questionKey') }}<input v-model="interviewQuestionForm.questionKey" required maxlength="100"></label>
            <label>{{ t('hr.questionCategory') }}<input v-model="interviewQuestionForm.category" required maxlength="80"></label>
            <label>{{ t('hr.questionText') }}<textarea v-model="interviewQuestionForm.questionText" required rows="3" maxlength="1000"></textarea></label>
            <label>{{ t('hr.evidenceGuidance') }}<textarea v-model="interviewQuestionForm.evidenceGuidance" required rows="3" maxlength="1500"></textarea></label>
            <label>{{ t('hr.prohibitedTopics') }}<textarea v-model="interviewQuestionForm.prohibitedTopics" rows="2" :placeholder="t('hr.onePerLine')"></textarea></label>
            <button class="button button--secondary" type="submit">{{ t('hr.saveQuestionVersion') }}</button>
          </form>
          <div v-if="interviewQuestions.length" class="record-grid">
            <article v-for="item in interviewQuestions" :key="item.id">
              <div class="section-heading"><h4>{{ item.questionKey }} · v{{ item.version }}</h4><StatusBadge :label="item.active ? t('statuses.ACTIVE') : t('statuses.PENDING_REVIEW')" :tone="item.active ? 'success' : 'warning'" /></div>
              <p>{{ item.questionText }}</p><small>{{ item.category }} · {{ item.evidenceGuidance }}</small>
              <button v-if="isAdmin && !item.active && item.ownerActorId !== session?.username" class="button button--secondary" type="button" @click="approveQuestion(item)">{{ t('hr.approveQuestion') }}</button>
            </article>
          </div>
        </section>
        <section class="workflow-card"><div class="section-heading"><div><h3>{{ t('hr.interviewPurpose') }}</h3><p>{{ t('hr.interviewDescription') }}</p></div><button class="button button--secondary" type="button" @click="loadInterviewSessions">{{ t('common.refresh') }}</button></div><form v-if="canOperate" class="primary-workflow-form" @submit.prevent="openInterview"><label>{{ t('hr.assessmentId') }}<input v-model="interviewAssessmentId" required inputmode="numeric" pattern="[0-9]+"></label><label>{{ t('hr.interviewerMembers') }}<textarea v-model="interviewMembers" rows="3" :placeholder="t('hr.onePerLine')"></textarea></label><button class="button button--primary" type="submit">{{ t('hr.openInterview') }}</button></form><div v-if="Array.isArray(hrData) && hrData.length" class="record-grid"><article v-for="item in hrData" :key="item.id"><div class="section-heading"><h4>{{ item.sessionReference }}</h4><StatusBadge :label="item.status" :tone="item.status === 'OPEN' ? 'success' : 'info'" /></div><p>#{{ item.id }} · {{ t('hr.assessmentId') }} {{ item.assessmentId }}</p><div class="button-row"><button v-if="item.status === 'OPEN'" class="button button--secondary" type="button" @click="interviewSessionId = String(item.id)">{{ t('hr.continueInterview') }}</button><button class="button button--secondary" type="button" @click="loadInterviewSummary(item.id)">{{ t('hr.viewSummary') }}</button></div></article></div><form v-if="interviewSessionId" class="primary-workflow-form" @submit.prevent="saveInterviewOpinion"><p><strong>{{ t('hr.sessionId') }}:</strong> {{ interviewSessionId }}</p><label v-if="canOperate">{{ t('hr.addInterviewer') }}<input v-model="interviewMember" maxlength="100"></label><div v-if="canOperate" class="button-row"><button class="button button--secondary" type="button" @click="addInterviewMember">{{ t('hr.addMember') }}</button><button class="button button--danger" type="button" @click="closeInterview">{{ t('hr.closeInterview') }}</button></div><label>{{ t('hr.interviewEvidence') }}<textarea v-model="interviewEvidence" required rows="4" :placeholder="t('hr.onePerLine')"></textarea></label><label>{{ t('hr.interviewGaps') }}<textarea v-model="interviewGaps" rows="3" :placeholder="t('hr.onePerLine')"></textarea></label><label>{{ t('hr.interviewOpinion') }}<textarea v-model="interviewOpinion" required rows="4"></textarea></label><button class="button button--primary" type="submit">{{ t('hr.saveOpinion') }}</button></form><pre v-if="interviewSummaryData" class="result-preview result-preview--bounded">{{ safeJson(interviewSummaryData) }}</pre></section>
      </template>
      <template v-else-if="module === 'hr' && activeTab === 'authorization'">
        <section class="workflow-card"><h3>{{ t('hr.authorizationPurpose') }}</h3><p>{{ t('hr.authorizationDescription') }}</p><form class="primary-workflow-form" @submit.prevent="saveConsent"><label>{{ t('hr.consentReference') }}<input v-model="consentReference" required maxlength="200"></label><label>{{ t('hr.candidateReference') }}<input v-model="candidateReference" required maxlength="200"></label><label>{{ t('hr.consentPurpose') }}<select v-model="consentPurpose" required><option value="ASSESSMENT">{{ t('hr.consentAssessment') }}</option><option value="ATS_IMPORT">{{ t('hr.consentAtsImport') }}</option></select></label><label>{{ t('hr.consentDays') }}<input v-model.number="consentDays" type="number" min="1" max="365" required></label><button class="button button--primary" type="submit">{{ t('hr.recordConsent') }}</button><button class="button button--danger" type="button" @click="revokeConsent">{{ t('hr.revokeConsent') }}</button></form><form v-if="consentPurpose === 'ASSESSMENT'" class="primary-workflow-form" @submit.prevent="assessAuthorizedCandidate"><h4>{{ t('hr.authorizedAssessment') }}</h4><label>{{ t('hr.confirmedCriteria') }}<select v-model="selectedJobId" required><option value="" disabled>{{ t('hr.selectCriteriaPlaceholder') }}</option><option v-for="job in confirmedJobs" :key="job.jobId" :value="String(job.jobId)">{{ job.title }} · v{{ job.criteriaVersion }}</option></select></label><label>{{ t('hr.resumeText') }}<textarea v-model="resumeText" required rows="6"></textarea></label><button class="button button--secondary" type="submit">{{ t('hr.runAuthorizedAssessment') }}</button></form><pre v-if="hrData" class="result-preview result-preview--bounded">{{ safeJson(hrData) }}</pre></section>
      </template>
      <template v-else-if="module === 'hr' && activeTab === 'onboarding'">
        <section v-if="canOperate" class="workflow-card">
          <h3>{{ t('hr.checklistGovernance') }}</h3>
          <form class="primary-workflow-form" @submit.prevent="saveOnboardingChecklist">
            <label>{{ t('hr.checklistKey') }}<input v-model="onboardingChecklistForm.checklistKey" required maxlength="100"></label>
            <label>{{ t('hr.checklistTitle') }}<input v-model="onboardingChecklistForm.title" required maxlength="300"></label>
            <label>{{ t('hr.roleScope') }}<input v-model="onboardingChecklistForm.roleScope" maxlength="100"></label>
            <label>{{ t('hr.checklistItems') }}<textarea v-model="onboardingChecklistForm.items" required rows="4" :placeholder="t('hr.checklistItemsHint')"></textarea></label>
            <label>{{ t('hr.knowledgeReferences') }}<textarea v-model="onboardingChecklistForm.knowledgeReferences" rows="3" :placeholder="t('hr.onePerLine')"></textarea></label>
            <button class="button button--secondary" type="submit">{{ t('hr.saveChecklistVersion') }}</button>
          </form>
        </section>
        <section class="workflow-card"><div class="section-heading"><div><h3>{{ t('hr.onboardingPurpose') }}</h3><p>{{ t('hr.onboardingDescription') }}</p></div><button class="button button--secondary" type="button" @click="loadOnboarding">{{ t('common.refresh') }}</button></div><form v-if="canOperate" class="primary-workflow-form" @submit.prevent="startOnboarding"><label>{{ t('hr.onboardingChecklist') }}<select v-model="selectedChecklistId" required><option value="" disabled>{{ t('hr.selectChecklist') }}</option><option v-for="item in activeOnboardingChecklists" :key="item.id" :value="String(item.id)">{{ item.title }} · v{{ item.version }}</option></select></label><label>{{ t('hr.employeeReference') }}<input v-model="employeeReference" required maxlength="200"></label><button class="button button--primary" type="submit">{{ t('hr.startOnboarding') }}</button></form><label>{{ t('hr.onboardingEvidence') }}<input v-model="onboardingEvidence" maxlength="1000" :placeholder="t('hr.onboardingEvidenceHint')"></label><div v-if="onboardingInstances.length" class="record-grid"><article v-for="instance in onboardingInstances" :key="instance.id"><div class="section-heading"><h4>{{ instance.employeeReference }}</h4><StatusBadge :label="instance.status" :tone="instance.status === 'COMPLETED' ? 'success' : instance.status === 'IN_PROGRESS' ? 'warning' : 'info'" /></div><p v-if="instance.knowledgeReferences?.length" class="field-hint">{{ t('hr.knowledgeReferences') }}：{{ instance.knowledgeReferences.join(', ') }}</p><ul><li v-for="task in instance.tasks" :key="task.id"><span><strong>{{ task.required ? t('hr.requiredTask') : t('hr.optionalTask') }}</strong> · {{ task.title }} · {{ task.status }}<small v-if="task.ownerRole || task.guidance">{{ task.ownerRole || t('common.unknown') }} · {{ task.guidance }}</small></span><button v-if="task.status === 'PENDING' && (instance.status === 'IN_PROGRESS' || !task.required)" class="button button--secondary" type="button" :disabled="!onboardingEvidence.trim()" @click="completeOnboardingTask(instance.id, task.id)">{{ t('hr.completeTask') }}</button></li></ul><button v-if="instance.status === 'IN_PROGRESS'" class="button button--danger" type="button" @click="cancelOnboarding(instance.id)">{{ t('hr.cancelOnboarding') }}</button></article></div><div v-if="onboardingChecklists.length" class="record-grid"><article v-for="item in onboardingChecklists" :key="item.id"><h4>{{ item.title }}</h4><p>{{ item.roleScope || t('hr.allRoles') }} · v{{ item.version }}</p><StatusBadge :label="item.active ? t('statuses.ACTIVE') : t('statuses.PENDING_REVIEW')" :tone="item.active ? 'success' : 'warning'" /><button v-if="isAdmin && !item.active && item.ownerActorId !== session?.username" class="button button--secondary" type="button" @click="approveChecklist(item)">{{ t('hr.approveChecklist') }}</button></article></div></section>
      </template>
      <ResumeReviewPanel v-if="module === 'hr' && activeTab === 'assessment' && isReviewer" />
      <KnowledgeQualityPanel v-else-if="module === 'knowledge' && activeTab === 'quality'" />
      <SupportReviewPanel v-else-if="module === 'support' && activeTab === 'review'" />
      <ReportEnterprisePanel v-else-if="module === 'report' && (activeTab === 'records' || activeTab === 'schedules')" :tab="reportEnterpriseTab" />
      <DataEnterprisePanel v-else-if="module === 'data' && !isPrimaryTab" :tab="activeTab" />
      <EnterprisePanel v-else-if="!isPrimaryTab && (module !== 'hr' || (activeTab === 'authorization' && isAdmin))" :module="module" :tab="activeTab" />

      <section v-if="module === 'data' && isPrimaryTab && result" class="data-query-flow" aria-live="polite">
        <div class="data-query-flow__heading">
          <div><p class="panel-kicker">{{ t('common.stageReview') }}</p><h3>{{ t('data.candidateStage') }}</h3></div>
          <StatusBadge :label="result.executable ? t('data.guardrailPassed') : t('data.guardrailBlocked')" :tone="result.executable ? 'success' : 'warning'" />
        </div>
        <p v-if="result.summary" class="data-query-flow__summary">{{ result.summary }}</p>
        <SqlPreview :sql="sql" />
        <div v-if="result.validation" class="validation-summary"><strong>{{ t('data.guardrail') }}</strong><span>{{ result.executable ? t('data.guardrailPassed') : t('data.guardrailBlocked') }}</span></div>
        <div v-if="executable" class="data-query-flow__actions">
          <div><strong>{{ t('data.candidateReady') }}</strong><p>{{ t('common.reviewBeforeConfirm') }}</p></div>
          <button class="button button--primary button--large" type="button" @click="confirmAction = 'data-execute'">{{ t('data.confirmQuery') }} <span aria-hidden="true">→</span></button>
        </div>
      </section>

      <section v-if="hasBusinessResult" class="business-result" aria-live="polite">
        <div class="business-result__heading"><div><span class="result-check">✓</span><h3>{{ t('common.processingResult') }}</h3></div><StatusBadge :label="resultStatus" tone="success" /></div>

        <template v-if="module === 'data' && dataExecution"><DataExecutionResult :execution="dataExecution" /></template>
        <template v-else-if="(module === 'knowledge' || (module === 'hr' && hrSection === 'employee')) && result"><div class="answer-content"><h4>{{ t('knowledge.answer') }}</h4><p>{{ result.answer || t('knowledge.noGroundedAnswer') }}</p><p v-if="result.status && result.status !== 'ANSWERED'" class="field-hint">{{ t('knowledge.statusExplanation') }}</p><form v-if="result.answerId" class="feedback-form" @submit.prevent="submitKnowledgeFeedback"><h5>{{ t('knowledge.feedbackTitle') }}</h5><div class="button-row"><button class="button button--secondary" type="button" :class="{ active: knowledgeFeedbackRating === 'HELPFUL' }" @click="knowledgeFeedbackRating = 'HELPFUL'">{{ t('knowledge.helpful') }}</button><button class="button button--secondary" type="button" :class="{ active: knowledgeFeedbackRating === 'NOT_HELPFUL' }" @click="knowledgeFeedbackRating = 'NOT_HELPFUL'">{{ t('knowledge.notHelpful') }}</button></div><label v-if="knowledgeFeedbackRating === 'NOT_HELPFUL'">{{ t('knowledge.feedbackReason') }}<select v-model="knowledgeFeedbackReason"><option value="MISSING_EVIDENCE">MISSING_EVIDENCE</option><option value="INCORRECT">INCORRECT</option><option value="OUTDATED">OUTDATED</option><option value="UNCLEAR">UNCLEAR</option><option value="OTHER">OTHER</option></select></label><label v-if="knowledgeFeedbackRating">{{ t('knowledge.feedbackComment') }}<textarea v-model="knowledgeFeedbackComment" rows="3" maxlength="1000"></textarea></label><button v-if="knowledgeFeedbackRating" class="button button--primary" type="submit">{{ t('knowledge.submitFeedback') }}</button></form></div></template>
        <template v-else-if="module === 'support' && result"><div class="review-editor"><h4>{{ t('support.replyDraft') }}</h4><textarea v-model="supportDraftText" rows="9" :disabled="!result.draft || ['CONFIRMED','CANCELED'].includes(result.status)"></textarea><label>{{ t('support.editReason') }}<input v-model="supportEditReason" maxlength="500" :disabled="!result.draft || ['CONFIRMED','CANCELED'].includes(result.status)"></label><div class="button-row"><button class="button button--secondary" type="button" :disabled="!result.draft || ['CONFIRMED','CANCELED'].includes(result.status)" @click="saveSupportEdit">{{ t('support.saveEdit') }}</button><button class="button button--primary" type="button" :disabled="!result.draft || ['CONFIRMED','CANCELED'].includes(result.status)" @click="confirmAction = 'support-confirm'">{{ t('support.confirmDraft') }}</button><button class="button button--danger" type="button" :disabled="!result.draft || ['CONFIRMED','CANCELED'].includes(result.status)" @click="confirmAction = 'support-cancel'">{{ t('common.cancel') }}</button></div><div v-if="result.status === 'CONFIRMED' && result.draft?.draftId" class="workflow-card"><p>{{ t('support.writebackBoundary') }}</p><div v-if="supportWritebackEligible" class="button-row"><button v-if="!supportWriteback" class="button button--secondary" type="button" @click="prepareSupportWriteback">{{ t('support.prepareWriteback') }}</button><button v-else-if="supportWriteback.status === 'PENDING_CONFIRMATION'" class="button button--danger" type="button" @click="confirmAction = 'support-writeback'">{{ t('support.confirmWriteback') }}</button><button v-else-if="supportWriteback.status !== 'COMPLETED'" class="button button--secondary" type="button" @click="refreshSupportWriteback()">{{ t('support.refreshWriteback') }}</button><StatusBadge :label="supportWriteback?.status ?? t('statuses.PENDING_REVIEW')" :tone="supportWriteback?.status === 'COMPLETED' ? 'success' : supportWriteback?.status === 'UNKNOWN' ? 'danger' : 'warning'" /></div><div v-if="isAdmin && supportWriteback?.status === 'UNKNOWN'" class="primary-workflow-form"><p class="alert alert--warning">{{ t('support.writebackUnknown') }}</p><label>{{ t('support.recoveryEvidence') }}<input v-model="supportRecoveryEvidence" required maxlength="500"></label><div class="button-row"><button class="button button--primary" type="button" :disabled="!supportRecoveryEvidence.trim()" @click="resolveSupportWriteback('COMPLETED')">{{ t('support.markExternallyCompleted') }}</button><button class="button button--secondary" type="button" :disabled="!supportRecoveryEvidence.trim()" @click="resolveSupportWriteback('SAFE_TO_RETRY')">{{ t('support.allowVerifiedRetry') }}</button></div></div><p v-else-if="supportWritebackEligible === false" class="field-hint">{{ t('support.writebackUnavailable') }}</p><small v-if="supportWriteback?.expiresAt" class="field-hint">{{ t('common.tokenExpiry') }}：{{ supportWriteback.expiresAt }}</small></div><p v-if="result.needsHuman" class="alert alert--warning">{{ t('support.needsHumanExplanation') }}</p></div></template>
        <template v-else-if="module === 'report' && result"><div v-if="result.status === 'NEEDS_REVIEW'" class="review-editor"><div class="alert alert--warning">{{ (result.reviewReasons ?? []).join('；') || t('report.needsReview') }}</div><p class="field-hint">{{ t('report.needsReview') }}</p><button class="button button--danger" type="button" @click="confirmAction = 'report-cancel'">{{ t('common.cancel') }}</button></div><div v-else-if="reportContent" class="review-editor"><h4>{{ t('report.reportContent') }}</h4><label>{{ t('report.executiveSummary') }}<textarea v-model="reportContent.executiveSummary" rows="5" :disabled="result.status !== 'DRAFTED'"></textarea><small v-if="reportContent.executiveSummarySourceIds?.length">{{ t('common.evidence') }}: {{ reportContent.executiveSummarySourceIds.join(', ') }}</small></label><div v-if="reportContent.metricHighlights?.length" class="report-edit-section"><h5>{{ t('report.metricHighlights') }}</h5><label v-for="(item, index) in reportContent.metricHighlights" :key="index"><span>{{ item.metricName }}: {{ item.metricValue }} {{ item.unit }}</span><textarea v-model="item.summary" rows="2" :disabled="result.status !== 'DRAFTED'"></textarea><small v-if="item.sourceIds?.length">{{ t('common.evidence') }}: {{ item.sourceIds.join(', ') }}</small></label></div><div v-for="section in ['completedItems','risks','actionItems','suggestions']" :key="section" class="report-edit-section"><h5>{{ t(`report.sections.${section}`) }}</h5><label v-for="(item, index) in reportContent[section] ?? []" :key="index"><textarea v-model="item.text" rows="3" :disabled="result.status !== 'DRAFTED'"></textarea><small v-if="item.sourceIds?.length">{{ t('common.evidence') }}: {{ item.sourceIds.join(', ') }}</small></label></div><div class="button-row"><button class="button button--secondary" type="button" :disabled="result.status !== 'DRAFTED'" @click="saveReportEdit">{{ t('report.saveEdit') }}</button><button class="button button--primary" type="button" :disabled="result.status !== 'DRAFTED'" @click="confirmAction = 'report-confirm'">{{ t('report.confirmReport') }}</button><button class="button button--danger" type="button" :disabled="result.status !== 'DRAFTED'" @click="confirmAction = 'report-cancel'">{{ t('common.cancel') }}</button></div><p class="field-hint">{{ t('report.confirmBoundary') }}</p></div><div v-else class="alert alert--warning">{{ t('report.needsReview') }}</div></template>
        <template v-else-if="module === 'hr' && hrSection === 'recruiting'"><div v-if="jobDraft" class="review-editor"><h4>{{ t('hr.jdDraft') }}</h4><textarea v-model="jobDraft.jdDraft" rows="16"></textarea><div class="button-row"><button class="button button--primary" type="button" @click="extractCriteria">{{ t('hr.extractCriteria') }}</button></div></div><div v-if="criteriaDraft" class="criteria-editor"><h4>{{ t('hr.criteriaReview') }}</h4><label v-for="criterion in criteriaDraft.criteria" :key="criterion.criterionId"><span>{{ criterion.requirementType }} · {{ criterion.category }}</span><textarea v-model="criterion.description" rows="2" :disabled="!criteriaEditable"></textarea></label><div class="button-row"><button class="button button--secondary" type="button" :disabled="!criteriaEditable" @click="saveCriteriaEdits">{{ t('hr.saveCriteriaEdits') }}</button><button class="button button--primary" type="button" :disabled="!criteriaEditable" @click="confirmAction = 'criteria-confirm'">{{ t('hr.confirmCriteria') }}</button></div></div></template>
        <template v-else-if="result"><p class="business-result__summary">{{ result.answer || result.summary || result.message }}</p></template>

        <template v-if="assessment"><div class="assessment-review"><h4>{{ t('hr.assessmentResult') }}</h4><p v-if="assessment.content?.anonymousSummary">{{ assessment.content.anonymousSummary }}</p><ul v-if="assessment.content?.criterionAssessments"><li v-for="item in assessment.content.criterionAssessments" :key="item.criterionId"><strong>{{ item.criterionId }} · {{ item.status }}</strong><span>{{ item.explanation }}</span></li></ul><div v-if="assessment.evidence?.length" class="assessment-evidence"><h5>{{ t('common.evidence') }}</h5><ul><li v-for="item in assessment.evidence.slice(0, 8)" :key="item.evidenceId"><strong>{{ item.evidenceId }} · {{ item.section }}</strong><span>{{ item.sanitizedText }}</span></li></ul></div><div v-if="assessment.reviewReasons?.length" class="alert alert--warning">{{ assessment.reviewReasons.join('；') }}</div><div class="button-row"><button v-if="assessment.status === 'DRAFTED'" class="button button--primary" type="button" @click="confirmAction = 'assessment-review'">{{ t('hr.confirmAssessmentReview') }}</button><button v-if="['DRAFTED','NEEDS_REVIEW'].includes(assessment.status)" class="button button--danger" type="button" @click="confirmAction = 'assessment-cancel'">{{ t('common.cancel') }}</button></div></div></template>

        <details><summary>{{ t('common.technicalDetails') }}</summary><pre class="result-preview result-preview--bounded">{{ safeJson(assessment ?? criteriaDraft ?? result ?? jobDraft) }}</pre></details>
      </section>
      <RequestId :value="requestId" />
    </section>

    <aside class="side-stack">
      <EvidenceList v-if="isPrimaryTab" :items="evidence" />
      <section class="panel"><h2>{{ t('common.risks') }}</h2><p>{{ riskBoundaryText }}</p><ul class="boundary-list"><li><span>✓</span>{{ t('common.trustEvidence') }}</li><li><span>✓</span>{{ t('common.trustControl') }}</li><li><span>✓</span>{{ t('common.trustAudit') }}</li></ul></section>
      <section class="panel next-step"><h2>{{ t('common.nextStep') }}</h2><p>{{ nextStepText }}</p></section>
    </aside>
  </div>

  <ConfirmDialog :open="Boolean(confirmAction)" :operation="confirmMeta.operation" :target="confirmMeta.target" :current-state="confirmMeta.current" :target-state="confirmMeta.targetState" :impact="t('common.confirmImpact')" :recoverable="false" :expires-at="confirmMeta.expiresAt" :risk="t('common.reviewBeforeConfirm')" :busy="loading" @confirm="runConfirmedAction" @cancel="confirmAction = null" />
  <ToastMessage :message="toast" :tone="toastTone" />
</template>
