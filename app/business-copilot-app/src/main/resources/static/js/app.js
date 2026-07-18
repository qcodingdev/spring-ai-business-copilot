// Data Copilot 前端逻辑：原生 fetch，无框架依赖。
// 三条主路径：生成 SQL、确认执行、错误展示。

const API_BASE = '/api/data-copilot';

// 为所有非安全的同源请求自动添加 Spring Security CSRF 请求头。
const nativeFetch = window.fetch.bind(window);
window.fetch = (input, init = {}) => {
  const options = { ...init };
  const method = String(options.method || 'GET').toUpperCase();
  if (!['GET', 'HEAD', 'OPTIONS', 'TRACE'].includes(method)) {
    const token = document.querySelector('meta[name="_csrf"]')?.content;
    const headerName = document.querySelector('meta[name="_csrf_header"]')?.content;
    if (token && headerName) {
      const headers = new Headers(options.headers || {});
      headers.set(headerName, token);
      options.headers = headers;
    }
  }
  return nativeFetch(input, options);
};

// ---- DOM 辅助函数 ----
const $ = (id) => document.getElementById(id);
const show = (el) => el && (el.hidden = false);
const hide = (el) => el && (el.hidden = true);

/**
 * 在异步结果完成渲染后，将视口定位到结果区的第一个面板。
 * 尊重系统“减少动态效果”偏好，避免强制平滑动画。
 */
function scrollResultIntoView(element) {
  if (!element) return;
  window.requestAnimationFrame(() => {
    const reduceMotion = window.matchMedia
      && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    element.scrollIntoView({
      behavior: reduceMotion ? 'auto' : 'smooth',
      block: 'start'
    });
  });
}

function setLoading(text) {
  if (text) {
    $('loading-text').textContent = text;
    show($('loading-overlay'));
    document.body.setAttribute('aria-busy', 'true');
  } else {
    hide($('loading-overlay'));
    document.body.removeAttribute('aria-busy');
  }
}

function showError(message) {
  const toast = $('error-toast');
  toast.style.background = '';
  toast.style.color = '';
  toast.style.border = '';
  toast.textContent = message;
  show(toast);
  setTimeout(() => hide(toast), 5000);
}

function showSuccess(message) {
  const toast = $('error-toast');
  toast.style.background = '#d4edda';
  toast.style.color = '#155724';
  toast.style.border = '1px solid #c3e6cb';
  toast.textContent = message;
  show(toast);
  setTimeout(() => {
    hide(toast);
    toast.style.background = '';
    toast.style.color = '';
    toast.style.border = '';
  }, 3000);
}

function clearError() {
  hide($('error-toast'));
}

// ---- 生成 SQL ----
$('generate-btn').addEventListener('click', async () => {
  const question = $('question-input').value.trim();
  if (!question) {
    showError('请输入业务问题');
    return;
  }

  setLoading('生成 SQL 中…');
  clearError();
  resetCandidatePanels();

  try {
    const res = await fetch(`${API_BASE}/sql-candidates`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ question })
    });
    const payload = await res.json();

    if (!res.ok || !payload.success) {
      showError(formatError(payload));
      return;
    }

    renderCandidate(payload.data);
  } catch (e) {
    showError('网络错误，请重试');
  } finally {
    setLoading(null);
  }
});

function renderCandidate(data) {
  // SQL 候选区
  $('candidate-sql').textContent = data.sql || '';
  $('candidate-summary').textContent = data.summary || '';
  toggleList('assumptions-row', 'candidate-assumptions', data.assumptions);
  toggleList('warnings-row', 'candidate-warnings', data.warnings);
  show($('candidate-panel'));

  // Guardrails 区
  show($('guardrails-panel'));
  if (data.executable) {
    show($('guardrails-pass'));
    hide($('guardrails-fail'));
  } else {
    hide($('guardrails-pass'));
    show($('guardrails-fail'));
    const ul = $('guardrails-violations');
    ul.innerHTML = '';
    (data.validation?.violations || []).forEach((v) => {
      const li = document.createElement('li');
      li.textContent = v;
      ul.appendChild(li);
    });
  }

  // 确认执行按钮：只有 executable 时启用，并保存 token
  $('candidate-id').value = data.candidateId || '';
  $('confirmation-token').value = data.confirmationToken || '';
  const confirmPanel = $('confirm-panel');
  const executeBtn = $('execute-btn');
  show(confirmPanel);
  executeBtn.disabled = !data.executable;

  scrollResultIntoView($('candidate-panel'));
}

