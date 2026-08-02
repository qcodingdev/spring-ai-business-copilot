<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { api, ApiError, jsonBody } from '@/api/client'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import EvidenceList from '@/components/EvidenceList.vue'
import PageHeader from '@/components/PageHeader.vue'
import RequestId from '@/components/RequestId.vue'
import SqlPreview from '@/components/SqlPreview.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import EnterprisePanel from '@/components/EnterprisePanel.vue'
import ModuleIcon from '@/components/ModuleIcon.vue'
import { safeJson } from '@/utils/safeDisplay'

type ModuleKey = 'data' | 'knowledge' | 'support' | 'report' | 'hr'
const props = defineProps<{ module: ModuleKey }>()
const { t, te } = useI18n()
const input = ref('')
const secondary = ref('')
const activeTab = ref('')
const loading = ref(false)
const result = ref<Record<string, any> | null>(null)
const errorCode = ref('')
const requestId = ref<string | null>(null)
const confirmOpen = ref(false)

const config = computed(() => {
  const definitions = {
    data: { tabs: ['query', 'governance', 'records', 'handoff'], field: 'question', placeholder: 'questionPlaceholder', action: 'generate' },
    knowledge: { tabs: ['qa', 'documents', 'sources', 'quality'], field: 'question', placeholder: 'questionPlaceholder', action: 'ask' },
    support: { tabs: ['tickets', 'review', 'connections', 'quality'], field: 'ticket', placeholder: 'ticketPlaceholder', action: 'analyze' },
    report: { tabs: ['generate', 'records', 'sources', 'schedules'], field: 'source', placeholder: 'sourcePlaceholder', action: 'generate' },
    hr: { tabs: ['criteria', 'assessment', 'interview', 'authorization', 'employeeQa', 'onboarding'], field: 'requirements', placeholder: 'requirementsPlaceholder', action: 'draft' },
  } as const
  return definitions[props.module]
})

watch(() => props.module, () => {
  activeTab.value = config.value.tabs[0]
  input.value = ''
  secondary.value = ''
  result.value = null
}, { immediate: true })

const evidence = computed(() => {
  const value = result.value
  if (!value) return []
  const candidates = [value.evidence, value.citations, value.assumptions, value.warnings, value.criteria]
  return candidates.flatMap((item) => Array.isArray(item) ? item : []).filter(Boolean)
})
const sql = computed(() => props.module === 'data' ? String(result.value?.sql ?? '') : '')
const executable = computed(() => props.module === 'data' && result.value?.executable === true)
const errorMessage = computed(() => errorCode.value ? t(`errors.${te(`errors.${errorCode.value}`) ? errorCode.value : 'generic'}`) : '')
const quickPrompts = computed(() => [
  t(`${props.module}.examples.first`),
  t(`${props.module}.examples.second`),
  t(`${props.module}.examples.third`),
])
const resultSummary = computed(() => {
  const value = result.value
  if (!value) return ''
  const execution = value.execution as Record<string, any> | undefined
  const executionResult = execution?.result as Record<string, any> | undefined
  const candidates = [value.answer, value.summary, value.message, executionResult?.summary, value.title]
  return String(candidates.find((item) => typeof item === 'string' && item.trim()) ?? '')
})
const resultStatus = computed(() => {
  const status = String(result.value?.status ?? '')
  return status && te(`statuses.${status}`) ? t(`statuses.${status}`) : t('common.operationSucceeded')
})

function usePrompt(prompt: string): void {
  input.value = prompt
}

