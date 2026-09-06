<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { api, ApiError } from '@/api/client'
import RequestId from './RequestId.vue'
import StatusBadge from './StatusBadge.vue'
import ToastMessage from './ToastMessage.vue'
import { formatDate } from '@/locales/format'

/** 管理端任务运行视图（RUN-06）：运行列表 + 单运行时间线（步骤/尝试/失败类别/停止原因）。 */

interface TaskRun {
  runId: string
  module?: string | null
  refType?: string | null
  refId?: string | null
  ownerActorId?: string | null
  status?: string | null
  failureCategory?: string | null
  stopReason?: string | null
  startedAt?: string | null
  endedAt?: string | null
}

interface TaskStepView {
  stepId: string
  name?: string | null
  status?: string | null
  attemptCount?: number | null
  failureCategory?: string | null
  evidenceRefs?: string[] | null
  summary?: string | null
}

interface TaskAttemptView {
  stepId?: string | null
  kind?: string | null
  operation?: string | null
  provider?: string | null
  model?: string | null
  inputTokens?: number | null
  outputTokens?: number | null
  latencyMs?: number | null
  outcome?: string | null
  failureCategory?: string | null
}

interface Timeline {
  run: TaskRun
  steps: TaskStepView[]
  attempts: TaskAttemptView[]
}

const { t, te, locale } = useI18n()
const runs = ref<TaskRun[]>([])
const statusFilter = ref('')
const loading = ref(false)
const timelineLoading = ref(false)
const selectedRun = ref<TaskRun | null>(null)
const timeline = ref<Timeline | null>(null)
const requestId = ref<string | null>(null)
const toast = ref('')
const toastTone = ref<'success' | 'danger' | 'info'>('info')
let toastTimer: ReturnType<typeof setTimeout> | undefined

const filters = computed(() => ['', 'WAITING_CONFIRMATION', 'RUNNING', 'FAILED', 'BUDGET_EXHAUSTED', 'OUTCOME_UNKNOWN'])

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

function statusTone(status: string | null | undefined): 'success' | 'danger' | 'warning' | 'info' {
  if (status === 'SUCCEEDED') return 'success'
  if (status === 'FAILED' || status === 'CANCELLED' || status === 'BUDGET_EXHAUSTED') return 'danger'
  if (status === 'WAITING_CONFIRMATION' || status === 'OUTCOME_UNKNOWN') return 'warning'
  return 'info'
}

function shortId(runId: string): string {
  return runId.length > 12 ? `${runId.slice(0, 12)}…` : runId
}

function date(value: string | null | undefined): string {
  return value ? (formatDate(value, locale.value) || '—') : '—'
}

async function load(): Promise<void> {
  loading.value = true
  try {
    const query = statusFilter.value ? `?status=${encodeURIComponent(statusFilter.value)}` : ''
    const response = await api<TaskRun[]>(`/api/admin/task-runs${query}`)
    runs.value = response.data ?? []
    requestId.value = response.requestId
  } catch (error) {
    requestId.value = error instanceof ApiError ? error.requestId : null
    showToast(errorText(error), 'danger')
  } finally {
    loading.value = false
  }
}

async function openTimeline(run: TaskRun): Promise<void> {
  selectedRun.value = run
  timeline.value = null
  timelineLoading.value = true
  try {
    const response = await api<Timeline>(`/api/admin/task-runs/${encodeURIComponent(run.runId)}/timeline`)
    timeline.value = response.data
    requestId.value = response.requestId
  } catch (error) {
    requestId.value = error instanceof ApiError ? error.requestId : null
    showToast(errorText(error), 'danger')
  } finally {
    timelineLoading.value = false
  }
}

function evidenceList(step: TaskStepView): string {
  return (step.evidenceRefs ?? []).join('、')
}

onMounted(load)
</script>

