<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { api, ApiError, jsonBody } from '@/api/client'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import PageHeader from '@/components/PageHeader.vue'
import RequestId from '@/components/RequestId.vue'
import RoleGuard from '@/components/RoleGuard.vue'
import { safeJson } from '@/utils/safeDisplay'

const { t } = useI18n()
const diagnostics = ref<Record<string, unknown> | null>(null)
const loading = ref(false)
const errorCode = ref('')
const requestId = ref<string | null>(null)
const resetOpen = ref(false)
const initializeOpen = ref(false)
const resetIntent = ref<{
  willDelete: Record<string, number>
  resetToken: string
  expiresAt: string
  requiredConfirmationText: string
} | null>(null)
const resetConfirmation = ref('')

async function load(): Promise<void> {
  loading.value = true
  try {
    const response = await api<Record<string, unknown>>('/api/admin/diagnostics')
    diagnostics.value = response.data
    requestId.value = response.requestId
  } catch (error) {
    errorCode.value = error instanceof ApiError ? error.errorCode : 'generic'
    requestId.value = error instanceof ApiError ? error.requestId : null
  } finally {
    loading.value = false
  }
}

async function reset(): Promise<void> {
  if (!resetIntent.value) return
  loading.value = true
  try {
    const response = await api<Record<string, unknown>>('/api/admin/demo-data/reset', {
      method: 'POST',
      ...jsonBody({
        resetToken: resetIntent.value.resetToken,
        confirmationText: resetConfirmation.value,
      }),
    })
    diagnostics.value = { ...diagnostics.value, demoDataReset: response.data }
    requestId.value = response.requestId
    resetOpen.value = false
    resetIntent.value = null
    resetConfirmation.value = ''
  } catch (error) {
    errorCode.value = error instanceof ApiError ? error.errorCode : 'generic'
    requestId.value = error instanceof ApiError ? error.requestId : null
  } finally {
    loading.value = false
  }
}

async function initialize(): Promise<void> {
  loading.value = true
  try {
    const response = await api<Record<string, unknown>>('/api/admin/demo-data/initialize', { method: 'POST' })
    diagnostics.value = { ...diagnostics.value, demoDataInitialization: response.data }
    requestId.value = response.requestId
    initializeOpen.value = false
  } catch (error) {
    errorCode.value = error instanceof ApiError ? error.errorCode : 'generic'
    requestId.value = error instanceof ApiError ? error.requestId : null
  } finally {
    loading.value = false
  }
}

async function prepareReset(): Promise<void> {
  loading.value = true
  try {
    const response = await api<NonNullable<typeof resetIntent.value>>(
      '/api/admin/demo-data/reset-intents',
      { method: 'POST' },
    )
    resetIntent.value = response.data
    requestId.value = response.requestId
  } catch (error) {
    errorCode.value = error instanceof ApiError ? error.errorCode : 'generic'
    requestId.value = error instanceof ApiError ? error.requestId : null
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <RoleGuard :roles="['ADMIN']">
    <PageHeader :title="t('admin.title')" :description="t('admin.description')">
      <button class="button button--secondary" type="button" :disabled="loading" @click="load">{{ t('common.refresh') }}</button>
    </PageHeader>
    <div class="workflow-grid">
      <section class="panel">
        <h2>{{ t('admin.diagnostics') }}</h2>
        <div v-if="loading" class="loading-overlay" role="status">{{ t('common.loading') }}</div>
        <pre v-else-if="diagnostics" class="result-preview">{{ safeJson(diagnostics) }}</pre>
        <p v-else class="empty-state">{{ t('common.noData') }}</p>
        <div v-if="errorCode" class="alert alert--danger">{{ t(`errors.${errorCode}`) }}</div>
        <RequestId :value="requestId" />
      </section>
      <aside class="side-stack">
        <section class="panel">
          <h2>{{ t('admin.connections') }}</h2>
          <ul class="check-list">
            <li>✓ {{ t('admin.httpsAllowlist') }}</li>
            <li>✓ {{ t('admin.dnsRedirect') }}</li>
            <li>✓ {{ t('admin.secretReferences') }}</li>
            <li>✓ {{ t('admin.resourceLimits') }}</li>
          </ul>
        </section>
        <section class="panel">
          <h2>{{ t('admin.demoData') }}</h2>
          <p>{{ t('admin.description') }}</p>
          <div class="button-row">
            <button class="button button--secondary" type="button" @click="initializeOpen = true">{{ t('admin.initialize') }}</button>
            <button class="button button--danger" type="button" @click="prepareReset">{{ t('admin.prepareReset') }}</button>
          </div>
          <div v-if="resetIntent" class="connection-form">
            <pre class="result-preview">{{ safeJson(resetIntent.willDelete) }}</pre>
            <label>
              {{ t('admin.resetConfirmation') }}
              <input v-model="resetConfirmation" autocomplete="off" maxlength="100">
            </label>
            <p class="field-hint">{{ resetIntent.requiredConfirmationText }}</p>
            <button
              class="button button--danger"
              type="button"
              :disabled="resetConfirmation !== resetIntent.requiredConfirmationText"
              @click="resetOpen = true"
            >{{ t('admin.reset') }}</button>
          </div>
        </section>
      </aside>
    </div>
    <ConfirmDialog
      :open="initializeOpen"
      :operation="t('admin.initialize')"
      target="fictional-demo-dataset"
      current-state="NOT_READY"
      target-state="INITIALIZING"
      :impact="t('admin.description')"
      :recoverable="true"
      :risk="t('common.reviewBeforeConfirm')"
      :busy="loading"
      @confirm="initialize"
      @cancel="initializeOpen = false"
    />
    <ConfirmDialog
      :open="resetOpen"
      :operation="t('admin.reset')"
      target="fictional-demo-dataset"
      current-state="RESET_INTENT_CREATED"
      target-state="RESET_PENDING"
      :impact="t('admin.description')"
      :recoverable="false"
      :expires-at="resetIntent?.expiresAt"
      :risk="t('common.reviewBeforeConfirm')"
      :busy="loading"
      @confirm="reset"
      @cancel="resetOpen = false"
    />
    <template #denied><p class="permission-denied">{{ t('admin.adminOnly') }}</p></template>
  </RoleGuard>
</template>
