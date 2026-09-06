<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { api, ApiError, jsonBody } from '@/api/client'
import RequestId from './RequestId.vue'
import StatusBadge from './StatusBadge.vue'
import ToastMessage from './ToastMessage.vue'

const props = defineProps<{ subjectType: 'DATA_SQL_CANDIDATE' | 'REPORT_DRAFT' }>()
const { t } = useI18n()
const loading = ref(false)
const tasks = ref<Record<string, any>[]>([])
const notes = ref<Record<string, string>>({})
const requestId = ref<string | null>(null)
const toast = ref('')
const toastTone = ref<'success' | 'danger'>('success')

const title = computed(() => props.subjectType === 'DATA_SQL_CANDIDATE'
  ? t('common.dataIndependentReview') : t('common.reportIndependentReview'))

function showToast(message: string, tone: 'success' | 'danger' = 'success'): void {
  toast.value = message
  toastTone.value = tone
}

async function load(): Promise<void> {
  if (loading.value) return
  loading.value = true
  try {
    const response = await api<Record<string, any>[]>(`/api/reviews/queue?subjectType=${props.subjectType}`)
    tasks.value = response.data ?? []
    requestId.value = response.requestId
  } catch (error) {
    requestId.value = error instanceof ApiError ? error.requestId : null
    showToast(t('common.reviewLoadFailed'), 'danger')
  } finally { loading.value = false }
}

async function decide(task: Record<string, any>, decision: 'APPROVE' | 'REJECT'): Promise<void> {
  const note = String(notes.value[task.id] ?? '').trim()
  if (!note || loading.value) return
  loading.value = true
  try {
    const response = await api<Record<string, any>>(`/api/reviews/${task.id}/decision`, {
      method: 'POST', ...jsonBody({ decision, note }),
    })
    requestId.value = response.requestId
    tasks.value = tasks.value.filter((item) => item.id !== task.id)
    delete notes.value[task.id]
    showToast(decision === 'APPROVE' ? t('common.reviewApproved') : t('common.reviewRejected'))
  } catch (error) {
    requestId.value = error instanceof ApiError ? error.requestId : null
    showToast(t('common.reviewSubmitFailed'), 'danger')
  } finally { loading.value = false }
}

function reportContent(task: Record<string, any>): Record<string, any> | null {
  const value = task.subject?.content
  if (!value) return null
  try { return typeof value === 'string' ? JSON.parse(value) : value } catch { return null }
}

function items(content: Record<string, any> | null, key: string): Record<string, any>[] {
  return content && Array.isArray(content[key]) ? content[key] : []
}

onMounted(load)
</script>

<template>
  <section class="panel enterprise-panel independent-review-panel">
    <div class="section-heading">
      <div><h3>{{ title }}</h3><p>{{ t('common.independentReviewDescription') }}</p></div>
      <button class="button button--secondary" type="button" :disabled="loading" @click="load">{{ t('common.refresh') }}</button>
    </div>
    <div v-if="tasks.length" class="record-grid">
      <article v-for="task in tasks" :key="task.id" class="review-task-card">
        <div class="section-heading">
          <div><strong>#{{ task.subjectId }}</strong><small>{{ t('common.createdBy') }}：{{ task.ownerActorId }}</small></div>
          <StatusBadge :label="t('common.awaitingIndependentReview')" tone="warning" />
        </div>

        <template v-if="subjectType === 'DATA_SQL_CANDIDATE'">
          <p>{{ t('common.model') }}：{{ task.subject?.modelName || '—' }} · {{ t('common.promptVersion') }}：{{ task.subject?.promptVersion || '—' }}</p>
          <pre class="sql-preview"><code>{{ task.subject?.sql }}</code></pre>
        </template>

        <template v-else>
          <h4>{{ task.subject?.title }}</h4>
          <p>{{ task.subject?.reportType }} · {{ task.subject?.periodStart }} – {{ task.subject?.periodEnd }}</p>
          <div v-if="reportContent(task)" class="review-report-preview">
            <p><strong>{{ t('report.executiveSummary') }}</strong></p>
            <p>{{ reportContent(task)?.executiveSummary }}</p>
            <template v-for="section in ['metricHighlights', 'completedItems', 'risks', 'actionItems', 'suggestions']" :key="section">
              <div v-if="items(reportContent(task), section).length">
                <strong>{{ t(`report.sections.${section}`) }}</strong>
                <ul><li v-for="(item, index) in items(reportContent(task), section)" :key="index">{{ item.text || item.summary || `${item.metricName}: ${item.metricValue} ${item.unit || ''}` }}</li></ul>
              </div>
            </template>
          </div>
        </template>

        <label>{{ t('common.reviewNote') }}<textarea v-model="notes[task.id]" rows="3" maxlength="1000" required :placeholder="t('common.reviewNotePlaceholder')"></textarea></label>
        <div class="button-row">
          <button class="button button--primary" type="button" :disabled="loading || !notes[task.id]?.trim()" @click="decide(task, 'APPROVE')">{{ t('common.approve') }}</button>
          <button class="button button--danger" type="button" :disabled="loading || !notes[task.id]?.trim()" @click="decide(task, 'REJECT')">{{ t('common.reject') }}</button>
        </div>
      </article>
    </div>
    <p v-else class="empty-state">{{ t('common.noPendingIndependentReviews') }}</p>
    <RequestId :value="requestId" />
  </section>
  <ToastMessage :message="toast" :tone="toastTone" />
</template>
