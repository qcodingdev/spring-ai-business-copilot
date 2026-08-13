<script setup lang="ts">
import { computed, onUnmounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { api, ApiError, jsonBody } from '@/api/client'
import RequestId from './RequestId.vue'
import StatusBadge from './StatusBadge.vue'
import ToastMessage from './ToastMessage.vue'

type RecordItem = Record<string, any>
const { t, te, locale } = useI18n()
const loading = ref(false)
const queue = ref<RecordItem[]>([])
const session = ref<RecordItem | null>(null)
const feedback = ref('')
const requestId = ref<string | null>(null)
const errorCode = ref('')
const toast = ref('')
const toastTone = ref<'success' | 'danger'>('success')
let toastTimer: ReturnType<typeof setTimeout> | undefined

const assessment = computed(() => session.value?.assessment ?? null)
const localizedError = computed(() => t(`errors.${te(`errors.${errorCode.value}`) ? errorCode.value : 'generic'}`))

function showToast(message: string, tone: 'success' | 'danger' = 'success'): void {
  if (toastTimer) clearTimeout(toastTimer)
  toast.value = message
  toastTone.value = tone
  toastTimer = setTimeout(() => { toast.value = '' }, 5000)
}

function date(value: string | null): string {
  if (!value) return '—'
  return new Intl.DateTimeFormat(locale.value, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
}

async function load(): Promise<void> {
  loading.value = true
  try {
    const response = await api<RecordItem[]>('/api/resume-copilot/assessments/review-queue?limit=50')
    queue.value = response.data ?? []
    requestId.value = response.requestId
  } catch (error) {
    errorCode.value = error instanceof ApiError ? error.errorCode : 'generic'
    requestId.value = error instanceof ApiError ? error.requestId : null
    showToast(localizedError.value, 'danger')
  } finally { loading.value = false }
}

async function open(item: RecordItem): Promise<void> {
  loading.value = true
  try {
    const response = await api<RecordItem>(`/api/resume-copilot/assessments/${item.assessmentId}/review-session`, { method: 'POST' })
    session.value = response.data
    feedback.value = ''
    requestId.value = response.requestId
  } catch (error) {
    errorCode.value = error instanceof ApiError ? error.errorCode : 'generic'
    requestId.value = error instanceof ApiError ? error.requestId : null
    showToast(localizedError.value, 'danger')
  } finally { loading.value = false }
}

async function decide(action: 'review' | 'cancel'): Promise<void> {
  if (!assessment.value?.assessmentId || !session.value?.reviewToken) return
  loading.value = true
  try {
    const response = await api<RecordItem>(`/api/resume-copilot/assessments/${assessment.value.assessmentId}/${action}`, {
      method: 'POST',
      ...jsonBody(action === 'review'
        ? { token: session.value.reviewToken, correctedContent: null, reviewerFeedback: feedback.value || null }
        : { token: session.value.reviewToken }),
    })
    requestId.value = response.requestId
    session.value = null
    showToast(t(action === 'review' ? 'hr.assessmentReviewed' : 'hr.assessmentCancelled'))
    await load()
  } catch (error) {
    errorCode.value = error instanceof ApiError ? error.errorCode : 'generic'
    requestId.value = error instanceof ApiError ? error.requestId : null
    showToast(localizedError.value, 'danger')
  } finally { loading.value = false }
}

void load()
onUnmounted(() => { if (toastTimer) clearTimeout(toastTimer) })
</script>

<template>
  <section class="panel enterprise-panel">
    <div class="section-heading">
      <div><h3>{{ t('hr.reviewQueue') }}</h3><p>{{ t('hr.reviewQueueDescription') }}</p></div>
      <button class="button button--secondary" type="button" :disabled="loading" @click="load">{{ loading ? t('common.loading') : t('common.refresh') }}</button>
    </div>
    <div v-if="queue.length" class="record-grid">
      <article v-for="item in queue" :key="item.assessmentId">
        <div class="section-heading"><h4>{{ item.jobTitle }}</h4><StatusBadge :label="item.status" tone="warning" /></div>
        <p>{{ item.candidateReference }} · {{ t('hr.assessmentId') }} #{{ item.assessmentId }}</p>
        <p v-if="item.reviewReasons?.length" class="field-hint">{{ item.reviewReasons.join('；') }}</p>
        <small>{{ date(item.updatedAt) }} · {{ item.reviewerActorId || t('hr.unassignedReviewer') }}</small>
        <button class="button button--primary" type="button" :disabled="loading" @click="open(item)">{{ t('hr.openAssessmentReview') }}</button>
      </article>
    </div>
    <p v-else class="empty-state">{{ t('common.noData') }}</p>

    <section v-if="assessment" class="workflow-card assessment-review">
      <div class="section-heading"><h3>{{ t('hr.assessmentResult') }}</h3><StatusBadge :label="assessment.status" tone="warning" /></div>
      <p v-if="assessment.content?.anonymousSummary">{{ assessment.content.anonymousSummary }}</p>
      <ul v-if="assessment.content?.criterionAssessments?.length"><li v-for="item in assessment.content.criterionAssessments" :key="item.criterionId"><strong>{{ item.criterionId }} · {{ item.status }}</strong><span>{{ item.explanation }}</span></li></ul>
      <div v-if="assessment.evidence?.length" class="assessment-evidence"><h4>{{ t('common.evidence') }}</h4><ul><li v-for="item in assessment.evidence" :key="item.evidenceId"><strong>{{ item.evidenceId }} · {{ item.section }}</strong><span>{{ item.sanitizedText }}</span></li></ul></div>
      <label>{{ t('hr.reviewerFeedback') }}<textarea v-model="feedback" rows="4" maxlength="2000"></textarea></label>
      <div class="button-row"><button class="button button--primary" type="button" :disabled="loading" @click="decide('review')">{{ t('hr.confirmAssessmentReview') }}</button><button class="button button--danger" type="button" :disabled="loading" @click="decide('cancel')">{{ t('common.cancel') }}</button></div>
      <small>{{ t('common.tokenExpiry') }}：{{ date(session?.expiresAt) }}</small>
    </section>
    <RequestId :value="requestId" />
  </section>
  <ToastMessage :message="toast" :tone="toastTone" />
</template>
