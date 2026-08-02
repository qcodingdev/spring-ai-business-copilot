<script setup lang="ts">
withDefaults(defineProps<{ label: string; tone?: 'info' | 'success' | 'warning' | 'danger' }>(), {
  tone: 'info',
})
</script>

<template>
  <span class="status-badge" :class="`status-badge--${tone}`"><span aria-hidden="true">●</span> {{ label }}</span>
</template>
