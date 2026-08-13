<script setup lang="ts">
defineProps<{ title: string; description: string; status?: string }>()
</script>

<template>
  <header class="page-header">
    <div>
      <p v-if="status" class="eyebrow">{{ status }}</p>
      <h1>{{ title }}</h1>
      <p>{{ description }}</p>
    </div>
    <div class="page-header__actions"><slot /></div>
  </header>
</template>
