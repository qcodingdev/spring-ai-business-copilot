<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { api, ApiError, jsonBody } from '@/api/client'
import RequestId from './RequestId.vue'
import StatusBadge from './StatusBadge.vue'
import ToastMessage from './ToastMessage.vue'

type QueueItem = Record<string, any>
const { t, te } = useI18n()
const loading = ref(false)
const queue = ref<QueueItem[]>([])
const selected = ref<QueueItem | null>(null)
const decision = ref('RESOLVED')
const note = ref('')
const requestId = ref<string | null>(null)
const errorCode = ref('')
const toast = ref('')
const toastTone = ref<'success' | 'danger'>('success')
let toastTimer: ReturnType<typeof setTimeout> | undefined

const localizedError = computed(() => t(`errors.${te(`errors.${errorCode.value}`) ? errorCode.value : 'generic'}`))
function showToast(message: string, tone: 'success' | 'danger' = 'success'): void { if (toastTimer) clearTimeout(toastTimer); toast.value = message; toastTone.value = tone; toastTimer = setTimeout(() => { toast.value = '' }, 4500) }
async function load(): Promise<void> {
  loading.value = true
  try {
    const response = await api<{ content: QueueItem[] }>('/api/knowledge-copilot/quality-queue?size=50')
    queue.value = response.data?.content ?? []; requestId.value = response.requestId
  } catch (error) { errorCode.value = error instanceof ApiError ? error.errorCode : 'generic'; requestId.value = error instanceof ApiError ? error.requestId : null; showToast(localizedError.value, 'danger') } finally { loading.value = false }
}
async function review(): Promise<void> {
  if (!selected.value || !note.value.trim()) return
  loading.value = true
  try {
    const item = selected.value
    const response = await api(`/api/knowledge-copilot/quality-queue/${item.answerId}/review`, { method: 'POST', ...jsonBody({ decision: decision.value, reviewNote: note.value, expectedIssueVersion: item.issueVersion, expectedIssueUpdatedAt: item.issueUpdatedAt }) })
    requestId.value = response.requestId; selected.value = null; note.value = ''; showToast(t('knowledge.reviewSaved')); await load()
  } catch (error) { errorCode.value = error instanceof ApiError ? error.errorCode : 'generic'; requestId.value = error instanceof ApiError ? error.requestId : null; showToast(localizedError.value, 'danger') } finally { loading.value = false }
}
watch(() => true, () => { void load() }, { immediate: true })
onUnmounted(() => { if (toastTimer) clearTimeout(toastTimer) })
</script>

<template>
  <section class="panel">
    <div class="section-heading"><div><h2>{{ t('knowledge.tabs.quality') }}</h2><p class="enterprise-panel__description">{{ t('knowledge.qualityDescription') }}</p></div><button class="button button--secondary" type="button" :disabled="loading" @click="load">{{ loading ? t('common.loading') : t('common.refresh') }}</button></div>
    <div v-if="queue.length" class="record-grid">
      <article v-for="item in queue" :key="item.answerId"><div class="section-heading"><h3>{{ item.question }}</h3><StatusBadge :label="item.rating || item.answerStatus" :tone="item.rating === 'NOT_HELPFUL' ? 'warning' : 'info'" /></div><p>{{ item.comment || item.refusalReason || t('knowledge.qualityNoComment') }}</p><small>#{{ item.answerId }} · v{{ item.issueVersion }}</small><button class="button button--primary" type="button" @click="selected = item">{{ t('knowledge.reviewIssue') }}</button></article>
    </div>
    <p v-else class="empty-state">{{ t('common.noData') }}</p>
    <form v-if="selected" class="connection-form" @submit.prevent="review"><h3>{{ t('knowledge.reviewIssue') }} #{{ selected.answerId }}</h3><p>{{ selected.question }}</p><label>{{ t('knowledge.reviewDecision') }}<select v-model="decision"><option value="RESOLVED">RESOLVED</option><option value="DISMISSED">DISMISSED</option><option value="KNOWLEDGE_UPDATE_REQUIRED">KNOWLEDGE_UPDATE_REQUIRED</option></select></label><label>{{ t('knowledge.reviewNote') }}<textarea v-model="note" required maxlength="1000" rows="4"></textarea></label><div class="button-row"><button class="button button--primary" type="submit" :disabled="loading || !note.trim()">{{ t('common.save') }}</button><button class="button button--secondary" type="button" @click="selected = null">{{ t('common.cancel') }}</button></div></form>
    <RequestId :value="requestId" />
  </section>
  <ToastMessage :message="toast" :tone="toastTone" />
</template>
