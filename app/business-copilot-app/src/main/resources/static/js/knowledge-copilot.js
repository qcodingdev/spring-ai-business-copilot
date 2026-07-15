// Knowledge Copilot 前端逻辑：原生 fetch，无框架依赖。
// 功能：文档上传/列表/启用停用、知识问答、审计日志预览。

const KC_API_BASE = '/api/knowledge-copilot';

// ---- DOM helpers (reuse app.js helpers where available) ----
function kcSetLoading(text) {
  if (text) {
    document.getElementById('loading-text').textContent = text;
    show(document.getElementById('loading-overlay'));
  } else {
    hide(document.getElementById('loading-overlay'));
  }
}

function kcShowError(message) {
  const toast = document.getElementById('error-toast');
  toast.textContent = message;
  show(toast);
  setTimeout(() => hide(toast), 5000);
}

// ═══════════════════════════════════════════════════════════════
// 文档上传
// ═══════════════════════════════════════════════════════════════
document.getElementById('kc-upload-btn').addEventListener('click', async () => {
  const fileName = document.getElementById('kc-file-name').value.trim();
  const content = document.getElementById('kc-file-content').value.trim();
  const category = document.getElementById('kc-file-category').value.trim();

  if (!fileName) { kcShowError('请输入文件名'); return; }
  if (!content) { kcShowError('请输入文档内容'); return; }

  const statusEl = document.getElementById('kc-upload-status');
  kcSetLoading('上传文档中…');

  try {
    const res = await fetch(`${KC_API_BASE}/documents`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ fileName, content, category })
    });
    const payload = await res.json();

    if (!res.ok || !payload.success) {
      statusEl.textContent = payload.message || '上传失败';
      statusEl.className = 'inline-status error';
      show(statusEl);
      return;
    }

    if (payload.data.indexed) {
      statusEl.textContent = `上传成功！文档 ID=${payload.data.documentId}，${payload.data.chunkCount} 个分片，已建立向量索引`;
      statusEl.className = 'inline-status';
    } else {
      statusEl.textContent = `文档已保存（ID=${payload.data.documentId}），但尚未建立向量索引；配置 Embedding 模型后请点击“重建索引”`;
      statusEl.className = 'inline-status error';
    }
    show(statusEl);

    // 清空表单
    document.getElementById('kc-file-name').value = '';
    document.getElementById('kc-file-category').value = '';
    document.getElementById('kc-file-content').value = '';

    // 刷新文档列表
    kcLoadDocuments();
  } catch (e) {
    kcShowError('网络错误，请重试');
  } finally {
    kcSetLoading(null);
  }
});

// ═══════════════════════════════════════════════════════════════
// 文档列表
// ═══════════════════════════════════════════════════════════════
async function kcLoadDocuments() {
  try {
    const res = await fetch(`${KC_API_BASE}/documents`);
    const payload = await res.json();
    const tbody = document.getElementById('kc-docs-tbody');
    tbody.innerHTML = '';

    if (!payload.success || !payload.data || !payload.data.length) {
      show(document.getElementById('kc-docs-empty'));
      document.getElementById('kc-docs-count').textContent = '0';
      return;
    }

    hide(document.getElementById('kc-docs-empty'));
    const docs = payload.data;
    document.getElementById('kc-docs-count').textContent = String(docs.length);

    docs.forEach((doc) => {
      const tr = document.createElement('tr');
      tr.appendChild(td(doc.id != null ? String(doc.id) : '—'));
      tr.appendChild(td(doc.title || '—'));
      tr.appendChild(td(doc.category || '—'));
      tr.appendChild(td(doc.sourceType || '—'));
      // 启用/停用 toggle
      const toggleTd = document.createElement('td');
      const wrapper = document.createElement('label');
      wrapper.className = 'toggle-switch';
      const checkbox = document.createElement('input');
      checkbox.type = 'checkbox';
      checkbox.checked = doc.enabled;
      checkbox.addEventListener('change', () => kcToggleEnabled(doc.id, checkbox.checked, checkbox));
      const slider = document.createElement('span');
      slider.className = 'toggle-slider';
      wrapper.appendChild(checkbox);
      wrapper.appendChild(slider);
      toggleTd.appendChild(wrapper);
      tr.appendChild(toggleTd);

      tr.appendChild(td(formatTime(doc.createdAt)));
      const actionTd = document.createElement('td');
      const reindexButton = document.createElement('button');
      reindexButton.type = 'button';
      reindexButton.className = 'btn-secondary table-action-button';
      reindexButton.textContent = '重建索引';
      reindexButton.addEventListener('click', () => kcReindexDocument(doc.id, reindexButton));
      actionTd.appendChild(reindexButton);
      tr.appendChild(actionTd);
      tbody.appendChild(tr);
    });
  } catch (e) {
    // 静默处理
  }
}

