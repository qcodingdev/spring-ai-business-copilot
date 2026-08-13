import zhCommon from './zh-CN/common'
import zhAuth from './zh-CN/auth'
import zhNavigation from './zh-CN/navigation'
import zhErrors from './zh-CN/errors'
import zhStatuses from './zh-CN/statuses'
import zhData from './zh-CN/data'
import zhKnowledge from './zh-CN/knowledge'
import zhSupport from './zh-CN/support'
import zhReport from './zh-CN/report'
import zhHr from './zh-CN/hr'
import zhAdmin from './zh-CN/admin'
import enCommon from './en-US/common'
import enAuth from './en-US/auth'
import enNavigation from './en-US/navigation'
import enErrors from './en-US/errors'
import enStatuses from './en-US/statuses'
import enData from './en-US/data'
import enKnowledge from './en-US/knowledge'
import enSupport from './en-US/support'
import enReport from './en-US/report'
import enHr from './en-US/hr'
import enAdmin from './en-US/admin'

export const messages = {
  'zh-CN': {
    common: zhCommon,
    auth: zhAuth,
    navigation: zhNavigation,
    errors: zhErrors,
    statuses: zhStatuses,
    data: zhData,
    knowledge: zhKnowledge,
    support: zhSupport,
    report: zhReport,
    hr: zhHr,
    admin: zhAdmin,
  },
  'en-US': {
    common: enCommon,
    auth: enAuth,
    navigation: enNavigation,
    errors: enErrors,
    statuses: enStatuses,
    data: enData,
    knowledge: enKnowledge,
    support: enSupport,
    report: enReport,
    hr: enHr,
    admin: enAdmin,
  },
} as const

export type SupportedLocale = keyof typeof messages
