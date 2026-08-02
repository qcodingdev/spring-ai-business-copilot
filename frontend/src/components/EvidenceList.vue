<script setup lang="ts">
import { useI18n } from 'vue-i18n'
defineProps<{ items: unknown[] }>()
const { t } = useI18n()

function displayEvidence(item: unknown): string {
  return typeof item === 'string' ? item : JSON.stringify(item, null, 2)
}
</script>

<template>
  <section class="panel" aria-live="polite">
    <h2>{{ t('common.evidence') }}</h2>
    <ul v-if="items.length" class="evidence-list">
      <li v-for="(item, index) in items" :key="index"><pre>{{ displayEvidence(item) }}</pre></li>
    </ul>
    <p v-else class="empty-state">{{ t('common.noData') }}</p>
  </section>
</template>