<template>
  <section class="panel admin-section" :aria-label="t('admin.taskRuns.title')">
    <div class="section-heading">
      <div>
        <h2>{{ t('admin.taskRuns.title') }}</h2>
        <p>{{ t('admin.taskRuns.description') }}</p>
      </div>
      <label class="task-run-filter">
        <span>{{ t('admin.taskRuns.statusFilter') }}</span>
        <select v-model="statusFilter" data-testid="task-run-status-filter" @change="load">
          <option value="">{{ t('admin.taskRuns.allStatuses') }}</option>
          <option v-for="filter in filters" :key="filter" :value="filter">{{ filter }}</option>
        </select>
      </label>
      <button class="button button--secondary" type="button" :disabled="loading" @click="load">
        {{ t('common.refresh') }}
      </button>
    </div>

    <div class="table-scroll">
      <table class="data-table" data-testid="task-runs-table">
        <thead>
          <tr>
            <th>{{ t('admin.taskRuns.runId') }}</th>
            <th>{{ t('admin.taskRuns.module') }}</th>
            <th>{{ t('admin.taskRuns.target') }}</th>
            <th>{{ t('admin.taskRuns.owner') }}</th>
            <th>{{ t('common.status') }}</th>
            <th>{{ t('admin.taskRuns.failureCategory') }}</th>
            <th>{{ t('admin.taskRuns.startedAt') }}</th>
            <th><span class="visually-hidden">{{ t('admin.taskRuns.timeline') }}</span></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="run in runs" :key="run.runId">
            <td><code :title="run.runId">{{ shortId(run.runId) }}</code></td>
            <td>{{ run.module || '—' }}</td>
            <td>{{ run.refType || '—' }} / {{ run.refId || '—' }}</td>
            <td>{{ run.ownerActorId || '—' }}</td>
            <td><StatusBadge :label="run.status || '—'" :tone="statusTone(run.status)" /></td>
            <td>{{ run.failureCategory || '—' }}</td>
            <td>{{ date(run.startedAt) }}</td>
            <td>
              <button class="button button--secondary" type="button" :disabled="timelineLoading" @click="openTimeline(run)">
                {{ t('admin.taskRuns.timeline') }}
              </button>
            </td>
          </tr>
          <tr v-if="!runs.length && !loading">
            <td colspan="8" class="empty-state">{{ t('admin.taskRuns.empty') }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <div v-if="selectedRun" class="task-run-timeline" data-testid="task-run-timeline">
      <h3>{{ t('admin.taskRuns.timelineTitle') }} <code>{{ shortId(selectedRun.runId) }}</code></h3>
      <p v-if="timelineLoading" class="field-hint">{{ t('common.loading') }}</p>
      <template v-else-if="timeline">
        <p v-if="timeline.run?.stopReason" class="field-hint">
          {{ t('admin.taskRuns.stopReason') }}: {{ timeline.run.stopReason }}
        </p>
        <h4>{{ t('admin.taskRuns.steps') }}</h4>
        <div class="table-scroll">
          <table class="data-table">
            <thead>
              <tr>
                <th>{{ t('admin.taskRuns.stepName') }}</th>
                <th>{{ t('common.status') }}</th>
                <th>{{ t('admin.taskRuns.attempts') }}</th>
                <th>{{ t('admin.taskRuns.failureCategory') }}</th>
                <th>{{ t('admin.taskRuns.evidence') }}</th>
                <th>{{ t('admin.taskRuns.summary') }}</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="step in timeline.steps" :key="step.stepId">
                <td>{{ step.name || step.stepId }}</td>
                <td><StatusBadge :label="step.status || '—'" :tone="statusTone(step.status)" /></td>
                <td>{{ step.attemptCount ?? 0 }}</td>
                <td>{{ step.failureCategory || '—' }}</td>
                <td>{{ evidenceList(step) || '—' }}</td>
                <td>{{ step.summary || '—' }}</td>
              </tr>
              <tr v-if="!timeline.steps.length">
                <td colspan="6" class="empty-state">{{ t('common.noData') }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <h4>{{ t('admin.taskRuns.attempts') }}</h4>
        <div class="table-scroll">
          <table class="data-table">
            <thead>
              <tr>
                <th>{{ t('admin.taskRuns.kind') }}</th>
                <th>{{ t('admin.taskRuns.operation') }}</th>
                <th>{{ t('admin.taskRuns.model') }}</th>
                <th>{{ t('admin.taskRuns.tokens') }}</th>
                <th>{{ t('admin.taskRuns.latency') }}</th>
                <th>{{ t('admin.taskRuns.outcome') }}</th>
                <th>{{ t('admin.taskRuns.failureCategory') }}</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(attempt, index) in timeline.attempts" :key="index">
                <td>{{ attempt.kind || '—' }}</td>
                <td>{{ attempt.operation || '—' }}</td>
                <td>{{ [attempt.provider, attempt.model].filter(Boolean).join(' / ') || '—' }}</td>
                <td>{{ (attempt.inputTokens ?? '—') + ' + ' + (attempt.outputTokens ?? '—') }}</td>
                <td>{{ attempt.latencyMs != null ? `${attempt.latencyMs} ms` : '—' }}</td>
                <td>
                  <StatusBadge
                    :label="attempt.outcome || '—'"
                    :tone="attempt.outcome === 'SUCCESS' ? 'success' : attempt.outcome === 'FAILURE' ? 'danger' : 'warning'"
                  />
                </td>
                <td>{{ attempt.failureCategory || '—' }}</td>
              </tr>
              <tr v-if="!timeline.attempts.length">
                <td colspan="7" class="empty-state">{{ t('common.noData') }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </template>
    </div>
    <RequestId :value="requestId" />
    <ToastMessage :message="toast" :tone="toastTone" />
  </section>
</template>

<style scoped>
.task-run-filter {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
}
.task-run-filter select {
  min-height: 2.2rem;
  border: 1px solid var(--border, #d7dbe7);
  border-radius: var(--radius-sm, 8px);
  background: var(--surface, #fff);
}
.task-run-timeline {
  margin-top: var(--space-5);
  display: grid;
  gap: var(--space-3);
}
.task-run-timeline h4 {
  margin: var(--space-2) 0 0;
}
</style>