function payload(): { path: string; body: unknown } {
  const today = new Date()
  const end = today.toISOString().slice(0, 10)
  const startDate = new Date(today)
  startDate.setDate(today.getDate() - 7)
  const start = startDate.toISOString().slice(0, 10)
  switch (props.module) {
    case 'data': return { path: '/api/data-copilot/sql-candidates', body: { question: input.value } }
    case 'knowledge': return { path: '/api/knowledge-copilot/questions', body: { question: input.value, category: null } }
    case 'support': return { path: '/api/support-copilot/tickets/analyze', body: { customerMessage: input.value, channel: 'workbench' } }
    case 'report': return {
      path: '/api/report-copilot/reports/generate',
      body: {
        reportType: 'TEAM_WEEKLY',
        period: { periodStart: start, periodEnd: end },
        title: secondary.value || (t('report.reportTitle') as string),
        metrics: [],
        tasks: [],
        meetingNotes: [{ title: secondary.value || 'Workbench source', content: input.value, recordedAt: new Date().toISOString() }],
        importedSources: [],
        templateId: null,
        templateVersion: null,
      },
    }
    case 'hr': return {
      path: '/api/resume-copilot/jobs/draft',
      body: { title: secondary.value, requirements: input.value },
    }
  }
}

async function submit(): Promise<void> {
  loading.value = true
  errorCode.value = ''
  result.value = null
  try {
    const request = payload()
    const response = await api<Record<string, any>>(request.path, { method: 'POST', ...jsonBody(request.body) })
    result.value = response.data
    requestId.value = response.requestId ?? response.data.requestId ?? null
  } catch (error) {
    if (error instanceof ApiError) {
      errorCode.value = error.errorCode
      requestId.value = error.requestId
    } else {
      errorCode.value = 'generic'
    }
  } finally {
    loading.value = false
  }
}

