<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
const props = defineProps<{ options: Record<string, any> }>()
const emit = defineEmits<{ change: [scope: Record<string, any> | null, valid: boolean] }>()
const { t } = useI18n()
const enabled = ref(false)
const metricKey = ref('')
const resultColumn = ref('')
const periodStart = ref('')
const periodEnd = ref('')
const timezone = ref('Asia/Shanghai')
const confirmed = ref(false)
const eligible = computed(() => !props.options.result?.truncated && props.options.result?.rowCount === 1 && props.options.metrics?.length)
const columns = computed(() => Object.entries(props.options.result?.rows?.[0] ?? {}).filter(([, value]) => typeof value === 'number').map(([name]) => name))
const selectedMetric = computed(() => props.options.metrics?.find((metric: Record<string, any>) => metric.metricKey === metricKey.value))
watch([enabled, metricKey, resultColumn, periodStart, periodEnd, timezone], () => { confirmed.value = false })
watch([enabled, metricKey, resultColumn, periodStart, periodEnd, timezone, confirmed], () => {
  const valid = !enabled.value || Boolean(eligible.value && metricKey.value && resultColumn.value && periodStart.value && periodEnd.value >= periodStart.value && timezone.value && confirmed.value)
  emit('change', enabled.value ? { metricKey: metricKey.value, resultColumn: resultColumn.value, periodStart: periodStart.value, periodEnd: periodEnd.value, timezone: timezone.value, confirmed: confirmed.value } : null, valid)
}, { immediate: true })
</script>

<template>
  <section class="workflow-card">
    <h4>{{ t('data.metricScopeTitle') }}</h4>
    <p v-if="!eligible">{{ t('data.metricScopeUnavailable') }}</p>
    <label v-else class="checkbox-label"><input v-model="enabled" type="checkbox"> {{ t('data.metricScopeEnable') }}</label>
    <div v-if="enabled" class="form-grid">
      <label>{{ t('data.metricName') }}<select v-model="metricKey"><option value="">{{ t('data.selectMetric') }}</option><option v-for="metric in options.metrics" :key="metric.metricKey" :value="metric.metricKey">{{ metric.displayName }} · v{{ metric.version }} · {{ metric.unit }}</option></select></label>
      <label>{{ t('data.metricResultColumn') }}<select v-model="resultColumn"><option value="">{{ t('data.selectMetricColumn') }}</option><option v-for="column in columns" :key="column">{{ column }}</option></select></label>
      <label>{{ t('data.metricPeriodStart') }}<input v-model="periodStart" type="date"></label>
      <label>{{ t('data.metricPeriodEnd') }}<input v-model="periodEnd" type="date" :min="periodStart"></label>
      <label>{{ t('data.metricTimezone') }}<input v-model="timezone" maxlength="80"></label>
      <p v-if="selectedMetric">{{ selectedMetric.description }}</p>
      <pre class="code-block">{{ options.sql }}</pre>
      <label class="checkbox-label"><input v-model="confirmed" type="checkbox"> {{ t('data.metricScopeConfirm') }}</label>
    </div>
  </section>
</template>
