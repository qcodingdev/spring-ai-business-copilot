<script setup lang="ts">
import { onMounted, ref } from 'vue'
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

const { t, te, locale } = useI18n()
const runs = ref<TaskRun[]>([])
const loading = ref(false)
const available = ref(true)
const requestId = ref<string | null>(null)

function statusTone(status: string | null | undefined): 'success' | 'danger' | 'warning' | 'info' {
  if (status === 'SUCCEEDED') return 'success'
  if (status === 'FAILED' || status === 'CANCELLED' || status === 'BUDGET_EXHAUSTED') return 'danger'
  if (status === 'WAITING_CONFIRMATION' || status === 'OUTCOME_UNKNOWN') return 'warning'
  return 'info'
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
.data-table small {
  display: block;
  color: var(--muted, #5b6478);
}
</style>
