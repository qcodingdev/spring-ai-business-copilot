// Knowledge Copilot 前端逻辑：原生 fetch，无框架依赖。
// 功能：文档上传/列表/启用停用、知识问答、审计日志预览。

const KC_API_BASE = '/api/knowledge-copilot';
const KC_MAX_FILE_BYTES = 2 * 1024 * 1024;
let kcSelectedFile = null;
let kcCurrentAnswerId = null;
let kcSelectedFeedbackRating = null;

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

function kcShowSuccess(message) {
  if (typeof showSuccess === 'function') {
    showSuccess(message);
    return;
  }
  kcShowError(message);
}

// ═══════════════════════════════════════════════════════════════
// 文档上传
// ═══════════════════════════════════════════════════════════════
const kcDropzone = document.getElementById('kc-dropzone');
const kcFileInput = document.getElementById('kc-file-input');
const kcSelectedFileLabel = document.getElementById('kc-selected-file');

kcDropzone.addEventListener('click', () => kcFileInput.click());
kcDropzone.addEventListener('keydown', (event) => {
  if (event.key === 'Enter' || event.key === ' ') {
    event.preventDefault();
    kcFileInput.click();
  }
});
kcFileInput.addEventListener('change', () => {
  if (kcFileInput.files && kcFileInput.files[0]) {
    kcUseSelectedFile(kcFileInput.files[0]);
  }
});
['dragenter', 'dragover'].forEach((eventName) => {
  kcDropzone.addEventListener(eventName, (event) => {
    event.preventDefault();
    kcDropzone.classList.add('dragover');
  });
});
['dragleave', 'drop'].forEach((eventName) => {
  kcDropzone.addEventListener(eventName, (event) => {
    event.preventDefault();
    kcDropzone.classList.remove('dragover');
  });
});
kcDropzone.addEventListener('drop', (event) => {
  const file = event.dataTransfer?.files?.[0];
  if (file) kcUseSelectedFile(file);
});

function kcUseSelectedFile(file) {
  if (file.size > KC_MAX_FILE_BYTES) {
    kcShowError('文件不能超过 2 MB');
    kcClearSelectedFile();
    return;
  }
  kcSelectedFile = file;
  kcSelectedFileLabel.textContent = `${file.name} · ${kcFormatFileSize(file.size)}`;
  show(kcSelectedFileLabel);
  document.getElementById('kc-file-name').value = file.name;
}

function kcClearSelectedFile() {
  kcSelectedFile = null;
  kcFileInput.value = '';
  kcSelectedFileLabel.textContent = '';
  hide(kcSelectedFileLabel);
}

function kcFormatFileSize(bytes) {
  if (bytes < 1024) return `${bytes} B`;
  return `${(bytes / 1024).toFixed(bytes < 1024 * 100 ? 1 : 0)} KB`;
}

