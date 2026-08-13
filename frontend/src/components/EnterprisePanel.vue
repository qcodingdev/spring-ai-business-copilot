<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { api, ApiError, jsonBody } from '@/api/client'
import { useSession } from '@/composables/useSession'
import RequestId from './RequestId.vue'
import ConfirmDialog from './ConfirmDialog.vue'
import { safeJson } from '@/utils/safeDisplay'
import ToastMessage from './ToastMessage.vue'

type ModuleKey = 'data' | 'knowledge' | 'support' | 'report' | 'hr'
const props = defineProps<{ module: ModuleKey; tab: string }>()
const { t, te } = useI18n()
const { isAdmin } = useSession()
const loading = ref(false)
const data = ref<unknown>(null)
const errorCode = ref('')
const requestId = ref<string | null>(null)
const connectionKey = ref('')
const displayName = ref('')
const provider = ref('')
const baseUrl = ref('')
const secretRef = ref('')
const rootReference = ref('')
const defaultVisibility = ref<'ALL' | 'HR_REVIEWER' | 'ADMIN'>('ADMIN')
const enabled = ref(true)
const resourceId = ref('')
const actionValue = ref('')
const actionConfirmOpen = ref(false)
const toast = ref('')
const toastTone = ref<'success' | 'danger'>('success')
let toastTimer: ReturnType<typeof setTimeout> | undefined

function showToast(message: string, tone: 'success' | 'danger' = 'success'): void {
  if (toastTimer) clearTimeout(toastTimer)
  toast.value = message
  toastTone.value = tone
  toastTimer = setTimeout(() => { toast.value = '' }, 4500)
}

const readEndpoint = computed(() => {
  const endpoints: Record<ModuleKey, Record<string, string>> = {
    data: {
      governance: '/api/data-copilot/metrics',
      records: '/api/data-copilot/audit-logs',
      handoff: '/api/data-copilot/datasource-health',
    },
    knowledge: {
      documents: '/api/knowledge-copilot/documents',
      sources: '/api/knowledge-copilot/sources',
      quality: '/api/knowledge-copilot/quality-queue',
    },
    support: {
      review: '/api/support-copilot/tickets',
      connections: '/api/support-copilot/enterprise/connections',
      quality: '/api/support-copilot/enterprise/quality-metrics',
    },
    report: {
      records: '/api/report-copilot/sample-sources',
      sources: '/api/report-copilot/enterprise/connections',
      schedules: '/api/report-copilot/enterprise/schedules',
    },
    hr: {
      assessment: '/api/resume-copilot/jobs/confirmed',
      interview: '/api/resume-copilot/enterprise/question-bank',
      authorization: '/api/resume-copilot/enterprise/ats-connections',
      employeeQa: '/api/knowledge-copilot/quality-metrics',
      onboarding: '/api/resume-copilot/enterprise/onboarding-checklists',
    },
  }
  return endpoints[props.module][props.tab] ?? null
})

const connectionTab = computed(() =>
  (props.module === 'knowledge' && props.tab === 'sources')
  || (props.module === 'support' && props.tab === 'connections')
  || (props.module === 'report' && props.tab === 'sources')
  || (props.module === 'hr' && props.tab === 'authorization'))

const providerOptions = computed(() => ({
  knowledge: ['SHAREPOINT', 'CONFLUENCE', 'NOTION', 'S3', 'MINIO'],
  support: ['JIRA_SERVICE_MANAGEMENT', 'ZENDESK', 'SERVICENOW', 'FEISHU', 'WECOM'],
  report: ['JIRA', 'MEETING_NOTES'],
  hr: ['GREENHOUSE', 'WORKDAY', 'MOKA', 'BEISEN'],
  data: [],
})[props.module])

watch([() => props.module, () => props.tab], () => {
  data.value = null
  errorCode.value = ''
  provider.value = providerOptions.value[0] ?? ''
  void load()
}, { immediate: true })

async function load(): Promise<void> {
  if (!readEndpoint.value) return
  loading.value = true
  errorCode.value = ''
  try {
    const response = await api<unknown>(readEndpoint.value)
    data.value = response.data
    requestId.value = response.requestId
  } catch (error) {
    errorCode.value = error instanceof ApiError ? error.errorCode : 'generic'
    requestId.value = error instanceof ApiError ? error.requestId : null
    showToast(localizedError.value, 'danger')
  } finally {
    loading.value = false
  }
}

