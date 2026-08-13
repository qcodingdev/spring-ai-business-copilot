<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { currentLocale, setLocale } from '@/locales'
import type { SupportedLocale } from '@/locales/messages'

const { t } = useI18n()
const targetLocale = computed<SupportedLocale>(() => currentLocale() === 'zh-CN' ? 'en-US' : 'zh-CN')
const targetLabel = computed(() => targetLocale.value === 'en-US' ? t('common.english') : t('common.chinese'))

function switchLocale(): void {
  setLocale(targetLocale.value)
}
</script>

<template>
  <button
    class="language-switcher"
    type="button"
    :aria-label="`${t('common.switchTo')} ${targetLabel}`"
    data-testid="language-switcher"
    @click="switchLocale"
  >
    <svg aria-hidden="true" viewBox="0 0 24 24"><path d="M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18Zm0 0c2.1-2.3 3.2-5.3 3.2-9S14.1 5.3 12 3m0 18c-2.1-2.3-3.2-5.3-3.2-9S9.9 5.3 12 3M3.5 9h17m-17 6h17" /></svg>
    <span>{{ targetLabel }}</span>
  </button>
</template>
