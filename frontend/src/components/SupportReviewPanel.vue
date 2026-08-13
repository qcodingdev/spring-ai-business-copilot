<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { api, ApiError, jsonBody, type ApiRequestInit } from '@/api/client'
import ConfirmDialog from './ConfirmDialog.vue'
import RequestId from './RequestId.vue'
import StatusBadge from './StatusBadge.vue'
import ToastMessage from './ToastMessage.vue'
import { useSession } from '@/composables/useSession'

interface QueueItem {
  ticketId: number
  externalReference: string | null
  customerQuestion: string
  category: string | null
  sentiment: string | null
  urgency: string | null
  status: string
  draftId: number | null
  draftStatus: string | null
  riskLevel: string | null
  riskReasons: string[]
  suggestedReply: string | null
  editReason: string | null
  decisionOutcome: string | null
  knowledgeVersions: string[]
  createdAt: string
}

type ReviewSession = { draftId: number; suggestedReply: string; confirmationToken: string; status: string; expiresAt: string }
type PendingAction = 'confirm' | 'cancel' | 'close' | 'manual' | null

const { t, te, locale } = useI18n()
const { canOperate } = useSession()
const loading = ref(false)
const queue = ref<QueueItem[]>([])
const search = ref('')
const statusFilter = ref('OPEN')
const urgencyFilter = ref('')
const selected = ref<QueueItem | null>(null)
const session = ref<ReviewSession | null>(null)
const editedReply = ref('')
const editReason = ref('')
const pendingAction = ref<PendingAction>(null)
const requestId = ref<string | null>(null)
const errorCode = ref('')
const toast = ref('')
const toastTone = ref<'success' | 'danger'>('success')
let toastTimer: ReturnType<typeof setTimeout> | undefined

const pendingStatuses = new Set(['RECEIVED', 'FAILED', 'DRAFTED', 'NEEDS_HUMAN'])
const filteredQueue = computed(() => queue.value.filter((item) => {
  const matchesStatus = statusFilter.value === ''
    || (statusFilter.value === 'OPEN' && pendingStatuses.has(item.status))
    || (statusFilter.value === 'DONE' && ['CONFIRMED', 'CLOSED', 'CANCELED'].includes(item.status))
    || item.status === statusFilter.value
  const query = search.value.trim().toLocaleLowerCase(locale.value)
  const matchesSearch = !query || `${item.externalReference ?? item.ticketId} ${item.customerQuestion} ${item.category ?? ''}`.toLocaleLowerCase(locale.value).includes(query)
  return matchesStatus && matchesSearch && (!urgencyFilter.value || item.urgency === urgencyFilter.value)
}))
const pendingCount = computed(() => queue.value.filter((item) => pendingStatuses.has(item.status)).length)
const highRiskCount = computed(() => queue.value.filter((item) => pendingStatuses.has(item.status) && ['HIGH', 'CRITICAL'].includes(item.riskLevel ?? item.urgency ?? '')).length)
const completedCount = computed(() => queue.value.filter((item) => ['CONFIRMED', 'CLOSED', 'CANCELED'].includes(item.status)).length)
const localizedError = computed(() => t(`errors.${te(`errors.${errorCode.value}`) ? errorCode.value : 'generic'}`))
const actionMeta = computed(() => {
  const item = selected.value
  if (!item || !pendingAction.value) return null
  const definitions = {
    confirm: { operation: t('support.queue.confirmAction'), targetState: 'CONFIRMED', impact: t('support.queue.confirmImpact') },
    cancel: { operation: t('support.queue.cancelAction'), targetState: 'CANCELED', impact: t('support.queue.cancelImpact') },
    close: { operation: t('support.queue.closeAction'), targetState: 'CLOSED', impact: t('support.queue.closeImpact') },
    manual: { operation: t('support.queue.manualAction'), targetState: 'CLOSED', impact: t('support.queue.manualImpact') },
  }
  return definitions[pendingAction.value]
})