async function executeData(): Promise<void> {
  if (!result.value?.candidateId || !result.value?.confirmationToken) return
  loading.value = true
  try {
    const response = await api<Record<string, any>>(
      `/api/data-copilot/sql-candidates/${encodeURIComponent(result.value.candidateId)}/execute`,
      { method: 'POST', ...jsonBody({ confirmationToken: result.value.confirmationToken }) },
    )
    result.value = { ...result.value, execution: response.data, status: 'COMPLETED' }
    requestId.value = response.requestId ?? requestId.value
    confirmOpen.value = false
  } catch (error) {
    errorCode.value = error instanceof ApiError ? error.errorCode : 'generic'
    requestId.value = error instanceof ApiError ? error.requestId : requestId.value
    confirmOpen.value = false
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <PageHeader :title="t(`${module}.title`)" :description="t(`${module}.description`)" :status="t('common.workspaceEyebrow')">
    <span class="page-module-icon"><ModuleIcon :name="module" /></span>
    <StatusBadge :label="t('statuses.ACTIVE')" tone="success" />
  </PageHeader>

  <div v-if="module !== 'hr'" class="tabs" role="tablist" :aria-label="t(`${module}.title`)">
    <button
      v-for="tab in config.tabs"
      :key="tab"
      role="tab"
      :aria-selected="activeTab === tab"
      :class="{ active: activeTab === tab }"
      @click="activeTab = tab"
    >{{ t(`${module}.tabs.${tab}`) }}</button>
  </div>
  <div v-else class="hr-workspace-tabs">
    <div class="tab-group">
      <span>{{ t('hr.sections.recruiting') }}</span>
      <div class="tabs" role="tablist" :aria-label="t('hr.sections.recruiting')">
        <button
          v-for="tab in config.tabs.slice(0, 4)"
          :key="tab"
          role="tab"
          :aria-selected="activeTab === tab"
          :class="{ active: activeTab === tab }"
          @click="activeTab = tab"
        >{{ t(`hr.tabs.${tab}`) }}</button>
      </div>
    </div>
    <div class="tab-group">
      <span>{{ t('hr.sections.employee') }}</span>
      <div class="tabs" role="tablist" :aria-label="t('hr.sections.employee')">
        <button
          v-for="tab in config.tabs.slice(4)"
          :key="tab"
          role="tab"
          :aria-selected="activeTab === tab"
          :class="{ active: activeTab === tab }"
          @click="activeTab = tab"
        >{{ t(`hr.tabs.${tab}`) }}</button>
      </div>
    </div>
  </div>

  <div class="workflow-grid">
    <section class="panel task-panel" :class="`task-panel--${module}`">
      <div class="task-panel__heading">
        <div><p class="panel-kicker">{{ t('common.currentTask') }}</p><h2>{{ t(`${module}.tabs.${activeTab}`) }}</h2></div>
        <div v-if="activeTab === config.tabs[0]" class="workflow-stages" :aria-label="t('common.taskFlow')" tabindex="0">
          <span class="active"><b>1</b>{{ t('common.stageInput') }}</span>
          <span :class="{ active: result }"><b>2</b>{{ t('common.stageReview') }}</span>
          <span :class="{ active: executable }"><b>3</b>{{ t('common.stageConfirm') }}</span>
        </div>
      </div>
      <form v-if="activeTab === config.tabs[0]" class="primary-workflow-form" @submit.prevent="submit">
        <label v-if="module === 'hr' || module === 'report'">
          {{ module === 'hr' ? t('hr.jobTitle') : t('report.reportTitle') }}
          <input v-model="secondary" required maxlength="300" />
        </label>
        <label>
          {{ t(`${module}.${config.field}`) }}
          <textarea v-model="input" :placeholder="t(`${module}.${config.placeholder}`)" required maxlength="4000" rows="8" />
        </label>
        <div class="quick-start">
          <div><strong>{{ t('common.quickStart') }}</strong><span>{{ t('common.chooseExample') }}</span></div>
          <div class="prompt-chips">
            <button v-for="prompt in quickPrompts" :key="prompt" type="button" @click="usePrompt(prompt)">{{ prompt }}</button>
          </div>
        </div>
        <div class="form-actions">
          <button class="button button--primary button--large" type="submit" :disabled="loading">
            {{ loading ? t('common.loading') : t(`${module}.${config.action}`) }} <span aria-hidden="true">→</span>
          </button>
          <small>{{ t('common.reviewBeforeConfirm') }}</small>
        </div>
      </form>
      <EnterprisePanel v-else :module="module" :tab="activeTab" />
      <section v-if="result" class="business-result" aria-live="polite">
        <div class="business-result__heading">
          <div><span class="result-check">✓</span><h3>{{ t('common.processingResult') }}</h3></div>
          <StatusBadge :label="resultStatus" tone="success" />
        </div>
        <p v-if="resultSummary" class="business-result__summary">{{ resultSummary }}</p>
        <details>
          <summary>{{ t('common.technicalDetails') }}</summary>
          <pre class="result-preview">{{ safeJson(result) }}</pre>
        </details>
      </section>
      <div v-if="errorMessage" class="alert alert--danger" role="alert">{{ errorMessage }}</div>
      <RequestId :value="requestId" />
    </section>

    <aside class="side-stack">
      <SqlPreview v-if="module === 'data'" :sql="sql" />
      <EvidenceList :items="evidence" />
      <section class="panel">
        <h2>{{ t('common.risks') }}</h2>
        <p>{{ t('common.reviewBeforeConfirm') }}</p>
        <ul class="boundary-list">
          <li><span>✓</span>{{ t('common.trustEvidence') }}</li>
          <li><span>✓</span>{{ t('common.trustControl') }}</li>
          <li><span>✓</span>{{ t('common.trustAudit') }}</li>
        </ul>
      </section>
      <section class="panel next-step">
        <h2>{{ t('common.nextStep') }}</h2>
        <p>{{ t(`${module}.next`) }}</p>
        <button v-if="executable" class="button button--danger" type="button" @click="confirmOpen = true">
          {{ t('data.execute') }}
        </button>
      </section>
    </aside>
  </div>

  <ConfirmDialog
    :open="confirmOpen"
    :operation="t('data.execute')"
    :target="String(result?.candidateId ?? '')"
    current-state="DRAFTED"
    target-state="COMPLETED"
    :impact="t('data.description')"
    :recoverable="false"
    :expires-at="result?.expiresAt"
    :risk="t('common.reviewBeforeConfirm')"
    :busy="loading"
    @confirm="executeData"
    @cancel="confirmOpen = false"
  />
</template>
