<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

const props = defineProps<{
  open: boolean
  operation: string
  target: string
  currentState: string
  targetState: string
  impact: string
  recoverable: boolean
  expiresAt?: string
  risk: string
  busy?: boolean
}>()
const emit = defineEmits<{ confirm: []; cancel: [] }>()
const { t } = useI18n()
const dialog = ref<HTMLDialogElement>()
let previousFocus: HTMLElement | null = null

watch(
  () => props.open,
  async (open) => {
    if (open) {
      previousFocus = document.activeElement as HTMLElement
      await nextTick()
      dialog.value?.showModal()
      dialog.value?.querySelector<HTMLElement>('button')?.focus()
    } else {
      dialog.value?.close()
      previousFocus?.focus()
    }
  },
  { immediate: true },
)

function cancel(): void {
  if (!props.busy) emit('cancel')
}

onBeforeUnmount(() => dialog.value?.close())
</script>

<template>
  <dialog ref="dialog" class="confirm-dialog" @cancel.prevent="cancel">
    <form method="dialog" @submit.prevent>
      <h2>{{ operation }}</h2>
      <p>{{ t('common.reviewBeforeConfirm') }}</p>
      <dl class="confirm-grid">
        <dt>{{ t('common.target') }}</dt><dd>{{ target }}</dd>
        <dt>{{ t('common.currentState') }}</dt><dd>{{ currentState }}</dd>
        <dt>{{ t('common.targetState') }}</dt><dd>{{ targetState }}</dd>
        <dt>{{ t('common.impact') }}</dt><dd>{{ impact }}</dd>
        <dt>{{ t('common.recoverable') }}</dt><dd>{{ recoverable ? t('common.yes') : t('common.no') }}</dd>
        <dt v-if="expiresAt">{{ t('common.tokenExpiry') }}</dt><dd v-if="expiresAt">{{ expiresAt }}</dd>
      </dl>
      <div class="alert alert--danger"><strong>{{ t('common.risks') }}:</strong> {{ risk }}</div>
      <div class="dialog-actions">
        <button class="button button--secondary" type="button" :disabled="busy" @click="cancel">{{ t('common.cancel') }}</button>
        <button class="button button--danger" type="button" :disabled="busy" @click="emit('confirm')">
          {{ busy ? t('common.loading') : operation }}
        </button>
      </div>
    </form>
  </dialog>
</template>