document.getElementById('kc-upload-btn').addEventListener('click', async () => {
  const fileName = document.getElementById('kc-file-name').value.trim();
  const content = document.getElementById('kc-file-content').value.trim();
  const category = document.getElementById('kc-file-category').value.trim();

  if (!kcSelectedFile && !fileName) { kcShowError('请选择文件或输入文件名'); return; }
  if (!kcSelectedFile && !content) { kcShowError('请选择文件或输入文档内容'); return; }

  const statusEl = document.getElementById('kc-upload-status');
  kcSetLoading(kcSelectedFile ? '上传文件并创建索引任务中…' : '上传文档中…');

  try {
    const uploadFile = kcSelectedFile;
    let res;
    if (uploadFile) {
      const formData = new FormData();
      formData.append('file', uploadFile);
      if (category) formData.append('category', category);
      res = await fetch(`${KC_API_BASE}/documents/file`, {
        method: 'POST',
        body: formData
      });
    } else {
      res = await fetch(`${KC_API_BASE}/documents`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ fileName, content, category })
      });
    }
    const payload = await res.json();

    if (!res.ok || !payload.success) {
      statusEl.textContent = payload.message || '上传失败';
      statusEl.className = 'inline-status error';
      show(statusEl);
      return;
    }

    if (payload.data.indexed || payload.data.indexStatus === 'COMPLETED') {
      statusEl.textContent = `上传成功！文档 ID=${payload.data.documentId}，${payload.data.chunkCount} 个分片，已建立向量索引`;
      statusEl.className = 'inline-status';
    } else if (payload.data.indexJobId) {
      statusEl.textContent = `文档版本 v${payload.data.version || 1} 已接收，正在后台建立索引`;
      statusEl.className = 'inline-status';
      kcPollIndexJob(payload.data.indexJobId, statusEl);
    } else {
      statusEl.textContent = `文档已保存（ID=${payload.data.documentId}），但尚未建立向量索引；配置 Embedding 模型后请点击“重建索引”`;
      statusEl.className = 'inline-status error';
    }
    show(statusEl);

    // 清空表单
    document.getElementById('kc-file-name').value = '';
    document.getElementById('kc-file-category').value = '';
    document.getElementById('kc-file-content').value = '';
    kcClearSelectedFile();

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
      const titleTd = document.createElement('td');
      const title = document.createElement('span');
      title.className = 'table-title';
      const titleStrong = document.createElement('strong');
      titleStrong.textContent = doc.title || '未命名文档';
      const titleMeta = document.createElement('small');
      titleMeta.textContent = doc.sourceName || `文档 ID ${doc.id ?? '—'}`;
      title.append(titleStrong, titleMeta);
      titleTd.appendChild(title);
      tr.appendChild(titleTd);

      tr.appendChild(td(doc.category || '—'));
      const versionTd = document.createElement('td');
      const versionBadge = document.createElement('span');
      versionBadge.className = `badge ${doc.currentVersion ? 'badge-info' : ''}`;
      versionBadge.textContent = `v${doc.versionNo || 1}${doc.currentVersion ? ' 当前' : ''}`;
      versionTd.appendChild(versionBadge);
      tr.appendChild(versionTd);

      const indexTd = document.createElement('td');
      const indexStatus = String(doc.indexStatus || (doc.enabled ? 'INDEXED' : 'PENDING'));
      const indexLabel = document.createElement('span');
      indexLabel.className = `document-index-status ${indexStatus.toLowerCase()}`;
      const textSearchOnly = indexStatus === 'INDEXED'
        && doc.indexErrorCategory === 'TEXT_SEARCH_ONLY';
      indexLabel.textContent = textSearchOnly ? '文本检索可用' : kcIndexStatusText(indexStatus);
      if (textSearchOnly) {
        indexLabel.title = '未配置 Embedding 模型，当前使用全文与中文关键词检索';
      }
      indexTd.appendChild(indexLabel);
      tr.appendChild(indexTd);

      // 启用/停用 toggle
      const toggleTd = document.createElement('td');
      const wrapper = document.createElement('label');
      wrapper.className = 'toggle-switch';
      const checkbox = document.createElement('input');
      checkbox.type = 'checkbox';
      checkbox.checked = doc.enabled;
      checkbox.disabled = !doc.currentVersion
        || !['COMPLETED', 'INDEXED', 'DISABLED'].includes(indexStatus);
      if (checkbox.disabled) {
        wrapper.title = doc.currentVersion ? '索引完成后才能启用' : '历史版本不能启用';
      }
      checkbox.addEventListener('change', () => kcToggleEnabled(doc.id, checkbox.checked, checkbox));
      const slider = document.createElement('span');
      slider.className = 'toggle-slider';
      wrapper.appendChild(checkbox);
      wrapper.appendChild(slider);
      toggleTd.appendChild(wrapper);
      tr.appendChild(toggleTd);

      tr.appendChild(td(formatTime(doc.updatedAt || doc.createdAt)));
      const actionTd = document.createElement('td');
      const actionWrapper = document.createElement('div');
      actionWrapper.className = 'table-actions';
      const reindexButton = document.createElement('button');
      reindexButton.type = 'button';
      reindexButton.className = 'btn-secondary table-action-button';
      reindexButton.textContent = '重建索引';
      reindexButton.addEventListener('click', () => kcReindexDocument(doc.id, reindexButton));
      actionWrapper.appendChild(reindexButton);
      const deleteButton = document.createElement('button');
      deleteButton.type = 'button';
      deleteButton.className = 'btn-secondary table-action-button';
      deleteButton.textContent = '删除';
      deleteButton.addEventListener('click', () => kcDeleteDocument(doc, deleteButton));
      actionWrapper.appendChild(deleteButton);
      actionTd.appendChild(actionWrapper);
      tr.appendChild(actionTd);
      tbody.appendChild(tr);
    });
  } catch (e) {
    kcShowError('知识文档列表加载失败，请刷新页面重试');
  }
}