function showToast(message: string, tone: 'success' | 'danger' = 'success'): void {
  if (toastTimer) clearTimeout(toastTimer)
  toast.value = message
  toastTone.value = tone
  toastTimer = setTimeout(() => { toast.value = '' }, 4500)
}

function statusLabel(value: string | null): string {
  return value && te(`statuses.${value}`) ? t(`statuses.${value}`) : (value || t('common.unknown'))
}

function queueValueLabel(value: string | null): string {
  return value && te(`support.queue.values.${value}`) ? t(`support.queue.values.${value}`) : statusLabel(value)
}

function statusTone(item: QueueItem): 'info' | 'success' | 'warning' | 'danger' {
  if (item.status === 'CLOSED' || item.status === 'CONFIRMED') return 'success'
  if (item.status === 'CANCELED') return 'danger'
  return ['HIGH', 'CRITICAL'].includes(item.riskLevel ?? item.urgency ?? '') ? 'danger' : 'warning'
}

async function load(): Promise<void> {
  loading.value = true
  try {
    const response = await api<QueueItem[]>('/api/support-copilot/tickets?limit=100')
    queue.value = response.data ?? []
    requestId.value = response.requestId
  } catch (error) {
    errorCode.value = error instanceof ApiError ? error.errorCode : 'generic'
    requestId.value = error instanceof ApiError ? error.requestId : null
    showToast(localizedError.value, 'danger')
  } finally { loading.value = false }
}

async function openReview(item: QueueItem): Promise<void> {
  selected.value = item
  session.value = null
  editedReply.value = item.suggestedReply ?? ''
  editReason.value = item.editReason ?? ''
  if (!item.draftId || !['DRAFTED', 'NEEDS_REVIEW'].includes(item.draftStatus ?? '')) return
  loading.value = true
  try {
    const response = await api<ReviewSession>(`/api/support-copilot/reply-drafts/${item.draftId}/review-session`, { method: 'POST' })
    session.value = response.data
    editedReply.value = response.data.suggestedReply
    requestId.value = response.requestId
  } catch (error) {
    errorCode.value = error instanceof ApiError ? error.errorCode : 'generic'
    requestId.value = error instanceof ApiError ? error.requestId : null
    showToast(localizedError.value, 'danger')
  } finally { loading.value = false }
}

async function analyzeImported(item: QueueItem): Promise<void> {
  loading.value = true
  try {
    const response = await api(`/api/support-copilot/tickets/${item.ticketId}/analyze`, { method: 'POST' })
    requestId.value = response.requestId
    showToast(t('support.queue.analysisCompleted'))
    await load()
  } catch (error) {
    errorCode.value = error instanceof ApiError ? error.errorCode : 'generic'
    requestId.value = error instanceof ApiError ? error.requestId : null
    showToast(localizedError.value, 'danger')
  } finally { loading.value = false }
}

async function saveEdit(): Promise<void> {
  if (!selected.value?.draftId || !session.value || !editedReply.value.trim()) return
  loading.value = true
  try {
    const response = await api(`/api/support-copilot/reply-drafts/${selected.value.draftId}/edit`, {
      method: 'POST',
      ...jsonBody({ editedText: editedReply.value, reason: editReason.value || null }),
    })
    requestId.value = response.requestId
    showToast(t('support.editSaved'))
  } catch (error) {
    errorCode.value = error instanceof ApiError ? error.errorCode : 'generic'
    requestId.value = error instanceof ApiError ? error.requestId : null
    showToast(localizedError.value, 'danger')
  } finally { loading.value = false }
}

function requestQueueAction(item: QueueItem, action: Exclude<PendingAction, 'confirm' | 'cancel' | null>): void {
  selected.value = item
  session.value = null
  pendingAction.value = action
}

