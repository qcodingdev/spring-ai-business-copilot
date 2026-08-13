<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { api, ApiError, jsonBody } from '@/api/client'
import RequestId from './RequestId.vue'
import StatusBadge from './StatusBadge.vue'
import ToastMessage from './ToastMessage.vue'

interface QueueItem {
  answerId: number
  requestId: string | null
  question: string
  answerPreview: string | null
  retrievedChunkIds: string | null
  citedChunkIds: string | null
  answerStatus: string
  refusalReason: string | null
  rating: string | null
  feedbackReason: string | null
  comment: string | null
  answerCreatedAt: string
  feedbackUpdatedAt: string | null
  issueVersion: number
  issueUpdatedAt: string
}

const { t, te, locale } = useI18n()
const loading = ref(false)
const queue = ref<QueueItem[]>([])
const selected = ref<QueueItem | null>(null)
const decision = ref('KNOWLEDGE_UPDATE_REQUIRED')
const evidenceAssessment = ref('INSUFFICIENT')
const answerAssessment = ref('NOT_VERIFIABLE')
const remediationAction = ref('UPDATE_KNOWLEDGE')
const note = ref('')
const requestId = ref<string | null>(null)
const errorCode = ref('')
const toast = ref('')
const toastTone = ref<'success' | 'danger'>('success')
let toastTimer: ReturnType<typeof setTimeout> | undefined

const localizedError = computed(() => t(`errors.${te(`errors.${errorCode.value}`) ? errorCode.value : 'generic'}`))
const evidenceOptions = ['SUFFICIENT', 'INSUFFICIENT', 'CONFLICTING', 'OUTDATED', 'NOT_APPLICABLE']
const answerOptions = ['ACCURATE', 'PARTIALLY_ACCURATE', 'INACCURATE', 'NOT_VERIFIABLE']
const remediationOptions = ['NONE', 'REINDEX_SOURCE', 'UPDATE_KNOWLEDGE', 'ADJUST_POLICY', 'FOLLOW_UP_WITH_REQUESTER']
const decisionOptions = ['RESOLVED', 'DISMISSED', 'KNOWLEDGE_UPDATE_REQUIRED']

function showToast(message: string, tone: 'success' | 'danger' = 'success'): void {
  if (toastTimer) clearTimeout(toastTimer)
  toast.value = message
  toastTone.value = tone
  toastTimer = setTimeout(() => { toast.value = '' }, 4500)
}

function optionLabel(group: string, value: string): string {
  return t(`knowledge.reviewOptions.${group}.${value}`)
}

function statusLabel(value: string | null): string {
  return value && te(`statuses.${value}`) ? t(`statuses.${value}`) : (value || t('common.unknown'))
}

function detailText(value: string | null): string {
  return value ? statusLabel(value) : ''
}

function chunkIds(value: string | null): string[] {
  return value?.split(',').map((item) => item.trim()).filter(Boolean) ?? []
}

function beginReview(item: QueueItem): void {
  selected.value = item
  decision.value = item.answerStatus === 'ANSWERED' ? 'KNOWLEDGE_UPDATE_REQUIRED' : 'RESOLVED'
  evidenceAssessment.value = item.answerStatus === 'NO_EVIDENCE' ? 'INSUFFICIENT' : 'NOT_APPLICABLE'
  answerAssessment.value = item.answerStatus === 'ANSWERED' ? 'PARTIALLY_ACCURATE' : 'NOT_VERIFIABLE'
  remediationAction.value = item.answerStatus === 'NO_EVIDENCE' ? 'UPDATE_KNOWLEDGE' : 'FOLLOW_UP_WITH_REQUESTER'
  note.value = ''
}

async function load(): Promise<void> {
  loading.value = true
  try {
    const response = await api<{ content: QueueItem[] }>('/api/knowledge-copilot/quality-queue?size=50')
    queue.value = response.data?.content ?? []
    requestId.value = response.requestId
  } catch (error) {
    errorCode.value = error instanceof ApiError ? error.errorCode : 'generic'
    requestId.value = error instanceof ApiError ? error.requestId : null
    showToast(localizedError.value, 'danger')
  } finally { loading.value = false }
}

async function review(): Promise<void> {
  if (!selected.value || !note.value.trim()) return
  loading.value = true
  try {
    const item = selected.value
    const response = await api(`/api/knowledge-copilot/quality-queue/${item.answerId}/review`, {
      method: 'POST',
      ...jsonBody({
        decision: decision.value,
        evidenceAssessment: evidenceAssessment.value,
        answerAssessment: answerAssessment.value,
        remediationAction: remediationAction.value,
        reviewNote: note.value,
        expectedIssueVersion: item.issueVersion,
        expectedIssueUpdatedAt: item.issueUpdatedAt,
      }),
    })
    requestId.value = response.requestId
    selected.value = null
    note.value = ''
    showToast(t('knowledge.reviewSaved'))
    await load()
  } catch (error) {
    errorCode.value = error instanceof ApiError ? error.errorCode : 'generic'
    requestId.value = error instanceof ApiError ? error.requestId : null
    showToast(localizedError.value, 'danger')
  } finally { loading.value = false }
}

