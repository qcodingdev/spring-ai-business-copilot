export default {
  title: '客户工单处理与人工复核',
  description: '分类、风险和知识证据与回复草稿并列展示，外部回写单独确认。',
  tabs: { tickets: '工单处理', review: '人工复核队列', connections: '外部连接', quality: '质量与 SLA' },
  ticket: '客户消息',
  ticketPlaceholder: '粘贴一条已脱敏的客户问题。',
  analyze: '分析工单并生成草稿',
  draft: 'AI 原始草稿',
  writeback: '确认外部回写',
  next: '人工编辑并确认草稿；外部写入需要新的二次确认。',
  cardDescription: '理解客户诉求、检索处理依据、起草回复，并交给客服人员复核发送。',
  cardTag: '工单分级 · 人工复核',
  examples: {
    first: '客户反馈重复扣款且情绪激动，请判断风险并起草回复。',
    second: '客户要求取消已发货订单，请给出处置建议和依据。',
    third: '企业客户连续两次反馈接口超时，请生成升级处理草稿。',
  },
}
