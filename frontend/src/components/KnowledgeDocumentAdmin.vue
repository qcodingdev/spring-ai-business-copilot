<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { api, ApiError, jsonBody } from '@/api/client'
import ConfirmDialog from './ConfirmDialog.vue'
import RequestId from './RequestId.vue'
import StatusBadge from './StatusBadge.vue'
import ToastMessage from './ToastMessage.vue'

interface KnowledgeDocument {
  id: number
  title: string
  sourceType: string
  sourceName: string
  category: string | null
  enabled: boolean
  indexStatus: string
  contentType: string
  versionNo: number
  currentVersion: boolean
  visibilityScope: string
  systemManaged: boolean
  updatedAt: string
}

const { t, te, locale } = useI18n()
const documents = ref<KnowledgeDocument[]>([])
const query = ref('')
const category = ref('')
const file = ref<File | null>(null)
const loading = ref(false)
const requestId = ref<string | null>(null)
const deleteTarget = ref<KnowledgeDocument | null>(null)
const toast = ref('')
const toastTone = ref<'success' | 'danger'>('success')
let toastTimer: ReturnType<typeof setTimeout> | undefined

const filtered = computed(() => {
  const needle = query.value.trim().toLocaleLowerCase()
  return documents.value.filter((document) => !needle || [document.title, document.sourceName, document.category]
    .some((value) => String(value ?? '').toLocaleLowerCase().includes(needle)))
})

function showToast(message: string, tone: 'success' | 'danger' = 'success'): void {
  if (toastTimer) clearTimeout(toastTimer)
  toast.value = message
  toastTone.value = tone
  toastTimer = setTimeout(() => { toast.value = '' }, 4500)
}

function localizedError(error: unknown): string {
  const code = error instanceof ApiError ? error.errorCode : 'generic'
  return t(`errors.${te(`errors.${code}`) ? code : 'generic'}`)
}

async function load(): Promise<void> {
  loading.value = true
  try {
    const response = await api<KnowledgeDocument[]>('/api/knowledge-copilot/documents')
    documents.value = response.data
    requestId.value = response.requestId
  } catch (error) {
    showToast(localizedError(error), 'danger')
    requestId.value = error instanceof ApiError ? error.requestId : null
  } finally {
    loading.value = false
  }
}

function chooseFile(event: Event): void {
  file.value = (event.target as HTMLInputElement).files?.[0] ?? null
}

async function upload(): Promise<void> {
  if (!file.value) return
  loading.value = true
  const body = new FormData()
  body.append('file', file.value)
  if (category.value.trim()) body.append('category', category.value.trim())
  try {
    const response = await api<Record<string, unknown>>('/api/knowledge-copilot/documents/file', {
      method: 'POST', body,
    })
    requestId.value = response.requestId
    file.value = null
    showToast(t('admin.documentAccepted'))
    await load()
  } catch (error) {
    showToast(localizedError(error), 'danger')
    requestId.value = error instanceof ApiError ? error.requestId : null
  } finally {
    loading.value = false
  }
}

async function toggle(document: KnowledgeDocument): Promise<void> {
  loading.value = true
  try {
    const response = await api(`/api/knowledge-copilot/documents/${document.id}/enabled`, {
      method: 'PATCH', ...jsonBody({ enabled: !document.enabled }),
    })
    requestId.value = response.requestId
    showToast(document.enabled ? t('admin.documentDisabled') : t('admin.documentEnabled'))
    await load()
  } catch (error) {
    showToast(localizedError(error), 'danger')
  } finally {
    loading.value = false
  }
}

async function reindex(document: KnowledgeDocument): Promise<void> {
  loading.value = true
  try {
    const response = await api(`/api/knowledge-copilot/documents/${document.id}/reindex`, { method: 'POST' })
    requestId.value = response.requestId
    showToast(t('admin.reindexScheduled'))
    await load()
  } catch (error) {
    showToast(localizedError(error), 'danger')
  } finally {
    loading.value = false
  }
}

