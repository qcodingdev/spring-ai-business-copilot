<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

const props = defineProps<{ execution: Record<string, any> }>()
const { t } = useI18n()
const table = computed<Record<string, any>>(() => props.execution.table ?? {})
const columns = computed<Record<string, any>[]>(() => Array.isArray(table.value.columns) ? table.value.columns : [])
const rows = computed<Record<string, any>[]>(() => Array.isArray(table.value.rows) ? table.value.rows : [])
const explanation = computed(() => String(props.execution.explanation?.explanation ?? ''))
const rowCount = computed(() => Number(table.value.rowCount ?? rows.value.length))

function valueFor(row: Record<string, any>, column: Record<string, any>): string {
  const value = row.values?.[column.name] ?? row[column.name]
  if (value === null || value === undefined || value === '') return '—'
  return typeof value === 'object' ? JSON.stringify(value) : String(value)
}
</script>

<template>
  <div class="data-execution-result">
    <p v-if="explanation" class="data-execution-result__explanation">{{ explanation }}</p>
    <div v-if="columns.length && rows.length" class="data-execution-result__table-wrap">
      <table>
        <thead><tr><th v-for="column in columns" :key="column.name" scope="col">{{ column.name }}</th></tr></thead>
        <tbody><tr v-for="(row, rowIndex) in rows" :key="rowIndex"><td v-for="column in columns" :key="column.name">{{ valueFor(row, column) }}</td></tr></tbody>
      </table>
    </div>
    <p v-else class="empty-state">{{ t('data.noExecutionRows') }}</p>
    <p class="data-execution-result__meta">{{ t('data.returnedRows', { count: rowCount }) }}<span v-if="table.truncated"> · {{ t('data.resultTruncated') }}</span></p>
  </div>
</template>