function kcIndexStatusText(status) {
  const labels = {
    PENDING: '待索引',
    PROCESSING: '索引中',
    RETRYABLE: '等待重试',
    COMPLETED: '已完成',
    INDEXED: '已完成',
    FAILED: '失败',
    CANCELED: '已取消'
  };
  return labels[status] || status;
}

async function kcReindexDocument(documentId, button) {
  button.disabled = true;
  kcSetLoading('正在创建索引任务…');
  try {
    const res = await fetch(`${KC_API_BASE}/documents/${documentId}/reindex`, { method: 'POST' });
    const payload = await res.json();
    if (!res.ok || !payload.success) {
      kcShowError(payload.message || '重建索引失败');
      return;
    }
    const statusEl = document.getElementById('kc-upload-status');
    statusEl.textContent = '索引任务已创建，正在后台处理';
    statusEl.className = 'inline-status';
    show(statusEl);
    if (payload.data?.id) kcPollIndexJob(payload.data.id, statusEl);
    kcLoadDocuments();
  } catch (e) {
    kcShowError('网络错误');
  } finally {
    button.disabled = false;
    kcSetLoading(null);
  }
}

async function kcDeleteDocument(document, button) {
  const title = document.title || `文档 ${document.id}`;
  if (!window.confirm(`确定删除“${title}”的当前文档版本吗？相关分片和向量索引也会删除，此操作不可撤销。`)) {
    return;
  }
  button.disabled = true;
  kcSetLoading('正在删除文档版本…');
  try {
    const res = await fetch(`${KC_API_BASE}/documents/${document.id}`, { method: 'DELETE' });
    const payload = await res.json().catch(() => ({}));
    if (!res.ok || !payload.success) {
      kcShowError(payload.message || '文档删除失败');
      return;
    }
    kcShowSuccess('文档版本已删除；如存在旧版本，最近版本已恢复为当前版本并保持停用');
    kcLoadDocuments();
  } catch (e) {
    kcShowError('网络错误，文档未删除');
  } finally {
    button.disabled = false;
    kcSetLoading(null);
  }
}

async function kcPollIndexJob(jobId, statusEl, attempts = 0) {
  if (!jobId) return;
  if (attempts >= 200) {
    statusEl.textContent = '索引处理时间较长，请稍后在文档列表查看状态；无需重复上传';
    statusEl.className = 'inline-status error';
    return;
  }

  try {
    const res = await fetch(`${KC_API_BASE}/index-jobs/${jobId}`);
    const payload = await res.json().catch(() => ({}));
    if (!res.ok || !payload.success || !payload.data) {
      throw new Error(payload.message || '索引状态获取失败');
    }
    const status = String(payload.data.status || 'PENDING');

    if (status === 'COMPLETED') {
      statusEl.textContent = payload.data.embeddingModel === 'text-search-only'
        ? `文本索引完成，共生成 ${payload.data.chunkCount || 0} 个分片；未配置 Embedding，当前使用全文与中文关键词检索`
        : `向量索引完成，共生成 ${payload.data.chunkCount || 0} 个分片`;
      statusEl.className = 'inline-status';
      kcLoadDocuments();
      return;
    }
    if (status === 'FAILED' || status === 'CANCELED') {
      statusEl.textContent = status === 'FAILED'
        ? kcIndexFailureText(payload.data.errorCategory)
        : '索引任务已取消';
      statusEl.className = 'inline-status error';
      kcLoadDocuments();
      return;
    }

    if (status === 'PROCESSING') {
      statusEl.textContent = '文档解析已完成，正在生成并保存向量索引…';
    } else if (status === 'RETRYABLE') {
      statusEl.textContent = `模型调用暂时失败，后台将自动重试（第 ${payload.data.attempts || 1} 次）…`;
    } else {
      statusEl.textContent = '索引任务排队中…';
    }
    setTimeout(() => kcPollIndexJob(jobId, statusEl, attempts + 1), 1500);
  } catch (e) {
    statusEl.textContent = '索引状态暂时无法获取，正在继续重试…';
    setTimeout(() => kcPollIndexJob(jobId, statusEl, attempts + 1), 2500);
  }
}

