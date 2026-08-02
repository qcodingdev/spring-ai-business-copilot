export default {
  title: 'Customer ticket handling and human review',
  description: 'Classification, risk, and knowledge evidence stay beside the draft; external writeback is confirmed separately.',
  tabs: { tickets: 'Ticket handling', review: 'Human review queue', connections: 'External connections', quality: 'Quality and SLA' },
  ticket: 'Customer message',
  ticketPlaceholder: 'Paste a masked customer issue.',
  analyze: 'Analyze ticket and draft reply',
  draft: 'Original AI draft',
  writeback: 'Confirm external writeback',
  next: 'Edit and confirm the draft. External writes require a new second confirmation.',
  cardDescription: 'Understand customer needs, retrieve policy evidence, draft a reply, and route it to an agent for review.',
  cardTag: 'Ticket triage · Human review',
  examples: {
    first: 'A customer reports a duplicate charge and is upset. Assess risk and draft a reply.',
    second: 'A customer wants to cancel an order that already shipped. Suggest next steps with evidence.',
    third: 'An enterprise customer reported API timeouts twice. Draft an escalation response.',
  },
}