function toggleList(rowId, contentId, items) {
  if (items && items.length) {
    $(contentId).textContent = items.join('；');
    show($(rowId));
  } else {
    hide($(rowId));
  }
}

// ---- 确认执行：只传 confirmationToken，不传 SQL ----
$('execute-btn').addEventListener('click', async () => {
  const candidateId = $('candidate-id').value;
  const confirmationToken = $('confirmation-token').value;
  if (!candidateId || !confirmationToken) {
    showError('缺少确认凭证');
    return;
  }

  setLoading('执行查询中…');
  clearError();

  try {
    const res = await fetch(`${API_BASE}/sql-candidates/${candidateId}/execute`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      // 只传 confirmationToken，绝不传 SQL
      body: JSON.stringify({ confirmationToken })
    });
    const payload = await res.json();

    if (!res.ok || !payload.success) {
      showError(formatError(payload));
      return;
    }

    renderResult(payload.data);
  } catch (e) {
    showError('网络错误，请重试');
  } finally {
    setLoading(null);
    loadAuditLogs();
  }
});

function renderResult(data) {
  const table = data.table;
  const rowCount = table.rowCount;
  const empty = rowCount === 0;

  // 结果表格
  $('result-row-count').textContent = `${rowCount} 行`;
  const thead = $('result-thead');
  const tbody = $('result-tbody');
  thead.innerHTML = '';
  tbody.innerHTML = '';

  if (table.columns && table.columns.length) {
    const tr = document.createElement('tr');
    table.columns.forEach((col) => {
      const th = document.createElement('th');
      th.textContent = col.name;
      tr.appendChild(th);
    });
    thead.appendChild(tr);
  }

  if (empty) {
    // 空结果友好状态
    show($('result-empty'));
  } else {
    hide($('result-empty'));
    (table.rows || []).forEach((row) => {
      const tr = document.createElement('tr');
      (table.columns || []).forEach((col) => {
        const td = document.createElement('td');
        const val = row.values[col.name];
        td.textContent = val === null || val === undefined ? '' : String(val);
        tr.appendChild(td);
      });
      tbody.appendChild(tr);
    });
  }

  // 截断标记
  if (table.truncated) {
    show($('result-truncated'));
  } else {
    hide($('result-truncated'));
  }

  show($('result-panel'));

  // AI 解释
  if (data.explanation) {
    $('explanation-text').textContent = data.explanation.explanation || '';
    if (data.explanation.degraded) {
      show($('explanation-degraded'));
    } else {
      hide($('explanation-degraded'));
    }
    show($('explanation-panel'));
  }

  scrollResultIntoView($('result-panel'));
}

// ---- 审计记录预览 ----
async function loadAuditLogs() {
  try {
    const res = await fetch(`${API_BASE}/audit-logs?page=0&size=10`);
    const payload = await res.json();
    if (res.status === 403) {
      $('audit-tbody').innerHTML = '<tr><td colspan="4">审计记录仅管理员和审计员可查看</td></tr>';
      return;
    }
    if (!res.ok) {
      $('audit-tbody').innerHTML = '<tr><td colspan="4">审计记录暂时无法加载</td></tr>';
      return;
    }
    if (payload.success && payload.data) {
      const tbody = $('audit-tbody');
      tbody.innerHTML = '';
      payload.data.forEach((log) => {
        const tr = document.createElement('tr');
        tr.appendChild(td(formatTime(log.createdAt)));
        tr.appendChild(td(log.userQuestion || '—'));
        tr.appendChild(td(dataStatusLabel(log.executionStatus || log.validationStatus)));
        tr.appendChild(td(log.rowCount == null ? '—' : String(log.rowCount)));
        tbody.appendChild(tr);
      });
    }
  } catch (e) {
    $('audit-tbody').innerHTML = '<tr><td colspan="4">审计记录暂时无法加载</td></tr>';
  }
}

function td(text) {
  const cell = document.createElement('td');
  cell.textContent = text;
  return cell;
}

function formatTime(iso) {
  if (!iso) return '—';
  try {
    return new Date(iso).toLocaleString('zh-CN', { hour12: false });
  } catch {
    return iso;
  }
}

