<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { api, ApiError, jsonBody } from '@/api/client'
import RequestId from './RequestId.vue'
import StatusBadge from './StatusBadge.vue'
import { formatDate } from '@/locales/format'

type Category = 'RUNTIME_READINESS' | 'MODEL_QUALITY' | 'VENDOR_ACCEPTANCE' | 'RELEASE_GATE'
type Status = 'PASS' | 'ATTENTION' | 'FAILED' | 'NOT_VERIFIED'
interface Summary { category: Category; status: Status; evidenceCount: number }
interface Evidence { id: number; category: Category; name: string; status: Status; source: string; applicableVersion: string; recordedBy: string; recordedAt: string; note?: string }
interface Assessment { applicableVersion: string; categories: Summary[]; releaseReadiness: { releasable: boolean; overall: Status } }

const { t, locale } = useI18n()
const assessment = ref<Assessment | null>(null)
const history = ref<Evidence[]>([])
const category = ref<Category>('MODEL_QUALITY')
const categories: Category[] = ['RUNTIME_READINESS', 'MODEL_QUALITY', 'VENDOR_ACCEPTANCE', 'RELEASE_GATE']
const statuses: Status[] = ['NOT_VERIFIED', 'PASS', 'ATTENTION', 'FAILED']
const status = ref<Status>('NOT_VERIFIED')
const name = ref('')
const source = ref('')
const note = ref('')
const acknowledged = ref(false)
const loading = ref(false)
const error = ref('')
const notice = ref('')
const requestId = ref<string | null>(null)
const suites = [
  { key: 'firstForty', category: 'MODEL_QUALITY' as Category, evidenceName: 'evaluation-harness-first-40' },
  { key: 'crossModule', category: 'MODEL_QUALITY' as Category, evidenceName: 'evaluation-harness-cross-module' },
  { key: 'releaseRegression', category: 'RELEASE_GATE' as Category, evidenceName: 'release-full-regression' },
]

function tone(value: Status): 'success' | 'warning' | 'danger' | 'info' {
  return value === 'PASS' ? 'success' : value === 'FAILED' ? 'danger' : value === 'ATTENTION' ? 'warning' : 'info'
}

function date(value: string | null | undefined): string {
  return value ? (formatDate(value, locale.value) || '—') : '—'
}

function showError(cause: unknown): void {
  requestId.value = cause instanceof ApiError ? cause.requestId : null
  error.value = t('admin.acceptance.loadFailed')
}

async function loadHistory(): Promise<void> {
  history.value = []
  const response = await api<Evidence[]>(`/api/admin/acceptance-evidence/${category.value}`)
  history.value = response.data
  requestId.value = response.requestId
}

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  assessment.value = null
  history.value = []
  try {
    const response = await api<Assessment>('/api/admin/acceptance-evidence')
    assessment.value = response.data
    requestId.value = response.requestId
    await loadHistory()
  } catch (cause) {
    assessment.value = null
    showError(cause)
  } finally {
    loading.value = false
  }
}

async function changeCategory(): Promise<void> {
  loading.value = true
  error.value = ''
  try { await loadHistory() } catch (cause) { showError(cause) } finally { loading.value = false }
}

async function prepareSuite(suite: typeof suites[number]): Promise<void> {
  if (loading.value) return
  category.value = suite.category
  name.value = suite.evidenceName
  status.value = 'NOT_VERIFIED'
  source.value = ''
  note.value = ''
  acknowledged.value = false
  await changeCategory()
  document.getElementById('acceptance-evidence-form')?.scrollIntoView({ block: 'start' })
}

async function save(sync = false): Promise<void> {
  if (loading.value || !assessment.value) return
  if (!sync && (category.value === 'RUNTIME_READINESS' || !acknowledged.value || !name.value.trim() || !source.value.trim())) return
  loading.value = true
  error.value = ''
  notice.value = ''
  try {
    const response = await api<Evidence>(sync
      ? '/api/admin/acceptance-evidence/runtime-readiness/sync'
      : '/api/admin/acceptance-evidence', {
      method: 'POST',
      ...(sync ? {} : jsonBody({ category: category.value, name: name.value.trim(), status: status.value,
        source: source.value.trim(), applicableVersion: assessment.value.applicableVersion, note: note.value.trim() })),
    })
    requestId.value = response.requestId
    acknowledged.value = false
    notice.value = t('admin.acceptance.saved')
    await load()
  } catch (cause) {
    // 写入后断连时不声称成功；先重新查看历史，避免盲目重复登记。
    notice.value = ''
    error.value = t('admin.acceptance.saveFailed')
    requestId.value = cause instanceof ApiError ? cause.requestId : null
    assessment.value = null
  } finally {
    loading.value = false
  }
}

defineExpose({ load })
onMounted(load)
</script>

