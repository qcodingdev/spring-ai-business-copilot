<script setup lang="ts">
defineProps<{ name: 'overview' | 'data' | 'knowledge' | 'support' | 'report' | 'hr' | 'admin' }>()
</script>

<template>
  <svg class="module-icon" aria-hidden="true" viewBox="0 0 24 24" fill="none">
    <template v-if="name === 'overview'">
      <path d="M4 13h6V4H4v9Zm10 7h6V11h-6v9ZM4 20h6v-3H4v3Zm10-13h6V4h-6v3Z" />
    </template>
    <template v-else-if="name === 'data'">
      <path d="M4 19V9m6 10V5m6 14v-7m4 7H2" />
      <circle cx="4" cy="7" r="1.5" /><circle cx="10" cy="3" r="1.5" /><circle cx="16" cy="10" r="1.5" />
    </template>
    <template v-else-if="name === 'knowledge'">
      <path d="M4 5.5A3.5 3.5 0 0 1 7.5 2H11v17H7.5A3.5 3.5 0 0 0 4 22V5.5Zm16 0A3.5 3.5 0 0 0 16.5 2H13v17h3.5A3.5 3.5 0 0 1 20 22V5.5Z" />
    </template>
    <template v-else-if="name === 'support'">
      <path d="M20 14a4 4 0 0 1-4 4H9l-5 3v-7a8 8 0 1 1 16 0Z" />
      <path d="M8 10h8M8 14h5" />
    </template>
    <template v-else-if="name === 'report'">
      <path d="M5 2h10l4 4v16H5V2Z" /><path d="M15 2v5h4M8 17v-4m4 4V9m4 8v-6" />
    </template>
    <template v-else-if="name === 'hr'">
      <circle cx="9" cy="7" r="3" /><path d="M3.5 20v-2.5A4.5 4.5 0 0 1 8 13h2a4.5 4.5 0 0 1 4.5 4.5V20M17 8v6m-3-3h6" />
    </template>
    <template v-else>
      <path d="M12 15.5a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7Z" /><path d="m19.4 15 .1.1 1.4 2.4-2.4 2.4-2.4-1.4H16a8 8 0 0 1-2 .8V22h-4v-2.7a8 8 0 0 1-2-.8h-.1l-2.4 1.4-2.4-2.4 1.4-2.4V15a8 8 0 0 1-.8-2H1V9h2.7a8 8 0 0 1 .8-2v-.1L3.1 4.5l2.4-2.4 2.4 1.4H8a8 8 0 0 1 2-.8V0h4v2.7a8 8 0 0 1 2 .8h.1l2.4-1.4 2.4 2.4-1.4 2.4V7a8 8 0 0 1 .8 2H23v4h-2.7a8 8 0 0 1-.9 2Z" />
    </template>
  </svg>
</template>