async function runAction(): Promise<void> {
  const item = selected.value
  const action = pendingAction.value
  if (!item || !action) return
  loading.value = true
  try {
    let path: string
    let init: ApiRequestInit
    if (action === 'confirm' || action === 'cancel') {
      if (!item.draftId || !session.value?.confirmationToken) return
      path = `/api/support-copilot/reply-drafts/${item.draftId}/${action}`
      init = { method: 'POST', ...jsonBody({ confirmationToken: session.value.confirmationToken }) }
    } else if (action === 'close') {
      if (!item.draftId) return
      path = `/api/support-copilot/reply-drafts/${item.draftId}/mark-customer-replied`
      init = { method: 'POST' }
    } else {
      path = `/api/support-copilot/tickets/${item.ticketId}/record-manual-reply`
      init = { method: 'POST' }
    }
    const response = await api(path, init)
    requestId.value = response.requestId
    pendingAction.value = null
    selected.value = null
    session.value = null
    showToast(t('support.queue.actionSaved'))
    await load()
  } catch (error) {
    errorCode.value = error instanceof ApiError ? error.errorCode : 'generic'
    requestId.value = error instanceof ApiError ? error.requestId : null
    pendingAction.value = null
    showToast(localizedError.value, 'danger')
  } finally { loading.value = false }
}

watch(() => true, () => { void load() }, { immediate: true })
onUnmounted(() => { if (toastTimer) clearTimeout(toastTimer) })
</script>

