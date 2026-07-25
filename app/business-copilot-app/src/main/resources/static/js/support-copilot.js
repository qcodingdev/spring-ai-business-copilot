/**
 * Support Copilot — 智能客服助手前端工作台
 *
 * 提供工单输入、示例快捷填充、分类展示、知识依据列表、
 * 回复草稿展示和确认/取消操作。
 */
(function () {
  'use strict';

  const API_BASE = '/api/support-copilot';

  // ── DOM refs ──────────────────────────────────────────────────
  const $ = (sel) => document.querySelector(sel);
  const $$ = (sel) => document.querySelectorAll(sel);

  const els = {
    ticketInput: $('#sc-ticket-input'),
    channelSelect: $('#sc-channel-select'),
    analyzeBtn: $('#sc-analyze-btn'),
    sampleBtns: $('#sc-sample-buttons'),

    classificationPanel: $('#sc-classification-panel'),
    category: $('#sc-classification-category'),
    sentiment: $('#sc-classification-sentiment'),
    urgency: $('#sc-classification-urgency'),
    needsHuman: $('#sc-classification-needs-human'),
    summary: $('#sc-classification-summary'),
    reasonsDiv: $('#sc-classification-reasons'),
    reasonsList: $('#sc-classification-reasons-list'),

    evidencePanel: $('#sc-evidence-panel'),
    evidenceEmpty: $('#sc-evidence-empty'),
    evidenceEmptyText: $('#sc-evidence-empty-text'),
    evidenceList: $('#sc-evidence-list'),

    draftPanel: $('#sc-draft-panel'),
    draftText: $('#sc-draft-text'),
    draftEditor: $('#sc-draft-editor'),
    draftEditText: $('#sc-draft-edit-text'),
    draftEditReason: $('#sc-draft-edit-reason'),
    editBtn: $('#sc-edit-btn'),
    draftRiskBadge: $('#sc-draft-risk-badge'),
    draftNeedsHuman: $('#sc-draft-needs-human'),
    draftRiskReasons: $('#sc-draft-risk-reasons'),
    draftCitationsDiv: $('#sc-draft-citations'),
    draftCitationsList: $('#sc-draft-citations-list'),
    draftActions: $('#sc-draft-actions'),
    draftId: $('#sc-draft-id'),
    confirmationToken: $('#sc-confirmation-token'),
    confirmBtn: $('#sc-confirm-btn'),
    cancelBtn: $('#sc-cancel-btn'),

    auditTbody: $('#sc-audit-tbody')
  };

  // ── Sample ticket data ────────────────────────────────────────
  const sampleTickets = {
    product: 'CloudMart 的商品批量导入应该怎么操作？单次最多可以导入多少个 SKU？',
    activation: '我想注册 CloudMart 账号，注册完成后应该怎样完成邮箱验证？',
    refund: '商品有质量问题，我申请退款时需要提供哪些材料，标准退款流程怎么走？',
    incident: '核心数据库故障导致全站业务完全不可用，这属于什么故障等级，应该如何响应？'
  };

  // ── Event listeners ───────────────────────────────────────────

  els.analyzeBtn.addEventListener('click', analyzeTicket);

  els.sampleBtns.addEventListener('click', (e) => {
    const btn = e.target.closest('.sample-btn');
    if (!btn) return;
    const category = btn.dataset.category;
    const ticket = sampleTickets[category];
    if (ticket) {
      els.ticketInput.value = ticket;
      els.ticketInput.focus();
    }
  });

  els.confirmBtn.addEventListener('click', () => {
    const draftId = els.draftId.value;
    const token = els.confirmationToken.value;
    if (!draftId || !token) return;
    if (!window.confirm('是否已通过实际客服渠道回复客户？确定后会确认草稿、标记“已回复客户”并关闭工单。系统不会自动发送消息。')) return;
    confirmDraft(draftId, token);
  });

  els.cancelBtn.addEventListener('click', () => {
    const draftId = els.draftId.value;
    const token = els.confirmationToken.value;
    if (!draftId || !token) return;
    cancelDraft(draftId, token);
  });
  els.editBtn.addEventListener('click', () => {
    const draftId = els.draftId.value;
    if (!draftId) return;
    editDraft(draftId);
  });

  // ── API calls ─────────────────────────────────────────────────

  async function analyzeTicket() {
    const customerMessage = els.ticketInput.value.trim();
    if (!customerMessage) {
      showError('请输入客户消息');
      return;
    }

    showLoading('正在分析工单...');
    clearResults();

    try {
      const resp = await fetch(API_BASE + '/tickets/analyze', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          customerMessage: customerMessage,
          channel: els.channelSelect.value
        })
      });

      if (!resp.ok) {
        const err = await resp.json().catch(() => ({}));
        throw new Error(err.message || '请求失败 (' + resp.status + ')');
      }

      const body = await resp.json();
      if (!body.success) {
        throw new Error(body.message || '分析失败');
      }

      renderResult(body.data);
    } catch (err) {
      showError('工单分析失败: ' + err.message);
      console.error(err);
    } finally {
      hideLoading();
    }
  }

  async function confirmDraft(draftId, token) {
    showLoading('正在确认并记录回复...');
    try {
      const resp = await fetch(API_BASE + '/reply-drafts/' + draftId + '/confirm', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ confirmationToken: token })
      });

      if (!resp.ok) {
        const err = await resp.json().catch(() => ({}));
        throw new Error(err.message || '确认失败');
      }

      const body = await resp.json();
      if (body.success) {
        const replied = await fetch(API_BASE + '/reply-drafts/' + draftId + '/mark-customer-replied', {
          method: 'POST'
        });
        const repliedBody = await replied.json().catch(() => ({}));
        if (!replied.ok || !repliedBody.success) {
          throw new Error(repliedBody.message || '草稿已确认，但未能记录“已回复客户”');
        }
        els.draftActions.hidden = true;
        showToast('已确认草稿并标记“已回复客户”，工单已关闭');
      } else {
        throw new Error(body.message || '确认失败');
      }
    } catch (err) {
      showError('确认失败: ' + err.message);
    } finally {
      hideLoading();
      loadAuditLogs();
    }
  }

  async function cancelDraft(draftId, token) {
    showLoading('正在取消...');
    try {
      const resp = await fetch(API_BASE + '/reply-drafts/' + draftId + '/cancel', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ confirmationToken: token })
      });

      if (!resp.ok) {
        const err = await resp.json().catch(() => ({}));
        throw new Error(err.message || '取消失败');
      }

      const body = await resp.json();
      if (body.success) {
        els.draftActions.hidden = true;
        showToast('草稿已取消');
      } else {
        throw new Error(body.message || '取消失败');
      }
    } catch (err) {
      showError('取消失败: ' + err.message);
    } finally {
      hideLoading();
      loadAuditLogs();
    }
  }

  async function editDraft(draftId) {
    const editedText = els.draftEditText.value.trim();
    if (!editedText) {
      showError('人工修订后的草稿不能为空');
      return;
    }
    showLoading('正在保存人工修订…');
    try {
      const resp = await fetch(API_BASE + '/reply-drafts/' + draftId + '/edit', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          editedText,
          reason: els.draftEditReason.value.trim() || null
        })
      });
      const body = await resp.json().catch(() => ({}));
      if (!resp.ok || !body.success) {
        throw new Error(body.message || '保存人工修订失败');
      }
      els.draftText.textContent = body.data.editedText;
      els.draftEditText.value = body.data.editedText;
      showToast('人工修订已保存，请继续确认或取消草稿');
    } catch (err) {
      showError('保存人工修订失败：' + err.message);
    } finally {
      hideLoading();
      loadAuditLogs();
    }
  }

  async function loadAuditLogs() {
    try {
      const resp = await fetch(API_BASE + '/audit-logs?page=0&size=10');
      if (resp.status === 403) {
        els.auditTbody.innerHTML = '<tr><td colspan="5">审计记录仅管理员和审计员可查看</td></tr>';
        return;
      }
      if (!resp.ok) {
        els.auditTbody.innerHTML = '<tr><td colspan="5">审计记录暂时无法加载</td></tr>';
        return;
      }
      const body = await resp.json();
      if (!body.success || !body.data) return;

      const items = body.data.content || [];
      els.auditTbody.innerHTML = (Array.isArray(items) ? items : []).map(log => {
        const time = log.createdAt ? new Date(log.createdAt).toLocaleString('zh-CN') : '-';
        const rawEventType = log.eventType;
        const eventType = supportLabel(rawEventType);
        const category = supportLabel(log.category);
        const urgency = supportLabel(log.urgency);
        const risk = supportLabel(log.riskLevel);
        return `<tr>
          <td>${escapeHtml(time)}</td>
          <td><span class="badge badge-${eventBadgeClass(rawEventType)}">${escapeHtml(eventType)}</span></td>
          <td>${escapeHtml(category)}</td>
          <td>${escapeHtml(urgency)}</td>
          <td>${escapeHtml(risk)}</td>
        </tr>`;
      }).join('');
    } catch (err) {
      console.error('客服审计记录加载失败', err);
    }
  }

  function eventBadgeClass(eventType) {
    switch (eventType) {
      case 'CLASSIFIED': return 'info';
      case 'DRAFTED': return 'pass';
      case 'CONFIRMED': return 'pass';
      case 'CANCELED': return 'warn';
      case 'NEEDS_HUMAN': return 'warn';
      case 'FAILED': return 'fail';
      default: return '';
    }
  }

  // ── Render ────────────────────────────────────────────────────

  function renderResult(data) {
    // Classification
    els.classificationPanel.hidden = false;
    els.category.textContent = supportLabel(data.category);
    els.category.className = 'badge badge-' + categoryBadgeClass(data.category);
    els.sentiment.textContent = supportLabel(data.sentiment);
    els.sentiment.className = 'badge badge-' + sentimentBadgeClass(data.sentiment);
    els.urgency.textContent = supportLabel(data.urgency);
    els.urgency.className = 'badge badge-' + urgencyBadgeClass(data.urgency);
    const hasEvidence = data.evidence && data.evidence.length > 0;
    els.needsHuman.textContent = data.needsHuman
      ? (hasEvidence ? '有建议，需人工复核' : '无依据，需人工处理')
      : '可生成建议';
    els.needsHuman.className = 'badge ' + (data.needsHuman ? 'badge-warn' : 'badge-pass');
    els.summary.textContent = data.summary || '-';

    if (data.classification && data.classification.reasons && data.classification.reasons.length > 0) {
      els.reasonsDiv.hidden = false;
      els.reasonsList.innerHTML = data.classification.reasons.map(r => `<li>${escapeHtml(r)}</li>`).join('');
    } else if (data.reasons && data.reasons.length > 0) {
      els.reasonsDiv.hidden = false;
      els.reasonsList.innerHTML = data.reasons.map(r => `<li>${escapeHtml(r)}</li>`).join('');
    }

    // Evidence
    if (data.evidence && data.evidence.length > 0) {
      els.evidencePanel.hidden = false;
      els.evidenceEmpty.hidden = true;
      els.evidenceList.innerHTML = data.evidence.map(e => `
        <li>
          <strong>${escapeHtml(e.sourceTitle || '未知')}</strong>
          ${e.sectionTitle ? ' > ' + escapeHtml(e.sectionTitle) : ''}
          <span class="badge badge-info">相似度: ${(e.similarity * 100).toFixed(0)}%</span>
          <p class="snippet">${escapeHtml(e.snippet || '')}</p>
        </li>
      `).join('');
    } else {
      els.evidencePanel.hidden = false;
      els.evidenceEmpty.hidden = false;
      els.evidenceEmptyText.textContent = data.knowledgeReason
        || '已连接知识库，但没有检索到与当前工单相关的依据，建议补充知识或转人工。';
      els.evidenceList.innerHTML = '';
    }

    // Draft
    if (data.draft) {
      renderDraft(data.draft);
    } else {
      els.draftPanel.hidden = true;
    }

    scrollResultIntoView(els.classificationPanel);
  }

  function renderDraft(draft) {
    els.draftPanel.hidden = false;

    if (draft.draftId) {
      els.draftText.innerHTML = escapeHtml(draft.replyText || '').replace(/\n/g, '<br>');
    } else if (draft.needsHuman) {
      els.draftText.textContent = '该工单需要人工处理（无足够知识依据或高风险）。';
    } else {
      els.draftText.textContent = draft.replyText || '';
    }

    // Risk badge
    els.draftRiskBadge.textContent = supportLabel(draft.riskLevel || 'MEDIUM');
    els.draftRiskBadge.className = 'badge badge-' + riskBadgeClass(draft.riskLevel);

    // Needs human
    if (draft.needsHuman || (draft.riskReasons && draft.riskReasons.length > 0)) {
      els.draftNeedsHuman.hidden = false;
      if (draft.riskReasons && draft.riskReasons.length > 0) {
        els.draftRiskReasons.innerHTML = draft.riskReasons.map(r => `<li>${escapeHtml(r)}</li>`).join('');
      }
    } else {
      els.draftNeedsHuman.hidden = true;
    }

    // Citations
    if (draft.citations && draft.citations.length > 0) {
      els.draftCitationsDiv.hidden = false;
      els.draftCitationsList.innerHTML = draft.citations.map(c => `
        <li>
          <span class="badge">分片：${escapeHtml(c.chunkId || '-')}</span>
          ${escapeHtml(c.reason || '')}
        </li>
      `).join('');
    } else {
      els.draftCitationsDiv.hidden = true;
    }

    // Actions (only show if we have a token)
    if (draft.confirmationToken && draft.draftId) {
      els.draftActions.hidden = false;
      els.draftEditor.hidden = false;
      els.draftId.value = draft.draftId;
      els.confirmationToken.value = draft.confirmationToken;
      els.draftEditText.value = draft.replyText || '';
      els.draftEditReason.value = '';
    } else {
      els.draftActions.hidden = true;
      els.draftEditor.hidden = true;
    }
  }

  function clearResults() {
    els.classificationPanel.hidden = true;
    els.evidencePanel.hidden = true;
    els.draftPanel.hidden = true;
    els.evidenceEmpty.hidden = true;
    els.evidenceList.innerHTML = '';
    els.draftActions.hidden = true;
    els.draftEditor.hidden = true;
    els.reasonsDiv.hidden = true;
    els.reasonsList.innerHTML = '';
    els.draftNeedsHuman.hidden = true;
    els.draftRiskReasons.innerHTML = '';
    els.draftCitationsDiv.hidden = true;
    els.draftCitationsList.innerHTML = '';
  }

  // ── Badge helpers ─────────────────────────────────────────────

  function categoryBadgeClass(cat) {
    switch ((cat || '').toUpperCase()) {
      case 'REFUND': case 'ACCOUNT_SECURITY': return 'fail';
      case 'INCIDENT': case 'ACCOUNT_ACTIVATION': return 'warn';
      default: return 'info';
    }
  }

  function sentimentBadgeClass(sent) {
    switch ((sent || '').toUpperCase()) {
      case 'ANGRY': return 'fail';
      case 'FRUSTRATED': return 'warn';
      case 'CONFUSED': return 'info';
      default: return '';
    }
  }

  function urgencyBadgeClass(urg) {
    switch ((urg || '').toUpperCase()) {
      case 'CRITICAL': return 'fail';
      case 'HIGH': return 'warn';
      case 'MEDIUM': return 'info';
      default: return '';
    }
  }

  function riskBadgeClass(risk) {
    switch ((risk || '').toUpperCase()) {
      case 'HIGH': return 'fail';
      case 'MEDIUM': return 'warn';
      default: return 'pass';
    }
  }

  function supportLabel(value) {
    const labels = {
      REFUND: '退款',
      ACCOUNT_ACTIVATION: '账号开通',
      INCIDENT: '故障事件',
      ACCOUNT_SECURITY: '账号安全',
      BILLING: '账单',
      PRODUCT_USAGE: '产品使用',
      OTHER: '其他',
      NEUTRAL: '中性',
      CONFUSED: '困惑',
      FRUSTRATED: '受挫',
      ANGRY: '愤怒',
      LOW: '低',
      MEDIUM: '中',
      HIGH: '高',
      CRITICAL: '紧急',
      CLASSIFIED: '已分类',
      DRAFTED: '已生成草稿',
      DRAFT_EDITED: '已人工修订',
      CONFIRMED: '已确认',
      CANCELED: '已取消',
      NEEDS_HUMAN: '需人工处理',
      FAILED: '失败'
    };
    return labels[value] || value || '-';
  }

  // ── UI utilities ──────────────────────────────────────────────

  function showLoading(msg) {
    const overlay = $('#loading-overlay');
    if (overlay) {
      overlay.hidden = false;
      const text = $('#loading-text');
      if (text) text.textContent = msg || '处理中…';
    }
  }

  function hideLoading() {
    const overlay = $('#loading-overlay');
    if (overlay) overlay.hidden = true;
  }

  function showError(msg) {
    const toast = $('#error-toast');
    if (toast) {
      toast.textContent = msg;
      toast.hidden = false;
      setTimeout(() => { toast.hidden = true; }, 5000);
    }
  }

  function showToast(msg) {
    const toast = $('#error-toast');
    if (toast) {
      toast.textContent = msg;
      toast.dataset.variant = 'success';
      toast.hidden = false;
      setTimeout(() => {
        toast.hidden = true;
      }, 3000);
    }
  }

  function escapeHtml(text) {
    if (!text) return '';
    return String(text)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#039;');
  }

  // ── Init ──────────────────────────────────────────────────────

  window.loadSupportAuditLogs = loadAuditLogs;

  loadAuditLogs();

  console.log('客服助手工作台已就绪');
})();
