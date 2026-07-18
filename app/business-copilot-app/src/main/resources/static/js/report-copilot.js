/** 报表助手工作台：来源预览、报告复核、确认和文件导出。 */
(function () {
  'use strict';

  const API_BASE = '/api/report-copilot';
  let draft = null;

  const $ = (id) => document.getElementById(id);
  const els = {
    reportType: $('rc-report-type'),
    periodStart: $('rc-period-start'),
    periodEnd: $('rc-period-end'),
    title: $('rc-title'),
    metricName: $('rc-metric-name'),
    metricValue: $('rc-metric-value'),
    metricUnit: $('rc-metric-unit'),
    taskTitle: $('rc-task-title'),
    taskStatus: $('rc-task-status'),
    taskAssignee: $('rc-task-assignee'),
    taskSource: $('rc-task-source'),
    noteTitle: $('rc-note-title'),
    noteContent: $('rc-note-content'),
    importFile: $('rc-import-file'),
    sampleSourcesBtn: $('rc-sample-sources-btn'),
    previewBtn: $('rc-preview-btn'),
    generateBtn: $('rc-generate-btn'),
    importPreviewBtn: $('rc-import-preview-btn'),
    generateFileBtn: $('rc-generate-file-btn'),
    sourcesPanel: $('rc-sources-panel'),
    sourcesCount: $('rc-sources-count'),
    sourcesList: $('rc-sources-list'),
    draftPanel: $('rc-draft-panel'),
    statusBadge: $('rc-status-badge'),
    draftMeta: $('rc-draft-meta'),
    reviewReasons: $('rc-review-reasons'),
    reviewReasonsList: $('rc-review-reasons-list'),
    reportContent: $('rc-report-content'),
    executiveSummary: $('rc-executive-summary'),
    metricsSection: $('rc-metrics-section'),
    metricsList: $('rc-metrics-list'),
    completedSection: $('rc-completed-section'),
    completedList: $('rc-completed-list'),
    risksSection: $('rc-risks-section'),
    risksList: $('rc-risks-list'),
    actionsSection: $('rc-actions-section'),
    actionsList: $('rc-actions-list'),
    suggestionsSection: $('rc-suggestions-section'),
    suggestionsList: $('rc-suggestions-list'),
    draftActions: $('rc-draft-actions'),
    exportActions: $('rc-export-actions'),
    confirmBtn: $('rc-confirm-btn'),
    cancelBtn: $('rc-cancel-btn'),
    exportBtn: $('rc-export-btn'),
    exportHtmlBtn: $('rc-export-html-btn')
  };

  initializePeriod();
  els.sampleSourcesBtn.addEventListener('click', loadSampleSources);
  els.previewBtn.addEventListener('click', previewSources);
  els.generateBtn.addEventListener('click', generateReport);
  els.importPreviewBtn.addEventListener('click', previewImportedSources);
  els.generateFileBtn.addEventListener('click', generateReportFromFile);
  els.confirmBtn.addEventListener('click', () => updateDraft('confirm'));
  els.cancelBtn.addEventListener('click', () => updateDraft('cancel'));
  els.exportBtn.addEventListener('click', () => exportReport('markdown', 'md'));
  els.exportHtmlBtn.addEventListener('click', () => exportReport('html', 'html'));

  function initializePeriod() {
    const today = new Date();
    const day = today.getDay() || 7;
    const end = new Date(today);
    end.setDate(today.getDate() - day + 5);
    const start = new Date(end);
    start.setDate(end.getDate() - 4);
    els.periodStart.value = formatDate(start);
    els.periodEnd.value = formatDate(end);
  }

  async function previewSources() {
    await submitRequest('/source-previews', '正在整理来源…', (data) => {
      renderSources(data.sources || []);
    });
  }

  async function loadSampleSources() {
    setLoading('正在加载示例来源…');
    clearError();
    try {
      const response = await fetch(API_BASE + '/sample-sources');
      const body = await response.json().catch(() => ({}));
      if (!response.ok || !body.success) throw new Error(formatError(body));
      renderSources(body.data.sources || []);
    } catch (error) {
      showError(error.message || '示例来源加载失败');
    } finally {
      setLoading(null);
    }
  }

  async function generateReport() {
    await submitRequest('/reports/generate', '正在生成报告草稿…', (data) => {
      draft = data;
      renderDraft(data);
      scrollResultIntoView(els.draftPanel);
    });
  }

  async function previewImportedSources() {
    const file = selectedImportFile();
    if (!file) return;
    const form = new FormData();
    form.append('file', file);
    await submitForm('/source-imports/preview', form, '正在校验导入来源…', (data) => {
      renderSources(data.sources || []);
    });
  }

  async function generateReportFromFile() {
    const file = selectedImportFile();
    if (!file || !reportScope()) return;
    const form = new FormData();
    form.append('file', file);
    const query = new URLSearchParams({
      reportType: els.reportType.value,
      periodStart: els.periodStart.value,
      periodEnd: els.periodEnd.value,
      title: els.title.value.trim()
    });
    await submitForm(`/reports/generate-from-file?${query}`, form, '正在从文件生成报告草稿…', (data) => {
      draft = data;
      renderDraft(data);
      scrollResultIntoView(els.draftPanel);
    });
  }

  async function submitRequest(path, loadingText, onSuccess) {
    const request = buildRequest();
    if (!request) return;

    setLoading(loadingText);
    clearError();
    try {
      const response = await fetch(API_BASE + path, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(request)
      });
      const body = await response.json().catch(() => ({}));
      if (!response.ok || !body.success) {
        throw new Error(formatError(body));
      }
      onSuccess(body.data);
    } catch (error) {
      showError(error.message || '请求失败，请稍后重试');
    } finally {
      setLoading(null);
    }
  }

  async function submitForm(path, form, loadingText, onSuccess) {
    setLoading(loadingText);
    clearError();
    try {
      const response = await fetch(API_BASE + path, { method: 'POST', body: form });
      const body = await response.json().catch(() => ({}));
      if (!response.ok || !body.success) throw new Error(formatError(body));
      onSuccess(body.data);
    } catch (error) {
      showError(error.message || '文件处理失败，请稍后重试');
    } finally {
      setLoading(null);
    }
  }

  function buildRequest() {
    if (!reportScope()) return null;

    const metrics = collectMetric();
    const tasks = collectTask();
    const meetingNotes = collectMeetingNote();
    if (metrics === null || tasks === null || meetingNotes === null) return null;

    return {
      reportType: els.reportType.value,
      period: { periodStart: els.periodStart.value, periodEnd: els.periodEnd.value },
      title: els.title.value.trim(),
      metrics,
      tasks,
      meetingNotes
    };
  }

  function reportScope() {
    const required = [els.periodStart.value, els.periodEnd.value, els.title.value.trim()];
    if (required.some((value) => !value)) {
      showError('请填写报告范围和标题');
      return false;
    }
    if (els.periodStart.value > els.periodEnd.value) {
      showError('报告开始日期不能晚于结束日期');
      return false;
    }
    return true;
  }

  function selectedImportFile() {
    const file = els.importFile.files && els.importFile.files[0];
    if (!file) {
      showError('请选择 CSV 或 JSON 来源文件');
      return null;
    }
    if (file.size > 1024 * 1024) {
      showError('报告来源文件不能超过 1 MB');
      return null;
    }
    return file;
  }

  function collectMetric() {
    const fields = [els.metricName.value.trim(), els.metricValue.value.trim(), els.metricUnit.value.trim()];
    if (fields.every((value) => !value)) return [];
    if (fields.some((value) => !value)) {
      showError('指标来源需要名称、数值和单位');
      return null;
    }
    return [{
      name: fields[0], value: fields[1], unit: fields[2],
      periodStart: els.periodStart.value, periodEnd: els.periodEnd.value,
      collectedAt: new Date().toISOString()
    }];
  }

  function collectTask() {
    const required = [els.taskTitle.value.trim(), els.taskSource.value.trim()];
    if (required.every((value) => !value) && !els.taskAssignee.value.trim()) return [];
    if (required.some((value) => !value)) {
      showError('任务来源需要标题和来源说明');
      return null;
    }
    return [{
      title: required[0], status: els.taskStatus.value,
      assigneeAlias: els.taskAssignee.value.trim(), sourceDescription: required[1]
    }];
  }

  function collectMeetingNote() {
    const fields = [els.noteTitle.value.trim(), els.noteContent.value.trim()];
    if (fields.every((value) => !value)) return [];
    if (fields.some((value) => !value)) {
      showError('会议纪要需要标题和内容');
      return null;
    }
    return [{ title: fields[0], content: fields[1], recordedAt: new Date().toISOString() }];
  }

  function renderSources(sources) {
    els.sourcesList.replaceChildren();
    sources.forEach((source) => {
      const item = document.createElement('li');
      const title = document.createElement('strong');
      title.textContent = source.title || '未命名来源';
      const type = document.createElement('span');
      type.className = 'badge badge-info';
      type.textContent = sourceTypeLabel(source.sourceType);
      const content = document.createElement('p');
      content.className = 'snippet';
      content.textContent = source.sanitizedContent || '';
      const id = document.createElement('span');
      id.className = 'report-source-id';
      id.textContent = '证据 ID: ' + (source.sourceId || '—');
      item.append(title, document.createTextNode(' '), type, content, id);
      els.sourcesList.appendChild(item);
    });
    els.sourcesCount.textContent = sources.length + ' 项';
    els.sourcesPanel.hidden = false;
  }

  function renderDraft(data) {
    els.draftPanel.hidden = false;
    const status = data.status || 'UNKNOWN';
    els.statusBadge.textContent = reportStatusLabel(status);
    els.statusBadge.className = 'badge ' + statusBadgeClass(status);
    els.draftMeta.textContent = [data.title, formatPeriod(data.period), data.modelName].filter(Boolean).join(' · ');

    renderTextList(els.reviewReasonsList, data.reviewReasons || []);
    els.reviewReasons.hidden = !(data.reviewReasons && data.reviewReasons.length);
    renderContent(data.content);

    const canCancel = (status === 'DRAFTED' || status === 'NEEDS_REVIEW') && data.draftId && data.confirmationToken;
    els.draftActions.hidden = !canCancel;
    els.confirmBtn.hidden = status !== 'DRAFTED';
    els.cancelBtn.hidden = !canCancel;
    els.exportActions.hidden = status !== 'CONFIRMED' || !data.draftId;
  }

  function renderContent(content) {
    if (!content) {
      els.reportContent.hidden = true;
      return;
    }
    els.reportContent.hidden = false;
    els.executiveSummary.textContent = content.executiveSummary || '—';
    renderTextList(els.metricsList, (content.metricHighlights || []).map((metric) => {
      const sources = formatSourceIds(metric.sourceIds);
      return `${metric.metricName || '指标'}：${metric.metricValue || ''} ${metric.unit || ''}。${metric.summary || ''}${sources}`;
    }));
    toggleSection(els.metricsSection, content.metricHighlights);
    renderTextList(els.completedList, (content.completedItems || []).map(formatEvidenceItem));
    toggleSection(els.completedSection, content.completedItems);
    renderTextList(els.risksList, (content.risks || []).map(formatEvidenceItem));
    toggleSection(els.risksSection, content.risks);
    renderTextList(els.actionsList, (content.actionItems || []).map(formatEvidenceItem));
    toggleSection(els.actionsSection, content.actionItems);
    renderTextList(els.suggestionsList, (content.suggestions || []).map((item) => item.text || ''));
    toggleSection(els.suggestionsSection, content.suggestions);
  }

  async function updateDraft(action) {
    if (!draft || !draft.draftId || !draft.confirmationToken) {
      showError('当前草稿缺少确认凭证');
      return;
    }
    setLoading(action === 'confirm' ? '正在确认草稿…' : '正在取消草稿…');
    try {
      const response = await fetch(`${API_BASE}/reports/${draft.draftId}/${action}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ confirmationToken: draft.confirmationToken })
      });
      const body = await response.json().catch(() => ({}));
      if (!response.ok || !body.success) throw new Error(formatError(body));
      draft.status = body.data.status;
      draft.confirmationToken = null;
      renderDraft(draft);
    } catch (error) {
      showError(error.message || '草稿状态更新失败');
    } finally {
      setLoading(null);
    }
  }

  async function exportReport(format, extension) {
    if (!draft || !draft.draftId || draft.status !== 'CONFIRMED') return;
    setLoading(`正在准备 ${format.toUpperCase()}…`);
    try {
      const response = await fetch(`${API_BASE}/reports/${draft.draftId}/${format}`);
      if (!response.ok) {
        const body = await response.json().catch(() => ({}));
        throw new Error(formatError(body));
      }
      const blob = await response.blob();
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = `report-${draft.draftId}.${extension}`;
      link.click();
      URL.revokeObjectURL(url);
    } catch (error) {
      showError(error.message || `${format.toUpperCase()} 导出失败`);
    } finally {
      setLoading(null);
    }
  }

  function renderTextList(list, items) {
    list.replaceChildren();
    items.filter(Boolean).forEach((text) => {
      const item = document.createElement('li');
      item.textContent = text;
      list.appendChild(item);
    });
  }

  function toggleSection(section, items) {
    section.hidden = !(items && items.length);
  }

  function formatEvidenceItem(item) {
    return (item.text || '') + formatSourceIds(item.sourceIds);
  }

  function formatSourceIds(sourceIds) {
    return sourceIds && sourceIds.length ? `（来源：${sourceIds.join('、')}）` : '';
  }

  function formatPeriod(period) {
    return period && period.periodStart && period.periodEnd ? `${period.periodStart} 至 ${period.periodEnd}` : '';
  }

  function statusBadgeClass(status) {
    if (status === 'CONFIRMED') return 'badge-pass';
    if (status === 'DRAFTED') return 'badge-info';
    if (status === 'REJECTED' || status === 'FAILED' || status === 'CANCELED') return 'badge-fail';
    return 'badge-warn';
  }

  function formatDate(date) {
    const year = date.getFullYear();
    const month = String(date.getMonth() + 1).padStart(2, '0');
    const day = String(date.getDate()).padStart(2, '0');
    return `${year}-${month}-${day}`;
  }

  function reportStatusLabel(status) {
    const labels = {
      DRAFTED: '待确认',
      NEEDS_REVIEW: '需人工复核',
      CONFIRMED: '已确认',
      REJECTED: '已拒绝',
      FAILED: '失败',
      CANCELED: '已取消',
      UNKNOWN: '未知'
    };
    return labels[status] || status || '未知';
  }

  function sourceTypeLabel(sourceType) {
    const labels = {
      METRIC: '指标',
      TASK: '任务',
      MEETING_NOTE: '会议纪要',
      TEXT: '文本'
    };
    return labels[sourceType] || sourceType || '来源';
  }

  function formatError(payload) {
    return payload && payload.message ? payload.message : '请求失败';
  }
}());
