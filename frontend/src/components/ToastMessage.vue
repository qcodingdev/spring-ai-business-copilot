<script setup lang="ts">
defineProps<{
  message: string
  tone?: 'success' | 'danger' | 'info'
}>()
</script>

<template>
  <Transition name="toast">
    <div v-if="message" class="toast-message" :class="`toast-message--${tone ?? 'info'}`" role="status" aria-live="polite">
      <span>{{ message }}</span>
    </div>
  </Transition>
</template>