function dataStatusLabel(status) {
  const labels = {
    PENDING_EXECUTION: '等待执行',
    GENERATED: '已生成',
    GUARDRAIL_FAILED: '安全校验失败',
    NOT_CONFIRMED: '未确认',
    EXECUTED: '执行成功',
    EXECUTION_FAILED: '执行失败',
    EXPLANATION_FAILED: '解释生成失败',
    PASSED: '已通过',
    FAILED: '失败'
  };
  return labels[status] || status || '—';
}

// ---- 错误格式化：不展示堆栈 ----
function formatError(payload) {
  if (!payload) return '未知错误';
  if (payload.data && payload.data.fieldErrors && payload.data.fieldErrors.length) {
    return payload.data.fieldErrors.map((f) => `${f.field}: ${f.message}`).join('；');
  }
  return payload.message || '请求失败';
}

function resetCandidatePanels() {
  hide($('candidate-panel'));
  hide($('guardrails-panel'));
  hide($('confirm-panel'));
  hide($('result-panel'));
  hide($('explanation-panel'));
}

// ── 模块切换 ──────────────────────────────────────────────────
const moduleTabs = Array.from(document.querySelectorAll('.tab-bar .tab'));

function activateModule(tab, updateLocation = true) {
  if (!tab) return;

  moduleTabs.forEach((item) => {
    const active = item === tab;
    item.classList.toggle('active', active);
    item.setAttribute('aria-selected', String(active));
    item.tabIndex = active ? 0 : -1;
  });

  const target = tab.dataset.tab;
  document.querySelectorAll('.tab-content').forEach((content) => {
    content.hidden = content.id !== `tab-${target}`;
  });

  $('active-module-title').textContent = tab.dataset.title || '';
  $('active-module-description').textContent = tab.dataset.description || '';
  document.title = `${tab.dataset.title || 'QCoding AI Copilot'} · QCoding AI Copilot`;

  if (updateLocation) {
    history.replaceState(null, '', `#${target}`);
    try {
      localStorage.setItem('business-copilot.active-module', target);
    } catch {
      // 浏览器禁用存储时不影响模块切换。
    }
  }

  if (target === 'support-copilot' && typeof window.loadSupportAuditLogs === 'function') {
    window.loadSupportAuditLogs();
  }
}

moduleTabs.forEach((tab, index) => {
  tab.addEventListener('click', () => activateModule(tab));
  tab.addEventListener('keydown', (event) => {
    if (!['ArrowDown', 'ArrowRight', 'ArrowUp', 'ArrowLeft', 'Home', 'End'].includes(event.key)) {
      return;
    }
    event.preventDefault();
    let nextIndex = index;
    if (event.key === 'ArrowDown' || event.key === 'ArrowRight') nextIndex = (index + 1) % moduleTabs.length;
    if (event.key === 'ArrowUp' || event.key === 'ArrowLeft') nextIndex = (index - 1 + moduleTabs.length) % moduleTabs.length;
    if (event.key === 'Home') nextIndex = 0;
    if (event.key === 'End') nextIndex = moduleTabs.length - 1;
    moduleTabs[nextIndex].focus();
    activateModule(moduleTabs[nextIndex]);
  });
});

// 示例问题快捷填充。
document.querySelectorAll('.prompt-chip[data-question]').forEach((chip) => {
  chip.addEventListener('click', () => {
    const targetId = chip.dataset.target || 'question-input';
    const input = document.getElementById(targetId);
    if (!input) return;
    input.value = chip.dataset.question || '';
    input.dispatchEvent(new Event('input', { bubbles: true }));
    input.focus();
  });
});

// 支持刷新后保留当前模块，同时允许通过 URL hash 直接打开。
let initialModule = window.location.hash.replace(/^#/, '');
if (!moduleTabs.some((tab) => tab.dataset.tab === initialModule)) {
  try {
    initialModule = localStorage.getItem('business-copilot.active-module') || '';
  } catch {
    initialModule = '';
  }
}
activateModule(
  moduleTabs.find((tab) => tab.dataset.tab === initialModule) || moduleTabs[0],
  false
);

window.addEventListener('hashchange', () => {
  const moduleName = window.location.hash.replace(/^#/, '');
  const tab = moduleTabs.find((item) => item.dataset.tab === moduleName);
  if (tab) activateModule(tab, false);
});

// 初始加载审计记录
loadAuditLogs();
