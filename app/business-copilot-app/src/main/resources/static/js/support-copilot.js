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
    evidenceList: $('#sc-evidence-list'),

    draftPanel: $('#sc-draft-panel'),
    draftText: $('#sc-draft-text'),
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
    refund: [
      "我昨天购买了年费会员，扣了 368 元，但发现不是我要的功能，我要全额退款！订单号是 ORD-2026-0701-001，请马上处理。",
      "上个月买的商品质量有问题，用了两周就坏了，我需要退货退款，请问怎么走流程？",
      "想了解一下如果我在试用期内取消订阅，会不会产生任何费用？退款政策怎么规定？",
      "你们的退款条款完全是霸王条款！我申请了三次退款都不给我退，我要去消费者协会投诉！"
    ],
    activation: [
      "我刚刚付款成功了，订单号 ACT-2026-0708-001，但已经等了 2 小时功能还没开通，这是什么情况？",
      "我们公司刚购买了团队版，管理员说需要帮我开通子账号，请问怎么操作？",
      "我收到激活邮件说点击链接激活，但是我点进去一直提示链接已过期，能不能重新发一封？",
      "我是付费用户，但今天登录突然提示账号未激活，所有数据都看不到了。这是你们系统 bug 吗？"
    ],
    incident: [
      "从今天上午 10 点开始，我们的 API 调用一直返回 500 错误，已经影响了业务正常运营。请马上排查！",
      "刚刚系统突然改版了，好多按钮找不到了，原来的导出功能在哪里？这是不是 bug？",
      "数据库主库挂了！！我们的核心业务完全停了，客户正在投诉。这是 P0 级生产事故！"
    ],
    security: [
      "我的账号今天收到了 3 次异地登录提醒，分别在深圳、上海、北京，是不是账号被盗了？",
      "我同事离职了，但他名下有几个重要项目的权限还没转移，他的账号应该怎么处理？",
      "我不小心把 API Key 提交到了公开的 GitHub 仓库，请立即 revoke 这个 key 并重新生成。"
    ]
  };

  function pickRandom(arr) {
    return arr[Math.floor(Math.random() * arr.length)];
  }

  // ── Event listeners ───────────────────────────────────────────

  els.analyzeBtn.addEventListener('click', analyzeTicket);

  els.sampleBtns.addEventListener('click', (e) => {
    const btn = e.target.closest('.sample-btn');
    if (!btn) return;
    const category = btn.dataset.category;
    const tickets = sampleTickets[category];
    if (tickets) {
      els.ticketInput.value = pickRandom(tickets);
    }
  });

  els.confirmBtn.addEventListener('click', () => {
    const draftId = els.draftId.value;
    const token = els.confirmationToken.value;
    if (!draftId || !token) return;
    confirmDraft(draftId, token);
  });

  els.cancelBtn.addEventListener('click', () => {
    const draftId = els.draftId.value;
    const token = els.confirmationToken.value;
    if (!draftId || !token) return;
    cancelDraft(draftId, token);
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
    showLoading('正在确认...');
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
        els.draftActions.hidden = true;
        showToast('草稿已确认');
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

  async function loadAuditLogs() {
    try {
      const resp = await fetch(API_BASE + '/audit-logs?page=0&size=10');
      if (!resp.ok) return;
      const body = await resp.json();
      if (!body.success || !body.data) return;

      const items = body.data.items || body.data;
      els.auditTbody.innerHTML = (Array.isArray(items) ? items : []).map(log => {
        const time = log.createdAt ? new Date(log.createdAt).toLocaleString('zh-CN') : '-';
        const eventType = log.eventType || '-';
        const category = log.category || '-';
        const urgency = log.urgency || '-';
        const risk = log.riskLevel || '-';
        return `<tr>
          <td>${escapeHtml(time)}</td>
          <td><span class="badge badge-${eventBadgeClass(eventType)}">${escapeHtml(eventType)}</span></td>
          <td>${escapeHtml(category)}</td>
          <td>${escapeHtml(urgency)}</td>
          <td>${escapeHtml(risk)}</td>
        </tr>`;
      }).join('');
    } catch (err) {
      console.error('Failed to load audit logs', err);
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
    els.category.textContent = data.category || '-';
    els.category.className = 'badge badge-' + categoryBadgeClass(data.category);
    els.sentiment.textContent = data.sentiment || '-';
    els.sentiment.className = 'badge badge-' + sentimentBadgeClass(data.sentiment);
    els.urgency.textContent = data.urgency || '-';
    els.urgency.className = 'badge badge-' + urgencyBadgeClass(data.urgency);
    els.needsHuman.textContent = data.needsHuman ? '需要转人工' : '可自动处理';
    els.needsHuman.className = 'badge ' + (data.needsHuman ? 'badge-warn' : 'badge-pass');
    els.summary.textContent = data.summary || '-';

    if (data.classification && data.classification.reasons && data.classification.reasons.length > 0) {
      els.reasonsDiv.hidden = false;
      els.reasonsList.innerHTML = data.classification.reasons.map(r => `<li>${escapeHtml(r)}</li>`).join('');
    } else if (data.reasons && data.reasons.length > 0) {
      // reasons may be at top level
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
      els.evidenceList.innerHTML = '';
    }

    // Draft
    if (data.draft) {
      renderDraft(data.draft);
    } else {
      els.draftPanel.hidden = true;
    }
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
    els.draftRiskBadge.textContent = draft.riskLevel || 'MEDIUM';
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
          <span class="badge">Chunk: ${escapeHtml(c.chunkId || '-')}</span>
          ${escapeHtml(c.reason || '')}
        </li>
      `).join('');
    } else {
      els.draftCitationsDiv.hidden = true;
    }

    // Actions (only show if we have a token)
    if (draft.confirmationToken && draft.draftId) {
      els.draftActions.hidden = false;
      els.draftId.value = draft.draftId;
      els.confirmationToken.value = draft.confirmationToken;
    } else {
      els.draftActions.hidden = true;
    }
  }

  function clearResults() {
    els.classificationPanel.hidden = true;
    els.evidencePanel.hidden = true;
    els.draftPanel.hidden = true;
    els.evidenceEmpty.hidden = true;
    els.evidenceList.innerHTML = '';
    els.draftActions.hidden = true;
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
      toast.hidden = false;
      toast.style.background = '#d4edda';
      toast.style.color = '#155724';
      toast.style.border = '1px solid #c3e6cb';
      setTimeout(() => {
        toast.hidden = true;
        toast.style.background = '';
        toast.style.color = '';
        toast.style.border = '';
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

  // Tab switching
  document.querySelectorAll('.tab').forEach(tab => {
    tab.addEventListener('click', () => {
      document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
      document.querySelectorAll('.tab-content').forEach(tc => tc.hidden = true);
      tab.classList.add('active');
      const target = $('#tab-' + tab.dataset.tab);
      if (target) target.hidden = false;

      if (tab.dataset.tab === 'support-copilot') {
        loadAuditLogs();
      }
    });
  });

  loadAuditLogs();

  console.log('Support Copilot ready');
})();
