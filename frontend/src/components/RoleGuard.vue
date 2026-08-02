<script setup lang="ts">
import { computed } from 'vue'
import { useSession } from '@/composables/useSession'

const props = defineProps<{ roles: string[] }>()
const { session } = useSession()
const allowed = computed(() => props.roles.some((role) => session.value?.roles.includes(role)))
</script>

<template>
  <slot v-if="allowed" />
  <slot v-else name="denied" />
</template>