function kcIndexFailureText(errorCategory) {
  const messages = {
    MODEL_DISABLED: '索引失败：Embedding 模型未启用，请检查 SPRING_AI_MODEL_EMBEDDING 和模型密钥',
    EMBEDDING_DIMENSION_MISMATCH: '索引失败：模型实际维度与配置不一致，请修正 SPRING_AI_OPENAI_EMBEDDING_DIMENSION 后重启',
    BIZ_0100: '索引失败：Embedding 模型调用失败，请检查服务地址、密钥和模型名',
    INDEXING_FAILED: '索引失败：模型或数据库暂时不可用，请查看服务端中文日志后重试'
  };
  return messages[errorCategory] || `索引失败（${errorCategory || '未知原因'}），请检查模型配置后重试`;
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
  kcResetFeedback();

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
    kcLoadQualityQueue();
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
  const feedbackPanel = document.getElementById('kc-feedback-panel');

  // 状态徽章
  statusBadge.textContent = kcAnswerStatusText(data.status);
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
      chunkSpan.textContent = `分片 #${c.chunkId}`;
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

  kcCurrentAnswerId = data.answerId || null;
  if (kcCurrentAnswerId) {
    show(feedbackPanel);
  } else {
    hide(feedbackPanel);
  }

  scrollResultIntoView(statusPanel);
}

// ═══════════════════════════════════════════════════════════════
// 用户反馈与质量闭环
// ═══════════════════════════════════════════════════════════════
const kcFeedbackHelpful = document.getElementById('kc-feedback-helpful');
const kcFeedbackNotHelpful = document.getElementById('kc-feedback-not-helpful');
const kcFeedbackSubmit = document.getElementById('kc-feedback-submit');

kcFeedbackHelpful.addEventListener('click', () => kcSelectFeedback('HELPFUL'));
kcFeedbackNotHelpful.addEventListener('click', () => kcSelectFeedback('NOT_HELPFUL'));
kcFeedbackSubmit.addEventListener('click', kcSubmitFeedback);

function kcSelectFeedback(rating) {
  kcSelectedFeedbackRating = rating;
  kcFeedbackHelpful.setAttribute('aria-pressed', String(rating === 'HELPFUL'));
  kcFeedbackNotHelpful.setAttribute('aria-pressed', String(rating === 'NOT_HELPFUL'));
  kcFeedbackHelpful.classList.toggle('selected', rating === 'HELPFUL');
  kcFeedbackNotHelpful.classList.toggle('selected', rating === 'NOT_HELPFUL');
  document.getElementById('kc-feedback-details').hidden = rating !== 'NOT_HELPFUL';
  kcFeedbackSubmit.disabled = false;
  hide(document.getElementById('kc-feedback-status'));
}

function kcResetFeedback() {
  kcCurrentAnswerId = null;
  kcSelectedFeedbackRating = null;
  if (!kcFeedbackHelpful || !kcFeedbackNotHelpful || !kcFeedbackSubmit) return;
  [kcFeedbackHelpful, kcFeedbackNotHelpful].forEach((button) => {
    button.setAttribute('aria-pressed', 'false');
    button.classList.remove('selected');
  });
  document.getElementById('kc-feedback-reason').value = '';
  document.getElementById('kc-feedback-comment').value = '';
  hide(document.getElementById('kc-feedback-details'));
  hide(document.getElementById('kc-feedback-status'));
  hide(document.getElementById('kc-feedback-panel'));
  kcFeedbackSubmit.disabled = true;
}

