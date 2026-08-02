<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
const { t } = useI18n()
const expanded = ref(false)

const props = defineProps<{ items: unknown[] }>()
const visibleItems = computed(() => expanded.value ? props.items : props.items.slice(0, 3))

watch(() => props.items, () => { expanded.value = false })

function displayEvidence(item: unknown): string {
  return typeof item === 'string' ? item : JSON.stringify(item, null, 2)
}
</script>

<template>
  <section class="panel" aria-live="polite">
    <h2>{{ t('common.evidence') }}</h2>
    <ul v-if="items.length" class="evidence-list evidence-list--bounded">
      <li v-for="(item, index) in visibleItems" :key="index"><pre>{{ displayEvidence(item) }}</pre></li>
    </ul>
    <p v-else class="empty-state">{{ t('common.noData') }}</p>
    <button v-if="items.length > 3" class="button button--text" type="button" @click="expanded = !expanded">
      {{ expanded ? t('common.showLess') : t('common.showAllEvidence', { count: items.length }) }}
    </button>
  </section>
</template>