async function kcReindexDocument(documentId, button) {
  button.disabled = true;
  kcSetLoading('重建向量索引中…');
  try {
    const res = await fetch(`${KC_API_BASE}/documents/${documentId}/reindex`, { method: 'POST' });
    const payload = await res.json();
    if (!res.ok || !payload.success) {
      kcShowError(payload.message || '重建索引失败');
      return;
    }
    kcLoadDocuments();
  } catch (e) {
    kcShowError('网络错误');
  } finally {
    button.disabled = false;
    kcSetLoading(null);
  }
}

// ═══════════════════════════════════════════════════════════════
// 文档启用/停用
// ═══════════════════════════════════════════════════════════════
async function kcToggleEnabled(documentId, enabled, checkbox) {
  try {
    const res = await fetch(`${KC_API_BASE}/documents/${documentId}/enabled`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ enabled })
    });
    if (!res.ok) {
      // 回滚 checkbox
      checkbox.checked = !enabled;
      kcShowError('更新文档状态失败');
    }
  } catch (e) {
    checkbox.checked = !enabled;
    kcShowError('网络错误');
  }
}

// ═══════════════════════════════════════════════════════════════
// 知识问答
// ═══════════════════════════════════════════════════════════════
document.getElementById('kc-ask-btn').addEventListener('click', async () => {
  const question = document.getElementById('kc-question-input').value.trim();
  if (!question) { kcShowError('请输入问题'); return; }

  kcSetLoading('检索并生成答案中…');
  hide(document.getElementById('kc-answer-panel'));
  hide(document.getElementById('kc-citations-panel'));
  hide(document.getElementById('kc-warnings-panel'));

  try {
    const res = await fetch(`${KC_API_BASE}/questions`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ question })
    });
    const payload = await res.json();

    if (!res.ok || !payload.success) {
      kcShowError(payload.message || '问答请求失败');
      return;
    }

    kcRenderAnswer(payload.data);
    kcLoadAuditLogs();
  } catch (e) {
    kcShowError('网络错误，请重试');
  } finally {
    kcSetLoading(null);
  }
});

function kcRenderAnswer(data) {
  const statusPanel = document.getElementById('kc-answer-panel');
  const answerText = document.getElementById('kc-answer-text');
  const noEvidence = document.getElementById('kc-answer-no-evidence');
  const statusBadge = document.getElementById('kc-answer-status-badge');
  const citationsPanel = document.getElementById('kc-citations-panel');
  const citationsList = document.getElementById('kc-citations-list');
  const warningsPanel = document.getElementById('kc-warnings-panel');
  const warningsList = document.getElementById('kc-warnings-list');

  // 状态徽章
  statusBadge.textContent = data.status;
  statusBadge.className = 'badge';
  if (data.status === 'ANSWERED') {
    statusBadge.classList.add('badge-pass');
  } else if (data.status === 'NO_EVIDENCE') {
    statusBadge.classList.add('badge-warn');
  } else {
    statusBadge.classList.add('badge-fail');
  }
  show(statusPanel);

  // 答案内容
  if (data.status === 'ANSWERED' && data.answer) {
    answerText.textContent = data.answer;
    show(answerText);
    hide(noEvidence);
  } else if (data.status === 'NO_EVIDENCE') {
    hide(answerText);
    show(noEvidence);
  } else {
    // REJECTED 等
    answerText.textContent = data.warnings && data.warnings.length
      ? data.warnings.join('\n') : '生成被拒绝';
    show(answerText);
    hide(noEvidence);
  }

  // 引用列表
  if (data.citations && data.citations.length) {
    citationsList.innerHTML = '';
    data.citations.forEach((c) => {
      const li = document.createElement('li');
      const chunkSpan = document.createElement('span');
      chunkSpan.className = 'chunk-id';
      chunkSpan.textContent = `chunk #${c.chunkId}`;
      li.appendChild(chunkSpan);
      li.appendChild(document.createTextNode(c.excerpt || ''));
      citationsList.appendChild(li);
    });
    show(citationsPanel);
  } else {
    hide(citationsPanel);
  }

  // 警告
  if (data.warnings && data.warnings.length) {
    warningsList.innerHTML = '';
    data.warnings.forEach((w) => {
      const li = document.createElement('li');
      li.textContent = w;
      warningsList.appendChild(li);
    });
    show(warningsPanel);
  } else {
    hide(warningsPanel);
  }
}

// ═══════════════════════════════════════════════════════════════
// 审计日志
// ═══════════════════════════════════════════════════════════════
async function kcLoadAuditLogs() {
  try {
    const res = await fetch(`${KC_API_BASE}/audit-logs?page=0&size=10`);
    const payload = await res.json();
    if (payload.success && payload.data && payload.data.content) {
      const tbody = document.getElementById('kc-audit-tbody');
      tbody.innerHTML = '';
      payload.data.content.forEach((log) => {
        const tr = document.createElement('tr');
        tr.appendChild(td(formatTime(log.createdAt)));
        tr.appendChild(td(log.question || '—'));
        tr.appendChild(td(log.answerStatus || '—'));
        tr.appendChild(td(log.modelName || '—'));
        tbody.appendChild(tr);
      });
    }
  } catch (e) {
    // 静默处理
  }
}

// 初始加载
kcLoadDocuments();
kcLoadAuditLogs();