async function kcSubmitFeedback() {
  if (!kcCurrentAnswerId || !kcSelectedFeedbackRating) return;
  const reason = document.getElementById('kc-feedback-reason').value || null;
  const comment = document.getElementById('kc-feedback-comment').value.trim() || null;
  if (kcSelectedFeedbackRating === 'NOT_HELPFUL' && !reason) {
    kcShowError('请选择未解决的主要原因');
    return;
  }

  kcFeedbackSubmit.disabled = true;
  const status = document.getElementById('kc-feedback-status');
  try {
    const res = await fetch(`${KC_API_BASE}/answers/${kcCurrentAnswerId}/feedback`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        rating: kcSelectedFeedbackRating,
        reason: kcSelectedFeedbackRating === 'NOT_HELPFUL' ? reason : null,
        comment
      })
    });
    const payload = await res.json().catch(() => ({}));
    if (!res.ok || !payload.success) {
      kcShowError(payload.message || '反馈提交失败');
      return;
    }
    status.textContent = '反馈已记录，可重新选择并更新。下一步：管理员在待复核问题中处理。';
    status.className = 'inline-status';
    show(status);
    kcLoadQualityQueue();
  } catch (e) {
    kcShowError('网络错误，反馈尚未提交');
  } finally {
    kcFeedbackSubmit.disabled = false;
  }
}

// ═══════════════════════════════════════════════════════════════
// 审计日志
// ═══════════════════════════════════════════════════════════════
async function kcLoadAuditLogs() {
  try {
    const res = await fetch(`${KC_API_BASE}/audit-logs?page=0&size=10`);
    const payload = await res.json();
    if (res.status === 403) {
      document.getElementById('kc-audit-tbody').innerHTML =
        '<tr><td colspan="4">审计记录仅管理员和审计员可查看</td></tr>';
      return;
    }
    if (!res.ok) {
      document.getElementById('kc-audit-tbody').innerHTML =
        '<tr><td colspan="4">审计记录暂时无法加载</td></tr>';
      return;
    }
    if (payload.success && payload.data && payload.data.content) {
      const tbody = document.getElementById('kc-audit-tbody');
      tbody.innerHTML = '';
      payload.data.content.forEach((log) => {
        const tr = document.createElement('tr');
        tr.appendChild(td(formatTime(log.createdAt)));
        tr.appendChild(td(log.question || '—'));
        tr.appendChild(td(kcAnswerStatusText(log.answerStatus)));
        tr.appendChild(td(log.modelName || '—'));
        tbody.appendChild(tr);
      });
    }
  } catch (e) {
    document.getElementById('kc-audit-tbody').innerHTML =
      '<tr><td colspan="4">审计记录暂时无法加载</td></tr>';
  }
}

async function kcLoadQualityQueue() {
  const tbody = document.getElementById('kc-quality-tbody');
  try {
    const res = await fetch(`${KC_API_BASE}/quality-queue?page=0&size=10`);
    const payload = await res.json().catch(() => ({}));
    if (res.status === 403) {
      tbody.innerHTML = '<tr><td colspan="5">质量复核队列仅管理员和审计员可查看</td></tr>';
      return;
    }
    if (!res.ok || !payload.success) {
      tbody.innerHTML = '<tr><td colspan="5">质量复核队列暂时无法加载</td></tr>';
      return;
    }
    const items = payload.data?.content || [];
    tbody.innerHTML = '';
    if (!items.length) {
      tbody.innerHTML = '<tr><td colspan="5">当前没有待复核问题</td></tr>';
      return;
    }
    items.forEach((item) => {
      const tr = document.createElement('tr');
      tr.appendChild(td(formatTime(item.feedbackUpdatedAt || item.answerCreatedAt)));
      tr.appendChild(td(item.question || '—'));
      tr.appendChild(td(kcQualityIssueText(item)));
      tr.appendChild(td(item.comment || kcFeedbackReasonText(item.feedbackReason) || '—'));
      tr.appendChild(kcQualityReviewActions(item));
      tbody.appendChild(tr);
    });
  } catch (e) {
    tbody.innerHTML = '<tr><td colspan="5">质量复核队列暂时无法加载</td></tr>';
  }
}

