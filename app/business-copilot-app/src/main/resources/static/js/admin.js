(function () {
  'use strict';
  let resetIntent = null;
  const nativeFetch = window.fetch.bind(window);
  window.fetch = (input, init = {}) => {
    const options = { ...init };
    const method = String(options.method || 'GET').toUpperCase();
    if (!['GET', 'HEAD', 'OPTIONS', 'TRACE'].includes(method)) {
      const token = document.querySelector('meta[name="_csrf"]')?.content;
      const headerName = document.querySelector('meta[name="_csrf_header"]')?.content;
      const headers = new Headers(options.headers || {});
      if (token && headerName) headers.set(headerName, token);
      options.headers = headers;
    }
    return nativeFetch(input, options);
  };

  document.getElementById('admin-refresh').addEventListener('click', loadDiagnostics);
  document.getElementById('admin-initialize').addEventListener('click', initialize);
  document.getElementById('admin-reset-intent').addEventListener('click', createResetIntent);
  document.getElementById('admin-reset-execute').addEventListener('click', executeReset);
  loadDiagnostics();

  async function loadDiagnostics() {
    try {
      const response = await fetch('/api/admin/diagnostics');
      const payload = await response.json();
      if (!response.ok || !payload.success) throw new Error(payload.message || '诊断加载失败');
      render(payload.data);
    } catch (error) {
      showError(error.message || '诊断加载失败');
    }
  }

  function render(data) {
    text('admin-scenarios', data.enabledScenarios);
    text('admin-knowledge', Object.values(data.knowledgeIndexStates || {}).reduce((a, b) => a + b, 0));
    text('admin-ai-calls', (data.usage || []).reduce((sum, item) => sum + Number(item.calls || 0), 0));
    text('admin-mode', data.runtimeMode);
    renderPairs('admin-modules', data.modules, (value) => value ? '正常' : '停用');
    renderPairs('admin-models', {
      'Chat 提供方': data.models?.provider,
      'Chat 模型': data.models?.chatModel,
      'Embedding 模型': data.models?.embeddingModel,
      'Embedding 维度': data.models?.embeddingDimension
    });
    renderPairs('admin-resilience', {
      'AI 最大并发': data.aiResilience?.maxConcurrentCalls,
      '当前可用并发': data.aiResilience?.availablePermits,
      '业务体验最大并发': data.limits?.maxConcurrentExecutions,
      '单客户端日额度': data.limits?.clientDailyOperations,
      '全站模型日额度': data.limits?.globalDailyModelCalls,
      '熔断状态': JSON.stringify(data.aiResilience?.circuitStates || {})
    });
    renderPairs('admin-visibility', data.knowledgeVisibility || {});
    renderPairs('admin-enterprise', data.enterpriseExpansion || {});
    renderUsage(data.usage || []);
    renderPrompts(data.prompts || []);
    renderJobs(data.demoJobs || []);
  }

  function renderPairs(id, values, formatter = (value) => value ?? '—') {
    const target = document.getElementById(id);
    target.replaceChildren();
    Object.entries(values || {}).forEach(([key, value]) => {
      const row = document.createElement('div');
      row.append(el('span', key), el('strong', formatter(value)));
      target.appendChild(row);
    });
  }

  function renderUsage(values) {
    const tbody = document.getElementById('admin-usage');
    tbody.replaceChildren();
    values.forEach((item) => {
      const row = document.createElement('tr');
      [
        item.usage_date || item.usageDate,
        `${item.call_type || item.callType} / ${item.operation}`,
        item.calls,
        `${item.successes}/${item.failures}`,
        tokenUsage(item),
        `${item.total_latency_ms || item.totalLatencyMs || 0} ms`,
        item.estimated_cost ?? item.estimatedCost ?? '未配置'
      ].forEach((value) => row.appendChild(el('td', value)));
      tbody.appendChild(row);
    });
    if (!values.length) tbody.appendChild(emptyRow(7, '暂无模型调用记录'));
  }

  function tokenUsage(item) {
    const input = item.input_tokens ?? item.inputTokens;
    const output = item.output_tokens ?? item.outputTokens;
    const callType = item.call_type ?? item.callType;
    if (callType === 'embedding' && Number(input || 0) === 0 && Number(output || 0) === 0) {
      return '未提供/未提供';
    }
    return `${input ?? '未提供'}/${output ?? '未提供'}`;
  }

  function renderPrompts(values) {
    const target = document.getElementById('admin-prompts');
    target.replaceChildren();
    values.forEach((item) => {
      const row = document.createElement('div');
      row.append(el('strong', item.name), el('code', item.contentHash));
      target.appendChild(row);
    });
  }

  function renderJobs(values) {
    const tbody = document.getElementById('admin-jobs');
    tbody.replaceChildren();
    values.forEach((item) => {
      const row = document.createElement('tr');
      [
        item.id,
        item.job_type || item.jobType,
        item.status,
        item.requested_by || item.requestedBy,
        formatTime(item.started_at || item.startedAt),
        formatTime(item.finished_at || item.finishedAt)
      ].forEach((value) => row.appendChild(el('td', value || '—')));
      tbody.appendChild(row);
    });
    if (!values.length) tbody.appendChild(emptyRow(6, '暂无维护任务'));
  }

  async function initialize() {
    await maintenance('/api/admin/demo-data/initialize', {}, '初始化任务已创建');
  }

  async function createResetIntent() {
    try {
      const response = await fetch('/api/admin/demo-data/reset-intents', { method: 'POST' });
      const payload = await response.json();
      if (!response.ok || !payload.success) throw new Error(payload.message || '无法创建恢复凭证');
      resetIntent = payload.data;
      const counts = document.getElementById('admin-reset-counts');
      counts.replaceChildren(el('h3', '本次将删除的临时数据'));
      const list = document.createElement('ul');
      Object.entries(resetIntent.willDelete || {}).forEach(([key, value]) =>
        list.appendChild(el('li', `${key}：${value}`)));
      counts.append(list, el('p', `凭证有效期至：${formatTime(resetIntent.expiresAt)}`));
      document.getElementById('admin-reset-confirm').hidden = false;
    } catch (error) {
      showError(error.message || '无法创建恢复凭证');
    }
  }

  async function executeReset() {
    if (!resetIntent) return showError('请先检查恢复范围');
    const confirmationText = document.getElementById('admin-reset-text').value.trim();
    await maintenance('/api/admin/demo-data/reset', {
      resetToken: resetIntent.resetToken,
      confirmationText
    }, '恢复任务已执行');
    resetIntent = null;
    document.getElementById('admin-reset-confirm').hidden = true;
  }

  async function maintenance(url, body, success) {
    const status = document.getElementById('admin-maintenance-status');
    try {
      const response = await fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body)
      });
      const payload = await response.json();
      if (!response.ok || !payload.success) throw new Error(payload.message || '维护操作失败');
      status.textContent = `${success}：${payload.data.id} / ${payload.data.status}`;
      status.className = 'inline-status';
      status.hidden = false;
      await loadDiagnostics();
    } catch (error) {
      status.textContent = error.message || '维护操作失败';
      status.className = 'inline-status error';
      status.hidden = false;
    }
  }

  function text(id, value) {
    document.getElementById(id).textContent = value ?? '—';
  }

  function el(tag, value) {
    const node = document.createElement(tag);
    node.textContent = String(value ?? '—');
    return node;
  }

  function emptyRow(columns, value) {
    const row = document.createElement('tr');
    const cell = el('td', value);
    cell.colSpan = columns;
    row.appendChild(cell);
    return row;
  }

  function formatTime(value) {
    if (!value) return '—';
    return new Date(value).toLocaleString('zh-CN', { hour12: false });
  }

  function showError(message) {
    const toast = document.getElementById('error-toast');
    toast.textContent = message;
    toast.hidden = false;
    setTimeout(() => { toast.hidden = true; }, 5000);
  }
}());