<template>
  <div class="admin-content" aria-live="polite">
    <section class="panel admin-section">
      <div class="section-heading"><div><h2>{{ t('admin.acceptance.title') }}</h2><p>{{ t('admin.acceptance.boundary') }}</p></div></div>
      <p v-if="error" class="alert alert--danger" role="alert">{{ error }}</p>
      <p v-if="notice" class="alert alert--success">{{ notice }}</p>
      <template v-if="assessment">
        <p>{{ t('admin.acceptance.version', { version: assessment.applicableVersion }) }}</p>
        <p>{{ t(assessment.releaseReadiness.releasable ? 'admin.acceptance.complete' : 'admin.acceptance.incomplete') }}</p>
        <div class="status-grid">
          <div v-for="item in assessment.categories" :key="item.category">
            <span>{{ t(`admin.acceptance.categories.${item.category}`) }}</span>
            <StatusBadge :label="t(`admin.acceptance.statuses.${item.status}`)" :tone="tone(item.status)" />
            <small>{{ t('admin.acceptance.checkCount', { count: item.evidenceCount }) }}</small>
          </div>
        </div>
        <button class="button button--secondary" type="button" :disabled="loading" @click="save(true)">{{ t('admin.acceptance.sync') }}</button>
      </template>
    </section>

    <section class="panel admin-section">
      <div class="section-heading"><div><h2>{{ t('admin.acceptance.suiteTitle') }}</h2><p>{{ t('admin.acceptance.suiteBoundary') }}</p></div></div>
      <div class="record-grid acceptance-suite-grid">
        <article v-for="suite in suites" :key="suite.key">
          <h3>{{ t(`admin.acceptance.suites.${suite.key}.title`) }}</h3>
          <p>{{ t(`admin.acceptance.suites.${suite.key}.description`) }}</p>
          <code>{{ suite.evidenceName }}</code>
          <button class="button button--secondary" type="button" :disabled="loading" @click="prepareSuite(suite)">{{ t('admin.acceptance.prepareReport') }}</button>
        </article>
      </div>
    </section>

    <section id="acceptance-evidence-form" class="panel admin-section">
      <h2>{{ t('admin.acceptance.recordTitle') }}</h2>
      <p>{{ t('admin.acceptance.recordBoundary') }}</p>
      <label>{{ t('admin.acceptance.category') }}<select v-model="category" :disabled="loading" @change="changeCategory"><option v-for="value in categories" :key="value" :value="value">{{ t(`admin.acceptance.categories.${value}`) }}</option></select></label>
      <form v-if="category !== 'RUNTIME_READINESS'" @submit.prevent="save()">
        <label>{{ t('admin.acceptance.name') }}<input v-model="name" required maxlength="100" :disabled="loading"></label>
        <label>{{ t('common.status') }}<select v-model="status" :disabled="loading"><option v-for="value in statuses" :key="value" :value="value">{{ t(`admin.acceptance.statuses.${value}`) }}</option></select></label>
        <label>{{ t('admin.acceptance.source') }}<input v-model="source" required maxlength="200" :disabled="loading"></label>
        <label>{{ t('admin.acceptance.note') }}<textarea v-model="note" maxlength="1000" :disabled="loading" /></label>
        <label class="checkbox-label"><input v-model="acknowledged" type="checkbox" :disabled="loading">{{ t('admin.acceptance.acknowledge') }}</label>
        <button class="button button--primary" type="submit" :disabled="loading || !assessment || !acknowledged || !name.trim() || !source.trim()">{{ t('admin.acceptance.record') }}</button>
      </form>
      <p v-else>{{ t('admin.acceptance.runtimeOnly') }}</p>
    </section>

    <section class="panel admin-section">
      <h2>{{ t('admin.acceptance.history') }}</h2>
      <p>{{ t('admin.acceptance.historyBoundary') }}</p>
      <div class="table-scroll" tabindex="0" role="region" :aria-label="t('admin.acceptance.history')"><table class="data-table"><thead><tr><th>{{ t('admin.acceptance.name') }}</th><th>{{ t('common.status') }}</th><th>{{ t('admin.acceptance.source') }}</th><th>{{ t('admin.requestedBy') }}</th><th>{{ t('admin.createdAt') }}</th></tr></thead><tbody>
        <tr v-for="item in history" :key="item.id"><td>{{ item.name }}<small>{{ item.applicableVersion }}</small><small>{{ item.note }}</small></td><td><StatusBadge :label="t(`admin.acceptance.statuses.${item.status}`)" :tone="tone(item.status)" /></td><td>{{ item.source }}</td><td>{{ item.recordedBy }}</td><td>{{ date(item.recordedAt) }}</td></tr>
        <tr v-if="!history.length"><td colspan="5" class="empty-state">{{ t('common.noData') }}</td></tr>
      </tbody></table></div>
    </section>
    <RequestId :value="requestId" />
  </div>
</template>