function connectionRequest(): { path: string; body: Record<string, unknown> } {
  const common = {
    connectionKey: connectionKey.value,
    displayName: displayName.value,
    provider: provider.value,
    baseUrl: baseUrl.value,
    secretRef: secretRef.value,
    enabled: enabled.value,
  }
  if (props.module === 'knowledge') {
    return {
      path: '/api/knowledge-copilot/sources',
      body: { ...common, rootReference: rootReference.value || null,
        groupMapping: {}, defaultVisibility: defaultVisibility.value },
    }
  }
  if (props.module === 'support') return { path: '/api/support-copilot/enterprise/connections', body: common }
  if (props.module === 'report') return { path: '/api/report-copilot/enterprise/connections', body: common }
  return { path: '/api/resume-copilot/enterprise/ats-connections', body: common }
}

async function saveConnection(): Promise<void> {
  loading.value = true
  errorCode.value = ''
  try {
    const request = connectionRequest()
    const response = await api<unknown>(request.path, { method: 'POST', ...jsonBody(request.body) })
    data.value = response.data
    requestId.value = response.requestId
    showToast(t('common.operationSucceeded'))
  } catch (error) {
    errorCode.value = error instanceof ApiError ? error.errorCode : 'generic'
    requestId.value = error instanceof ApiError ? error.requestId : null
    showToast(localizedError.value, 'danger')
  } finally {
    loading.value = false
  }
}

const localizedError = computed(() => {
  if (!errorCode.value) return ''
  return t(`errors.${te(`errors.${errorCode.value}`) ? errorCode.value : 'generic'}`)
})

const controlledAction = computed(() => {
  const key = `${props.module}:${props.tab}`
  const definitions: Record<string, {
    label: string
    needsId?: boolean
    valueLabel?: string
    request: (id: string, value: string) => { path: string; method: 'GET' | 'POST'; body?: unknown }
  }> = {
    'data:governance': {
      label: 'common.checkSchema',
      request: () => ({ path: '/api/data-copilot/schema-change-check', method: 'POST' }),
    },
    'data:records': {
      label: 'common.createHandoff',
      needsId: true,
      valueLabel: 'common.resultTitle',
      request: (id, value) => ({
        path: `/api/data-copilot/query-results/${encodeURIComponent(id)}/report-handoff`,
        method: 'POST',
        body: { title: value },
      }),
    },
    'knowledge:documents': {
      label: 'common.reindex',
      needsId: true,
      request: (id) => ({
        path: `/api/knowledge-copilot/documents/${encodeURIComponent(id)}/reindex`,
        method: 'POST',
      }),
    },
    'knowledge:sources': {
      label: 'common.syncNow',
      needsId: true,
      request: (id) => ({
        path: `/api/knowledge-copilot/sources/${encodeURIComponent(id)}/sync`,
        method: 'POST',
      }),
    },
    'support:connections': {
      label: 'common.syncTickets',
      needsId: true,
      request: (id) => ({
        path: `/api/support-copilot/enterprise/connections/${encodeURIComponent(id)}/import?limit=50`,
        method: 'POST',
      }),
    },
    'support:quality': {
      label: 'common.refreshSla',
      request: () => ({ path: '/api/support-copilot/enterprise/sla/refresh', method: 'POST' }),
    },
    'hr:interview': {
      label: 'common.interviewSummary',
      needsId: true,
      request: (id) => ({
        path: `/api/resume-copilot/enterprise/interview-sessions/${encodeURIComponent(id)}/summary`,
        method: 'GET',
      }),
    },
    'hr:authorization': {
      label: 'common.syncCandidates',
      needsId: true,
      valueLabel: 'common.consentReference',
      request: (id, value) => ({
        path: `/api/resume-copilot/enterprise/ats-connections/${encodeURIComponent(id)}/import`
          + `?consentReference=${encodeURIComponent(value)}&limit=50`,
        method: 'POST',
      }),
    },
  }
  return definitions[key] ?? null
})

const downloadFormats = computed(() => {
  if (props.module === 'data' && props.tab === 'records') return ['csv', 'xlsx']
  if (props.module === 'report' && props.tab === 'records') return ['docx', 'pdf', 'pptx']
  return []
})

function downloadPath(format: string): string {
  const id = encodeURIComponent(resourceId.value)
  return props.module === 'data'
    ? `/api/data-copilot/query-results/${id}/${format}`
    : `/api/report-copilot/enterprise/reports/${id}/${format}`
}

async function runControlledAction(): Promise<void> {
  if (!controlledAction.value) return
  loading.value = true
  errorCode.value = ''
  try {
    const request = controlledAction.value.request(resourceId.value, actionValue.value)
    const response = await api<unknown>(request.path, {
      method: request.method,
      ...(request.body === undefined ? {} : jsonBody(request.body)),
    })
    data.value = response.data
    requestId.value = response.requestId
    actionConfirmOpen.value = false
    showToast(t('common.operationSucceeded'))
  } catch (error) {
    errorCode.value = error instanceof ApiError ? error.errorCode : 'generic'
    requestId.value = error instanceof ApiError ? error.requestId : null
    actionConfirmOpen.value = false
    showToast(localizedError.value, 'danger')
  } finally {
    loading.value = false
  }
}

