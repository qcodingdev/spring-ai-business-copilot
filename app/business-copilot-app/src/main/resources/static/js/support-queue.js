/** 客服人工复核队列：按状态、分类、紧急度和风险查看脱敏业务摘要。 */
(function () {
  'use strict';

  const list = document.getElementById('sc-queue-list');
  const refresh = document.getElementById('sc-queue-refresh');
  if (!list || !refresh) return;

  refresh.addEventListener('click', loadQueue);
  ['sc-queue-status', 'sc-queue-category', 'sc-queue-urgency', 'sc-queue-risk']
    .forEach((id) => document.getElementById(id)?.addEventListener('change', loadQueue));
  document.addEventListener('DOMContentLoaded', loadQueue);

  async function loadQueue() {
    refresh.disabled = true;
    list.replaceChildren(node('p', '正在读取复核队列…', 'empty-state'));
    const params = new URLSearchParams({ limit: '30' });
    add(params, 'status', 'sc-queue-status');
    add(params, 'category', 'sc-queue-category');
    add(params, 'urgency', 'sc-queue-urgency');
    add(params, 'riskLevel', 'sc-queue-risk');
    try {
      const response = await fetch(`/api/support-copilot/tickets?${params}`);
      const payload = await response.json().catch(() => ({}));
      if (!response.ok || !payload.success) throw new Error(payload.message || '复核队列加载失败');
      render(payload.data || []);
    } catch (error) {
      list.replaceChildren(node('p', error.message || '复核队列暂时不可用。', 'empty-state'));
    } finally {
      refresh.disabled = false;
    }
  }

  function render(items) {
    list.replaceChildren();
    if (!items.length) {
      list.appendChild(node('p', '当前筛选条件下没有待处理事项。', 'empty-state'));
      return;
    }
    items.forEach((item) => {
      const card = document.createElement('article');
      card.className = 'support-queue-item';
      const heading = document.createElement('div');
      heading.className = 'support-queue-heading';
      const title = document.createElement('div');
      title.append(
        node('strong', item.customerQuestion || '虚构客户问题'),
        node('small', `${label(item.category)} · ${label(item.urgency)} · ${formatTime(item.createdAt)}`)
      );
      const badges = document.createElement('div');
      badges.append(
        badge(label(item.status), statusClass(item.status)),
        badge(item.riskLevel ? `${label(item.riskLevel)}风险` : '风险待评估',
          riskClass(item.riskLevel))
      );
      heading.append(title, badges);
      card.appendChild(heading);
      if (item.suggestedReply) {
        const reply = node('p', item.suggestedReply, 'support-queue-reply');
        reply.prepend(node('strong', '建议回复：'));
        card.appendChild(reply);
      }
      const details = document.createElement('details');
      details.appendChild(node('summary', '查看依据、风险与人工修订'));
      details.appendChild(line('知识依据版本', (item.knowledgeVersions || []).join('、') || '未找到有效依据'));
      details.appendChild(line('风险原因', (item.riskReasons || []).join('；') || '无额外风险'));
      details.appendChild(line('人工修订差异', item.editReason || '尚未人工修订'));
      details.appendChild(line('人工处理结果', label(item.decisionOutcome) || '待处理'));
      card.appendChild(details);
      const actions = actionButtons(item);
      if (actions) card.appendChild(actions);
      list.appendChild(card);
    });
  }

  function actionButtons(item) {
    const actions = document.createElement('div');
    actions.className = 'action-buttons support-queue-actions';
    if (item.draftStatus === 'DRAFTED' || item.draftStatus === 'NEEDS_REVIEW') {
      const confirm = node('button', '确认并回复客户', 'btn-primary');
      confirm.type = 'button';
      confirm.addEventListener('click', () => confirmFromQueue(item, confirm));
      actions.appendChild(confirm);
    }
    if (item.status === 'CONFIRMED') {
      const replied = node('button', '标记已回复客户', 'btn-secondary');
      replied.type = 'button';
      replied.addEventListener('click', () => markCustomerReplied(item, replied));
      actions.appendChild(replied);
    }
    if (!item.draftId && item.status === 'NEEDS_HUMAN') {
      const manuallyReplied = node('button', '标记已人工回复客户', 'btn-primary');
      manuallyReplied.type = 'button';
      manuallyReplied.addEventListener('click', () => recordManualReply(item, manuallyReplied));
      actions.appendChild(manuallyReplied);
    }
    return actions.childElementCount ? actions : null;
  }

  async function confirmFromQueue(item, button) {
    if (!window.confirm('是否已通过实际客服渠道同步回复客户？确定后会确认草稿、标记“已回复客户”并关闭工单。系统不会自动发送消息。')) {
      return;
    }
    button.disabled = true;
    try {
      const sessionResponse = await fetch(`/api/support-copilot/reply-drafts/${item.draftId}/review-session`, {
        method: 'POST'
      });
      const sessionPayload = await sessionResponse.json().catch(() => ({}));
      if (!sessionResponse.ok || !sessionPayload.success) throw new Error(sessionPayload.message || '无法打开复核项');
      const confirmResponse = await fetch(`/api/support-copilot/reply-drafts/${item.draftId}/confirm`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ confirmationToken: sessionPayload.data.confirmationToken })
      });
      const confirmPayload = await confirmResponse.json().catch(() => ({}));
      if (!confirmResponse.ok || !confirmPayload.success) throw new Error(confirmPayload.message || '确认失败');
      const repliedResponse = await fetch(`/api/support-copilot/reply-drafts/${item.draftId}/mark-customer-replied`, {
        method: 'POST'
      });
      const repliedPayload = await repliedResponse.json().catch(() => ({}));
      if (!repliedResponse.ok || !repliedPayload.success) {
        throw new Error(repliedPayload.message || '草稿已确认，但未能记录“已回复客户”');
      }
      notify('已确认草稿并标记“已回复客户”，工单已关闭。');
      await loadQueue();
    } catch (error) {
      notify(error.message || '队列确认失败', true);
      button.disabled = false;
    }
  }

  async function markCustomerReplied(item, button) {
    if (!window.confirm('请确认你已经通过实际客服渠道回复客户。此操作只记录处理完成并关闭工单，不会发送消息。')) {
      return;
    }
    button.disabled = true;
    try {
      const response = await fetch(`/api/support-copilot/reply-drafts/${item.draftId}/mark-customer-replied`, {
        method: 'POST'
      });
      const payload = await response.json().catch(() => ({}));
      if (!response.ok || !payload.success) throw new Error(payload.message || '无法记录已回复状态');
      notify('已记录“已回复客户”，工单已关闭。');
      await loadQueue();
    } catch (error) {
      notify(error.message || '操作失败', true);
      button.disabled = false;
    }
  }

  async function recordManualReply(item, button) {
    if (!window.confirm('是否已通过实际客服渠道同步回复客户？确定后会标记“已回复客户”并关闭工单。系统不会自动发送消息。')) {
      return;
    }
    button.disabled = true;
    try {
      const response = await fetch(
        `/api/support-copilot/tickets/${encodeURIComponent(item.externalReference)}/record-manual-reply`,
        { method: 'POST' }
      );
      const payload = await response.json().catch(() => ({}));
      if (!response.ok || !payload.success) throw new Error(payload.message || '无法记录已回复状态');
      notify('已记录“已回复客户”，工单已关闭。');
      await loadQueue();
    } catch (error) {
      notify(error.message || '操作失败', true);
      button.disabled = false;
    }
  }

  function notify(message, isError = false) {
    if (typeof showError === 'function' && isError) {
      showError(message);
    } else if (typeof showSuccess === 'function' && !isError) {
      showSuccess(message);
    } else {
      window.alert(message);
    }
  }

  function add(params, name, id) {
    const value = document.getElementById(id)?.value;
    if (value) params.set(name, value);
  }

  function line(title, value) {
    const row = document.createElement('p');
    row.append(node('strong', `${title}：`), document.createTextNode(value));
    return row;
  }

  function badge(text, className) {
    return node('span', text || '—', `badge ${className || 'badge-info'}`);
  }

  function node(tag, text, className) {
    const element = document.createElement(tag);
    element.textContent = text || '';
    if (className) element.className = className;
    return element;
  }

  function label(value) {
    return ({
      RECEIVED: '待处理', CLASSIFIED: '已完成分类', NEEDS_HUMAN: '待人工复核',
      DRAFTED: '待确认', CONFIRMED: '已确认', CANCELED: '已取消',
      CLOSED: '已关闭', FAILED: '处理失败', EXPIRED: '已过期', REFUND: '退款',
      INCIDENT: '故障', ACCOUNT_SECURITY: '账号安全', BILLING: '账单',
      PRODUCT_USAGE: '产品使用', ACCOUNT_ACTIVATION: '账号开通',
      CRITICAL: '紧急', HIGH: '高', MEDIUM: '中', LOW: '低',
      PENDING: '待处理', ACCEPTED: '已接受', EDITED_ACCEPTED: '修订后接受',
      REJECTED: '已拒绝', OTHER: '其他'
    })[value] || value || '';
  }

  function statusClass(status) {
    if (status === 'CONFIRMED') return 'badge-pass';
    if (status === 'CANCELED' || status === 'EXPIRED') return 'badge-muted';
    return 'badge-warn';
  }

  function riskClass(risk) {
    if (risk === 'HIGH') return 'badge-danger';
    if (risk === 'MEDIUM') return 'badge-warn';
    return 'badge-pass';
  }

  function formatTime(value) {
    return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—';
  }
}());
