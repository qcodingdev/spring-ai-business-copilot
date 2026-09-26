<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { api, ApiError } from '@/api/client'
import RequestId from './RequestId.vue'
import StatusBadge from './StatusBadge.vue'
import { formatDate } from '@/locales/format'

/** RUN-06：当前操作者自己的任务运行时间线（只读，最近记录）。 */

interface TaskRun {
  runId: string
  module?: string | null
  refType?: string | null
  refId?: string | null
  status?: string | null
  failureCategory?: string | null
  stopReason?: string | null
  startedAt?: string | null
}

interface RecoveryPlan {
  run: TaskRun
  completedSteps?: Array<{ name?: string | null }>
  retryableSteps?: Array<{ name?: string | null }>
  sourceStatus?: string | null
}

const { t, te, locale } = useI18n()
const runs = ref<TaskRun[]>([])
const recoveryPlans = ref<RecoveryPlan[]>([])
const loading = ref(false)
const available = ref(true)
const requestId = ref<string | null>(null)

function statusTone(status: string | null | undefined): 'success' | 'danger' | 'warning' | 'info' {
  if (status === 'SUCCEEDED') return 'success'
  if (status === 'FAILED' || status === 'CANCELLED' || status === 'BUDGET_EXHAUSTED') return 'danger'
  if (status === 'WAITING_CONFIRMATION' || status === 'OUTCOME_UNKNOWN') return 'warning'
  return 'info'
}

function businessPath(module: string | null | undefined): string | null {
  const paths: Record<string, string> = { data: '/data', knowledge: '/knowledge', support: '/support', report: '/report', resume: '/hr', hr: '/hr' }
  return module ? paths[module] ?? null : null
}

function shortId(runId: string): string {
  return runId.length > 10 ? `${runId.slice(0, 10)}…` : runId
}

function date(value: string | null | undefined): string {
  return value ? (formatDate(value, locale.value) || '—') : '—'
}

async function load(): Promise<void> {
  if (loading.value) return
  loading.value = true
  try {
    const response = await api<TaskRun[]>('/api/task-runs/mine?limit=8')
    runs.value = response.data ?? []
    requestId.value = response.requestId
    try {
      const recoveryResponse = await api<RecoveryPlan[]>('/api/task-runs/mine/recovery')
      recoveryPlans.value = recoveryResponse.data ?? []
      requestId.value = recoveryResponse.requestId ?? requestId.value
    } catch {
      // Keep the ledger usable when an older deployment has not exposed recovery yet.
      recoveryPlans.value = []
    }
    available.value = true
  } catch (error) {
    requestId.value = error instanceof ApiError ? error.requestId : null
    available.value = false
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <section class="panel home-runs" :aria-label="t('home.myTaskRuns.title')" data-testid="my-task-runs">
    <div class="section-heading">
      <div>
        <h2>{{ t('home.myTaskRuns.title') }}</h2>
        <p>{{ t('home.myTaskRuns.description') }}</p>
      </div>
      <button class="button button--secondary" type="button" :disabled="loading" @click="load">
        {{ t('common.refresh') }}
      </button>
    </div>
    <p v-if="!available" class="alert alert--warning" role="status">{{ t('home.myTaskRuns.unavailable') }}</p>
    <div v-if="recoveryPlans.length" class="home-runs__recovery" data-testid="task-run-recovery">
      <div>
        <strong>{{ t('home.myTaskRuns.recoveryTitle') }}</strong>
        <p>{{ t('home.myTaskRuns.recoveryDescription') }}</p>
      </div>
      <article v-for="plan in recoveryPlans" :key="plan.run.runId">
        <div>
          <StatusBadge :label="plan.run.status && te(`statuses.${plan.run.status}`) ? t(`statuses.${plan.run.status}`) : (plan.run.status || '—')" :tone="statusTone(plan.run.status)" />
          <small>{{ plan.run.module }} · {{ plan.run.refType || '—' }} / {{ plan.run.refId || '—' }}</small>
        </div>
        <p v-if="plan.run.status === 'OUTCOME_UNKNOWN'">{{ t('home.myTaskRuns.unknownOutcome') }}</p>
        <p v-else>{{ t('home.myTaskRuns.waitingConfirmation') }}</p>
        <RouterLink v-if="businessPath(plan.run.module)" :to="businessPath(plan.run.module)!">{{ t('home.myTaskRuns.openBusiness') }}</RouterLink>
      </article>
    </div>
    <div class="table-scroll">
      <table class="data-table">
        <thead>
          <tr>
            <th>{{ t('home.myTaskRuns.run') }}</th>
            <th>{{ t('home.myTaskRuns.target') }}</th>
            <th>{{ t('common.status') }}</th>
            <th>{{ t('admin.taskRuns.failureCategory') }}</th>
            <th>{{ t('admin.taskRuns.startedAt') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="run in runs" :key="run.runId">
            <td><code :title="run.runId">{{ shortId(run.runId) }}</code><small>{{ run.module }}</small></td>
            <td>{{ run.refType || '—' }} / {{ run.refId || '—' }}</td>
            <td>
              <StatusBadge :label="run.status && te(`statuses.${run.status}`) ? t(`statuses.${run.status}`) : (run.status || '—')" :tone="statusTone(run.status)" />
              <small v-if="run.stopReason" class="home-runs__reason">{{ run.stopReason }}</small>
              <RouterLink v-if="run.status !== 'SUCCEEDED' && businessPath(run.module)" :to="businessPath(run.module)!">
                {{ t('home.myTaskRuns.openBusiness') }}
              </RouterLink>
            </td>
            <td>{{ run.failureCategory || '—' }}</td>
            <td>{{ date(run.startedAt) }}</td>
          </tr>
          <tr v-if="!runs.length && !loading && available">
            <td colspan="5" class="empty-state">{{ t('home.myTaskRuns.empty') }}</td>
          </tr>
        </tbody>
      </table>
    </div>
    <RequestId v-if="!available" :value="requestId" />
  </section>
</template>

<style scoped>
.home-runs {
  margin-top: var(--space-5);
}
.home-runs__reason {
  display: block;
  color: var(--muted, #5b6478);
}
.home-runs__recovery {
  display: grid;
  gap: .7rem;
  margin: 1rem 0;
  padding: 1rem;
  border: 1px solid #e8c979;
  border-radius: .75rem;
  background: #fffaf0;
}
.home-runs__recovery > div:first-child p { margin: .25rem 0 0; color: var(--muted, #5b6478); }
.home-runs__recovery article { display: flex; gap: .75rem; align-items: center; justify-content: space-between; flex-wrap: wrap; padding-top: .65rem; border-top: 1px solid #f0dfaa; }
.home-runs__recovery article > div { display: grid; gap: .25rem; }
.home-runs__recovery article small, .home-runs__recovery article p { margin: 0; color: var(--muted, #5b6478); }
.data-table small {
  display: block;
  color: var(--muted, #5b6478);
}
</style>