watch(() => true, () => { void load() }, { immediate: true })
onUnmounted(() => { if (toastTimer) clearTimeout(toastTimer) })
</script>

<template>
  <section class="knowledge-quality">
    <div class="section-heading">
      <div><h3>{{ t('knowledge.qualityQueueHeading') }}</h3><p class="enterprise-panel__description">{{ t('knowledge.qualityDescription') }}</p></div>
      <button class="button button--secondary" type="button" :disabled="loading" @click="load">{{ loading ? t('common.loading') : t('common.refresh') }}</button>
    </div>

    <div v-if="queue.length" class="quality-queue-list">
      <article v-for="item in queue" :key="item.answerId" class="quality-queue-card" :class="{ active: selected?.answerId === item.answerId }">
        <div class="quality-queue-card__heading"><div><small>#{{ item.answerId }} · {{ new Date(item.issueUpdatedAt).toLocaleString(locale) }}</small><h3>{{ item.question }}</h3></div><StatusBadge :label="statusLabel(item.rating || item.answerStatus)" :tone="item.rating === 'NOT_HELPFUL' ? 'warning' : 'info'" /></div>
        <p>{{ item.comment || detailText(item.refusalReason) || t('knowledge.qualityNoComment') }}</p>
        <div class="quality-queue-card__signals"><span>{{ t('knowledge.retrievedEvidence') }} <b>{{ chunkIds(item.retrievedChunkIds).length }}</b></span><span>{{ t('knowledge.citedEvidence') }} <b>{{ chunkIds(item.citedChunkIds).length }}</b></span><span>{{ t('knowledge.issueVersion') }} <b>v{{ item.issueVersion }}</b></span></div>
        <button class="button button--primary" type="button" @click="beginReview(item)">{{ t('knowledge.reviewIssue') }}</button>
      </article>
    </div>
    <p v-else class="empty-state">{{ t('common.noData') }}</p>

    <form v-if="selected" class="knowledge-review-form" @submit.prevent="review">
      <div class="section-heading"><div><p class="panel-kicker">{{ t('knowledge.reviewContext') }} #{{ selected.answerId }}</p><h3>{{ selected.question }}</h3></div><StatusBadge :label="statusLabel(selected.answerStatus)" tone="warning" /></div>
      <div class="knowledge-review-context">
        <section><h4>{{ t('knowledge.answerUnderReview') }}</h4><p>{{ selected.answerPreview || detailText(selected.refusalReason) || t('knowledge.noAnswerPreview') }}</p></section>
        <section><h4>{{ t('knowledge.userFeedback') }}</h4><p>{{ selected.comment || (selected.feedbackReason ? optionLabel('feedback', selected.feedbackReason) : t('knowledge.noUserFeedback')) }}</p></section>
        <section><h4>{{ t('knowledge.evidenceTrace') }}</h4><dl><dt>{{ t('knowledge.retrievedEvidence') }}</dt><dd>{{ chunkIds(selected.retrievedChunkIds).join(', ') || t('common.noData') }}</dd><dt>{{ t('knowledge.citedEvidence') }}</dt><dd>{{ chunkIds(selected.citedChunkIds).join(', ') || t('common.noData') }}</dd></dl></section>
      </div>

      <div class="review-dimensions">
        <label>{{ t('knowledge.evidenceAssessment') }}<select v-model="evidenceAssessment" required><option v-for="value in evidenceOptions" :key="value" :value="value">{{ optionLabel('evidence', value) }}</option></select><small>{{ t('knowledge.evidenceAssessmentHint') }}</small></label>
        <label>{{ t('knowledge.answerAssessment') }}<select v-model="answerAssessment" required><option v-for="value in answerOptions" :key="value" :value="value">{{ optionLabel('answer', value) }}</option></select><small>{{ t('knowledge.answerAssessmentHint') }}</small></label>
        <label>{{ t('knowledge.remediationAction') }}<select v-model="remediationAction" required><option v-for="value in remediationOptions" :key="value" :value="value">{{ optionLabel('remediation', value) }}</option></select><small>{{ t('knowledge.remediationActionHint') }}</small></label>
        <label>{{ t('knowledge.reviewDecision') }}<select v-model="decision" required><option v-for="value in decisionOptions" :key="value" :value="value">{{ optionLabel('decision', value) }}</option></select><small>{{ t('knowledge.reviewDecisionHint') }}</small></label>
      </div>
      <label>{{ t('knowledge.reviewNote') }}<textarea v-model="note" required maxlength="1000" rows="5" :placeholder="t('knowledge.reviewNotePlaceholder')"></textarea></label>
      <div class="button-row"><button class="button button--primary" type="submit" :disabled="loading || !note.trim()">{{ t('knowledge.completeReview') }}</button><button class="button button--secondary" type="button" @click="selected = null">{{ t('common.cancel') }}</button></div>
    </form>
    <RequestId :value="requestId" />
  </section>
  <ToastMessage :message="toast" :tone="toastTone" />
</template>