async function remove(): Promise<void> {
  if (!deleteTarget.value) return
  loading.value = true
  try {
    const response = await api(`/api/knowledge-copilot/documents/${deleteTarget.value.id}`, { method: 'DELETE' })
    requestId.value = response.requestId
    deleteTarget.value = null
    showToast(t('admin.documentDeleted'))
    await load()
  } catch (error) {
    showToast(localizedError(error), 'danger')
  } finally {
    loading.value = false
  }
}

function date(value: string): string {
  return new Intl.DateTimeFormat(locale.value, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
}

onMounted(load)
</script>

<template>
  <section class="panel admin-section">
    <div class="section-heading">
      <div><h2>{{ t('admin.knowledgeDocuments') }}</h2><p>{{ t('admin.knowledgeDocumentsDescription') }}</p></div>
      <button class="button button--secondary" type="button" :disabled="loading" @click="load">{{ t('common.refresh') }}</button>
    </div>
    <form class="document-upload" @submit.prevent="upload">
      <label>{{ t('admin.documentFile') }}<input type="file" accept=".txt,.md,.pdf,.docx,text/plain,text/markdown,application/pdf" required @change="chooseFile"></label>
      <label>{{ t('admin.documentCategory') }}<input v-model="category" maxlength="100"></label>
      <button class="button button--primary" type="submit" :disabled="loading || !file">{{ t('knowledge.upload') }}</button>
    </form>
    <label class="document-search">{{ t('admin.searchDocuments') }}<input v-model="query" type="search" :placeholder="t('admin.searchDocumentsPlaceholder')"></label>
    <div class="table-scroll">
      <table class="data-table">
        <thead><tr><th>{{ t('admin.documentName') }}</th><th>{{ t('admin.documentCategory') }}</th><th>{{ t('common.status') }}</th><th>{{ t('admin.documentVersion') }}</th><th>{{ t('admin.updatedAt') }}</th><th>{{ t('admin.actions') }}</th></tr></thead>
        <tbody>
          <tr v-for="document in filtered" :key="document.id">
            <td><strong>{{ document.title }}</strong><small>{{ document.sourceName }} · #{{ document.id }}</small></td>
            <td>{{ document.category || '—' }}</td>
            <td><StatusBadge :label="document.indexStatus" :tone="document.indexStatus === 'INDEXED' ? 'success' : 'warning'" /><small>{{ document.enabled ? t('statuses.ACTIVE') : t('statuses.DISABLED') }}</small></td>
            <td>v{{ document.versionNo }}<small>{{ document.visibilityScope }}</small></td>
            <td>{{ date(document.updatedAt) }}</td>
            <td><div class="table-actions"><button class="button button--text" type="button" @click="toggle(document)">{{ document.enabled ? t('admin.disableDocument') : t('admin.enableDocument') }}</button><button class="button button--text" type="button" @click="reindex(document)">{{ t('common.reindex') }}</button><button v-if="!document.systemManaged" class="button button--text button--danger-text" type="button" @click="deleteTarget = document">{{ t('admin.deleteDocument') }}</button></div></td>
          </tr>
          <tr v-if="!filtered.length"><td colspan="6" class="empty-state">{{ t('common.noData') }}</td></tr>
        </tbody>
      </table>
    </div>
    <RequestId :value="requestId" />
  </section>
  <ConfirmDialog
    :open="Boolean(deleteTarget)"
    :operation="t('admin.deleteDocument')"
    :target="deleteTarget?.title ?? ''"
    current-state="ACTIVE"
    target-state="DELETED"
    :impact="t('admin.deleteDocumentImpact')"
    :recoverable="false"
    :risk="t('admin.deleteDocumentRisk')"
    :busy="loading"
    @confirm="remove"
    @cancel="deleteTarget = null"
  />
  <ToastMessage :message="toast" :tone="toastTone" />
</template>
