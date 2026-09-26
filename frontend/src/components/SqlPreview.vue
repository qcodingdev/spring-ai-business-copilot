<script setup lang="ts">
import { useI18n } from 'vue-i18n'
defineProps<{ sql: string }>()
const { t } = useI18n()
</script>

<template>
  <section id="data-sql-preview" class="panel">
    <h2>{{ t('data.sqlPreview') }}</h2>
    <pre class="code-block"><code>{{ sql || t('common.noData') }}</code></pre>
  </section>
</template>