<template>
  <section class="support-review">
    <div class="section-heading">
      <div><h3>{{ t('support.queue.heading') }}</h3><p class="enterprise-panel__description">{{ t('support.queue.description') }}</p></div>
      <button class="button button--secondary" type="button" :disabled="loading" @click="load">{{ loading ? t('common.loading') : t('common.refresh') }}</button>
    </div>

    <div class="queue-summary" aria-live="polite">
      <article><span>{{ t('support.queue.pending') }}</span><strong>{{ pendingCount }}</strong></article>
      <article><span>{{ t('support.queue.highRisk') }}</span><strong>{{ highRiskCount }}</strong></article>
      <article><span>{{ t('support.queue.completed') }}</span><strong>{{ completedCount }}</strong></article>
    </div>

    <div class="queue-toolbar">
      <label>{{ t('common.search') }}<input v-model="search" type="search" :placeholder="t('support.queue.searchPlaceholder')"></label>
      <label>{{ t('support.queue.statusFilter') }}<select v-model="statusFilter"><option value="OPEN">{{ t('support.queue.openItems') }}</option><option value="">{{ t('common.all') }}</option><option value="DONE">{{ t('support.queue.completedItems') }}</option><option value="RECEIVED">{{ statusLabel('RECEIVED') }}</option><option value="FAILED">{{ statusLabel('FAILED') }}</option><option value="NEEDS_HUMAN">{{ statusLabel('NEEDS_HUMAN') }}</option><option value="DRAFTED">{{ statusLabel('DRAFTED') }}</option><option value="CONFIRMED">{{ statusLabel('CONFIRMED') }}</option><option value="CLOSED">{{ statusLabel('CLOSED') }}</option><option value="CANCELED">{{ statusLabel('CANCELED') }}</option></select></label>
      <label>{{ t('support.queue.urgencyFilter') }}<select v-model="urgencyFilter"><option value="">{{ t('common.all') }}</option><option v-for="value in ['CRITICAL','HIGH','MEDIUM','LOW']" :key="value" :value="value">{{ statusLabel(value) }}</option></select></label>
    </div>

    <div v-if="filteredQueue.length" class="review-queue-list">
      <article v-for="item in filteredQueue" :key="`${item.ticketId}-${item.draftId ?? 'manual'}`" class="review-queue-card">
        <div class="review-queue-card__heading"><div><small>{{ item.externalReference ?? `#${item.ticketId}` }}</small><h3>{{ item.customerQuestion }}</h3></div><StatusBadge :label="statusLabel(item.status)" :tone="statusTone(item)" /></div>
        <div class="review-queue-card__meta"><span>{{ statusLabel(item.urgency) }}</span><span>{{ queueValueLabel(item.category) }}</span><span>{{ queueValueLabel(item.sentiment) }}</span><span>{{ statusLabel(item.riskLevel) }}</span><span>{{ new Date(item.createdAt).toLocaleString(locale) }}</span></div>
        <p v-if="item.suggestedReply" class="review-queue-card__reply">{{ item.suggestedReply }}</p>
        <ul v-if="item.riskReasons?.length" class="risk-reason-list"><li v-for="reason in item.riskReasons" :key="reason">{{ reason }}</li></ul>
        <div class="button-row">
          <button v-if="!item.draftId && ['RECEIVED','FAILED'].includes(item.status) && canOperate" class="button button--primary" type="button" :disabled="loading" @click="analyzeImported(item)">{{ t('support.queue.analyzeImported') }}</button>
          <button v-else-if="item.draftId && ['DRAFTED','NEEDS_REVIEW'].includes(item.draftStatus ?? '')" class="button button--primary" type="button" :disabled="loading" @click="openReview(item)">{{ t('support.queue.reviewDraft') }}</button>
          <button v-else-if="item.draftId && item.status === 'CONFIRMED'" class="button button--secondary" type="button" @click="requestQueueAction(item, 'close')">{{ t('support.queue.recordReply') }}</button>
          <button v-else-if="!item.draftId && item.status === 'NEEDS_HUMAN'" class="button button--secondary" type="button" @click="requestQueueAction(item, 'manual')">{{ t('support.queue.recordManualReply') }}</button>
        </div>
      </article>
    </div>
    <p v-else class="empty-state">{{ t('support.queue.noMatches') }}</p>

    <form v-if="selected && session" class="queue-review-editor" @submit.prevent="saveEdit">
      <div class="section-heading"><div><p class="panel-kicker">{{ selected.externalReference ?? `#${selected.ticketId}` }}</p><h3>{{ t('support.queue.reviewTitle') }}</h3></div><StatusBadge :label="statusLabel(session.status)" tone="warning" /></div>
      <div class="review-context"><div><span>{{ t('support.queue.customerQuestion') }}</span><p>{{ selected.customerQuestion }}</p></div><div><span>{{ t('support.queue.reviewFocus') }}</span><ul><li>{{ t('support.queue.focusAccuracy') }}</li><li>{{ t('support.queue.focusEvidence') }}</li><li>{{ t('support.queue.focusRisk') }}</li><li>{{ t('support.queue.focusTone') }}</li></ul></div></div>
      <label>{{ t('support.replyDraft') }}<textarea v-model="editedReply" required maxlength="4000" rows="9"></textarea></label>
      <label>{{ t('support.editReason') }}<input v-model="editReason" maxlength="500"></label>
      <p class="field-hint">{{ t('support.queue.tokenHint') }} · {{ session.expiresAt }}</p>
      <div class="button-row"><button class="button button--secondary" type="submit" :disabled="loading || !editedReply.trim()">{{ t('support.saveEdit') }}</button><button class="button button--primary" type="button" :disabled="loading || !editedReply.trim()" @click="pendingAction = 'confirm'">{{ t('support.queue.confirmAction') }}</button><button class="button button--danger" type="button" :disabled="loading" @click="pendingAction = 'cancel'">{{ t('support.queue.cancelAction') }}</button><button class="button button--ghost" type="button" @click="selected = null; session = null">{{ t('common.close') }}</button></div>
    </form>

    <RequestId :value="requestId" />
    <ConfirmDialog v-if="actionMeta && selected" :open="Boolean(pendingAction)" :operation="actionMeta.operation" :target="selected.externalReference ?? `#${selected.ticketId}`" :current-state="selected.status" :target-state="actionMeta.targetState" :impact="actionMeta.impact" :recoverable="false" :expires-at="session?.expiresAt" :risk="actionMeta.impact" :busy="loading" @confirm="runAction" @cancel="pendingAction = null" />
  </section>
  <ToastMessage :message="toast" :tone="toastTone" />
</template>