onUnmounted(() => { if (toastTimer) clearTimeout(toastTimer) })
</script>

<template>
  <section class="panel">
    <div class="section-heading">
      <div>
        <p class="enterprise-panel__description">{{ t(`${module}.next`) }}</p>
      </div>
      <button v-if="readEndpoint" class="button button--secondary" type="button" :disabled="loading" @click="load">
        {{ loading ? t('common.loading') : t('common.loadCapabilities') }}
      </button>
    </div>

    <form v-if="connectionTab && isAdmin" class="connection-form" @submit.prevent="saveConnection">
      <h3>{{ t('common.configureConnection') }}</h3>
      <div class="form-grid">
        <label>{{ t('common.connectionKey') }}<input v-model="connectionKey" required maxlength="100"></label>
        <label>{{ t('common.displayName') }}<input v-model="displayName" required maxlength="200"></label>
        <label>{{ t('common.provider') }}
          <select v-model="provider" required>
            <option v-for="option in providerOptions" :key="option" :value="option">{{ option }}</option>
          </select>
        </label>
        <label>{{ t('common.baseUrl') }}<input v-model="baseUrl" type="url" required maxlength="500" placeholder="https://api.example.com"></label>
        <label>{{ t('common.secretRef') }}<input v-model="secretRef" required maxlength="200" autocomplete="off" placeholder="EXTERNAL_PROVIDER_API_KEY"></label>
        <label v-if="module === 'knowledge'">{{ t('common.rootReference') }}<input v-model="rootReference" required maxlength="500"></label>
        <label v-if="module === 'knowledge'">{{ t('common.defaultVisibility') }}
          <select v-model="defaultVisibility">
            <option value="ADMIN">ADMIN</option><option value="HR_REVIEWER">HR_REVIEWER</option><option value="ALL">ALL</option>
          </select>
        </label>
        <label class="checkbox-label"><input v-model="enabled" type="checkbox"> {{ t('common.enabled') }}</label>
      </div>
      <p class="field-hint">{{ t('common.secretRefHint') }}</p>
      <button class="button button--primary" type="submit" :disabled="loading">{{ t('common.save') }}</button>
    </form>

    <form v-if="controlledAction && isAdmin" class="connection-form" @submit.prevent="actionConfirmOpen = true">
      <h3>{{ t('common.controlledAction') }}</h3>
      <div class="form-grid">
        <label v-if="controlledAction.needsId">
          {{ t('common.resourceId') }}
          <input v-model="resourceId" required inputmode="numeric" pattern="[A-Za-z0-9._-]+" maxlength="100">
        </label>
        <label v-if="controlledAction.valueLabel">
          {{ t(controlledAction.valueLabel) }}
          <input v-model="actionValue" required maxlength="300">
        </label>
      </div>
      <button class="button button--danger" type="submit" :disabled="loading">
        {{ t(controlledAction.label) }}
      </button>
      <p class="field-hint">{{ t('common.reviewBeforeConfirm') }}</p>
    </form>

    <section v-if="downloadFormats.length" class="download-actions">
      <h3>{{ t('common.download') }}</h3>
      <label>
        {{ t('common.resourceId') }}
        <input v-model="resourceId" required inputmode="numeric" pattern="[0-9]+" maxlength="20">
      </label>
      <div class="button-row">
        <a
          v-for="format in downloadFormats"
          :key="format"
          class="button button--secondary"
          :class="{ disabled: !resourceId }"
          :href="resourceId ? downloadPath(format) : undefined"
        >{{ format.toUpperCase() }}</a>
      </div>
    </section>

    <pre v-if="data" class="result-preview">{{ safeJson(data) }}</pre>
    <p v-else-if="!connectionTab" class="empty-state">{{ t('common.noData') }}</p>
    <RequestId :value="requestId" />
    <ConfirmDialog
      :open="actionConfirmOpen"
      :operation="controlledAction ? t(controlledAction.label) : ''"
      :target="resourceId || `${module}:${tab}`"
      current-state="READY"
      target-state="REQUESTED"
      :impact="t('common.reviewBeforeConfirm')"
      :recoverable="false"
      :risk="t('common.reviewBeforeConfirm')"
      :busy="loading"
      @confirm="runControlledAction"
      @cancel="actionConfirmOpen = false"
    />
  </section>
  <ToastMessage :message="toast" :tone="toastTone" />
</template>
