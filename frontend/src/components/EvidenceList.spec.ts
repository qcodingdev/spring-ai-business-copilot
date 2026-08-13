import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import EvidenceList from './EvidenceList.vue'
import { i18n } from '@/locales'

describe('EvidenceList', () => {
  it('renders structured citations as readable evidence instead of raw JSON', () => {
    const wrapper = mount(EvidenceList, {
      props: { items: [{ chunkId: 42, excerpt: '退款申请应在订单页发起。' }] },
      global: { plugins: [i18n] },
    })

    expect(wrapper.text()).toContain('证据条目 #42')
    expect(wrapper.text()).toContain('退款申请应在订单页发起。')
    expect(wrapper.text()).not.toContain('"chunkId"')
  })
})