function kcQualityReviewActions(item) {
  const cell = document.createElement('td');
  cell.className = 'knowledge-quality-actions';
  const note = document.createElement('textarea');
  note.rows = 2;
  note.maxLength = 1000;
  note.setAttribute('aria-label', `问题 ${item.answerId} 的复核说明`);
  note.placeholder = '填写处置依据或需要补充的知识内容';
  const buttons = document.createElement('div');
  buttons.className = 'knowledge-quality-action-buttons';
  [
    ['RESOLVED', '已处理'],
    ['DISMISSED', '忽略'],
    ['KNOWLEDGE_UPDATE_REQUIRED', '转知识维护']
  ].forEach(([decision, label]) => {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = decision === 'DISMISSED'
      ? 'btn-secondary table-action-button'
      : 'table-action-button';
    button.textContent = label;
    button.addEventListener('click', () =>
      kcReviewQualityIssue(item, decision, note, buttons));
    buttons.appendChild(button);
  });
  cell.append(note, buttons);
  return cell;
}

async function kcReviewQualityIssue(item, decision, note, buttons) {
  const reviewNote = note.value.trim();
  if (!reviewNote) {
    kcShowError('请先填写人工处置说明');
    note.focus();
    return;
  }
  const actionButtons = Array.from(buttons.querySelectorAll('button'));
  actionButtons.forEach((button) => { button.disabled = true; });
  try {
    const res = await fetch(`${KC_API_BASE}/quality-queue/${item.answerId}/review`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        decision,
        reviewNote,
        expectedIssueVersion: item.issueVersion,
        expectedIssueUpdatedAt: item.issueUpdatedAt
      })
    });
    const payload = await res.json().catch(() => ({}));
    if (!res.ok || !payload.success) {
      kcShowError(payload.message || '人工处置记录失败，请刷新后重试');
      return;
    }
    kcShowSuccess('人工处置已记录；如问题内容再次更新，将自动重新进入队列。');
    await Promise.all([kcLoadQualityQueue(), kcLoadQualityMetrics()]);
  } catch (e) {
    kcShowError('网络错误，人工处置尚未记录');
  } finally {
    actionButtons.forEach((button) => { button.disabled = false; });
  }
}

async function kcLoadQualityMetrics() {
  const summary = document.getElementById('kc-quality-review-summary');
  try {
    const res = await fetch(`${KC_API_BASE}/quality-metrics`);
    const payload = await res.json().catch(() => ({}));
    if (res.status === 403) {
      summary.textContent = '质量统计仅管理员和审计员可查看';
      return;
    }
    if (!res.ok || !payload.success || !payload.data) {
      summary.textContent = '质量统计暂时无法加载';
      return;
    }
    const metrics = payload.data;
    document.getElementById('kc-quality-pending-count').textContent =
      String(metrics.pendingReviewCount ?? 0);
    document.getElementById('kc-quality-feedback-count').textContent =
      String(metrics.feedbackCount ?? 0);
    document.getElementById('kc-quality-helpful-count').textContent =
      String(metrics.helpfulCount ?? 0);
    document.getElementById('kc-quality-not-helpful-count').textContent =
      String(metrics.notHelpfulCount ?? 0);
    summary.textContent =
      `累计人工处置：已处理 ${metrics.resolvedCount ?? 0}，` +
      `忽略 ${metrics.dismissedCount ?? 0}，` +
      `转知识维护 ${metrics.knowledgeUpdateRequiredCount ?? 0}`;
  } catch (e) {
    summary.textContent = '质量统计暂时无法加载';
  }
}

function kcQualityIssueText(item) {
  if (item.rating === 'NOT_HELPFUL') return '用户反馈未解决';
  if (item.answerStatus === 'NO_EVIDENCE') return '证据不足';
  if (item.answerStatus === 'REJECTED') return '生成被拒绝';
  return kcAnswerStatusText(item.answerStatus);
}

function kcFeedbackReasonText(reason) {
  const labels = {
    MISSING_EVIDENCE: '缺少关键依据',
    INCORRECT: '结论不正确',
    OUTDATED: '资料已经过期',
    UNCLEAR: '表达不清楚',
    OTHER: '其他问题'
  };
  return labels[reason] || '';
}

function kcAnswerStatusText(status) {
  const labels = {
    ANSWERED: '已回答',
    NO_EVIDENCE: '证据不足',
    REJECTED: '已拒绝'
  };
  return labels[status] || status || '—';
}

// 初始加载
kcLoadDocuments();
kcLoadAuditLogs();
kcLoadQualityQueue();
kcLoadQualityMetrics();
