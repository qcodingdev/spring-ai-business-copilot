<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { api, ApiError, jsonBody } from '@/api/client'
import ConfirmDialog from './ConfirmDialog.vue'
import RequestId from './RequestId.vue'
import { safeJson } from '@/utils/safeDisplay'

interface Scenario {
  scenarioId: string
  module: string
  title: string
  description: string
  inputTemplate: string
  dataScope: string
  version: number
  fallbackResultAvailable: boolean
}

const { t, te } = useI18n()
const scenarios = ref<Scenario[]>([])
const selected = ref<Scenario | null>(null)
const userInput = ref('')
const output = ref<unknown>(null)
const loading = ref(false)
const confirmOpen = ref(false)
const errorCode = ref('')
const requestId = ref<string | null>(null)

async function load(): Promise<void> {
  loading.value = true
  try {
    const response = await api<Scenario[]>('/api/demo/scenarios')
    scenarios.value = response.data
    requestId.value = response.requestId
  } catch (error) {
    errorCode.value = error instanceof ApiError ? error.errorCode : 'generic'
  } finally {
    loading.value = false
  }
}

function choose(scenario: Scenario): void {
  selected.value = scenario
  userInput.value = scenario.inputTemplate
  output.value = null
}

function moduleLabel(module: string): string {
  const key = module.toLowerCase() === 'resume' ? 'hr' : module.toLowerCase()
  return te(`navigation.${key}`) ? t(`navigation.${key}`) : module
}

async function execute(): Promise<void> {
  if (!selected.value) return
  loading.value = true
  try {
    const response = await api<unknown>('/api/demo/scenarios/execute', {
      method: 'POST',
      ...jsonBody({ scenarioId: selected.value.scenarioId, userInput: userInput.value }),
    })
    output.value = response.data
    requestId.value = response.requestId
    confirmOpen.value = false
  } catch (error) {
    errorCode.value = error instanceof ApiError ? error.errorCode : 'generic'
    requestId.value = error instanceof ApiError ? error.requestId : null
    confirmOpen.value = false
  } finally {
    loading.value = false
  }
}

async function loadSample(): Promise<void> {
  if (!selected.value) return
  loading.value = true
  try {
    const response = await api<unknown>(
      `/api/demo/scenarios/${encodeURIComponent(selected.value.scenarioId)}/sample-result`,
    )
    output.value = response.data
    requestId.value = response.requestId
  } catch (error) {
    errorCode.value = error instanceof ApiError ? error.errorCode : 'generic'
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <section class="panel">
    <div class="section-heading">
      <div>
        <h2>{{ t('common.publicScenarios') }}</h2>
        <p>{{ t('common.publicScenarioNotice') }}</p>
      </div>
      <button class="button button--secondary" type="button" :disabled="loading" @click="load">
        {{ t('common.refresh') }}
      </button>
    </div>
    <div class="scenario-grid">
      <button
        v-for="scenario in scenarios"
        :key="scenario.scenarioId"
        class="scenario-card"
        :class="{ active: selected?.scenarioId === scenario.scenarioId }"
        type="button"
        @click="choose(scenario)"
      >
        <strong>{{ moduleLabel(scenario.module) }} · {{ scenario.title }}</strong>
        <span>{{ scenario.description }}</span>
      </button>
    </div>
    <form v-if="selected" class="scenario-form" @submit.prevent="confirmOpen = true">
      <label>
        {{ t('common.fictionalBusinessInput') }}
        <textarea v-model="userInput" required maxlength="4000" rows="6" />
      </label>
      <div class="button-row">
        <button class="button button--danger" type="submit" :disabled="loading">
          {{ t('common.runScenario') }}
        </button>
        <button
          v-if="selected.fallbackResultAvailable"
          class="button button--secondary"
          type="button"
          :disabled="loading"
          @click="loadSample"
        >
          {{ t('common.loadSampleResult') }}
        </button>
      </div>
    </form>
    <div v-if="errorCode" class="alert alert--danger" role="alert">
      {{ t(`errors.${te(`errors.${errorCode}`) ? errorCode : 'generic'}`) }}
    </div>
    <pre v-if="output" class="result-preview">{{ safeJson(output) }}</pre>
    <p v-else-if="!loading && scenarios.length === 0" class="empty-state">{{ t('common.noData') }}</p>
    <RequestId :value="requestId" />
    <ConfirmDialog
      :open="confirmOpen"
      :operation="t('common.runScenario')"
      :target="selected?.scenarioId ?? ''"
      current-state="REVIEWED"
      target-state="RUNNING"
      :impact="t('common.publicScenarioNotice')"
      :recoverable="false"
      :risk="t('common.reviewBeforeConfirm')"
      :busy="loading"
      @confirm="execute"
      @cancel="confirmOpen = false"
    />
  </section>
</template>
