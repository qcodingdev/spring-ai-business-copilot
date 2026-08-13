<script setup lang="ts">
import { useI18n } from 'vue-i18n'
defineProps<{ value: string | null | undefined }>()
const { t } = useI18n()
</script>

<template>
  <p v-if="value" class="request-id"><strong>{{ t('common.requestId') }}:</strong> <code>{{ value }}</code></p>
</template>
