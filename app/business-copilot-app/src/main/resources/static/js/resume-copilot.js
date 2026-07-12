/** Resume Copilot workbench: confirmed criteria, one sanitized resume, and human-reviewed evidence. */
(function () {
  'use strict';
  const API = '/api/resume-copilot';
  const $ = (id) => document.getElementById(id);
  let job = null;
  let assessment = null;

  $('rsc-extract-btn').addEventListener('click', extractCriteria);
  $('rsc-confirm-criteria-btn').addEventListener('click', confirmCriteria);
  $('rsc-assess-btn').addEventListener('click', assessResume);
  $('rsc-review-btn').addEventListener('click', () => updateAssessment('review'));
  $('rsc-cancel-btn').addEventListener('click', () => updateAssessment('cancel'));

  async function extractCriteria() {
    const title = $('rsc-job-title').value.trim();
    const jobDescription = $('rsc-job-description').value.trim();
    if (!title || !jobDescription) return showError('请填写职位名称和职位描述');
    await post('/jobs/criteria', { title, jobDescription }, '正在解析职位标准…', (data) => {
      job = data;
      renderCriteria(data);
    });
  }

  async function confirmCriteria() {
    if (!job || !job.confirmationToken) return showError('缺少职位标准确认凭证');
    await post(`/jobs/${job.jobId}/criteria/confirm`, { token: job.confirmationToken }, '正在确认职位标准…', (data) => {
      job.status = data.status;
      job.confirmationToken = null;
      $('rsc-criteria-status').textContent = data.status;
      $('rsc-criteria-status').className = 'badge badge-pass';
      $('rsc-confirm-criteria-btn').hidden = true;
      $('rsc-resume-panel').hidden = false;
    });
  }

  async function assessResume() {
    const resumeText = $('rsc-resume-text').value.trim();
    if (!job || job.status !== 'CRITERIA_CONFIRMED') return showError('请先确认职位标准');
    if (!resumeText) return showError('请输入简历文本');
    await post('/assessments', { jobId: job.jobId, resumeText }, '正在生成证据化评估…', (data) => {
      assessment = data;
      renderAssessment(data);
    });
  }

  async function updateAssessment(action) {
    if (!assessment || !assessment.reviewToken) return showError('缺少评估复核凭证');
    await post(`/assessments/${assessment.assessmentId}/${action}`, { token: assessment.reviewToken },
      action === 'review' ? '正在记录人工复核…' : '正在取消评估…', (data) => {
        assessment.status = data.status;
        assessment.reviewToken = null;
        renderAssessment(assessment);
      });
  }

  async function post(path, body, loadingText, done) {
    setLoading(loadingText);
    clearError();
    try {
      const response = await fetch(API + path, {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body)
      });
      const payload = await response.json().catch(() => ({}));
      if (!response.ok || !payload.success) throw new Error(payload.message || '请求失败');
      done(payload.data);
    } catch (error) {
      showError(error.message || '网络错误，请重试');
    } finally {
      setLoading(null);
    }
  }

  function renderCriteria(data) {
    const tbody = $('rsc-criteria-tbody');
    tbody.replaceChildren();
    (data.criteria || []).forEach((criterion) => {
      const row = document.createElement('tr');
      [criterion.category, criterion.requirementType, criterion.description, criterion.sourceText]
        .forEach((value) => row.appendChild(cell(value || '—')));
      tbody.appendChild(row);
    });
    $('rsc-criteria-status').textContent = data.status;
    $('rsc-criteria-status').className = 'badge badge-info';
    $('rsc-confirm-criteria-btn').hidden = !data.confirmationToken;
    $('rsc-criteria-panel').hidden = false;
    $('rsc-resume-panel').hidden = true;
    $('rsc-assessment-panel').hidden = true;
  }

  function renderAssessment(data) {
    const status = data.status || 'UNKNOWN';
    $('rsc-assessment-panel').hidden = false;
    $('rsc-assessment-status').textContent = status;
    $('rsc-assessment-status').className = 'badge ' + statusClass(status);
    renderList($('rsc-review-reasons-list'), data.reviewReasons || []);
    $('rsc-review-reasons').hidden = !(data.reviewReasons && data.reviewReasons.length);
    $('rsc-assessment-content').hidden = !data.content;
    if (data.content) renderContent(data.content);
    const actionable = (status === 'DRAFTED' || status === 'NEEDS_REVIEW') && data.reviewToken;
    $('rsc-assessment-actions').hidden = !actionable;
    $('rsc-review-btn').hidden = status !== 'DRAFTED';
    $('rsc-cancel-btn').hidden = !actionable;
  }

  function renderContent(content) {
    $('rsc-summary').textContent = content.anonymousSummary || '—';
    const tbody = $('rsc-matches-tbody');
    tbody.replaceChildren();
    (content.criterionAssessments || []).forEach((item) => {
      const row = document.createElement('tr');
      row.appendChild(cell(item.criterionId || '—'));
      const status = cell(item.status || '—');
      status.className = 'badge-cell';
      const badge = document.createElement('span');
      badge.className = 'badge ' + matchClass(item.status);
      badge.textContent = item.status || '—';
      status.replaceChildren(badge);
      row.appendChild(status);
      row.appendChild(cell(item.explanation || '—'));
      row.appendChild(cell((item.evidenceIds || []).join('、') || '—'));
      tbody.appendChild(row);
    });
    renderList($('rsc-gaps-list'), content.evidenceGaps || []);
    renderList($('rsc-questions-list'), (content.interviewQuestions || []).map(item => item.question));
    renderList($('rsc-limitations-list'), content.limitations || []);
  }

  function renderList(list, values) {
    list.replaceChildren();
    values.filter(Boolean).forEach((value) => {
      const item = document.createElement('li');
      item.textContent = value;
      list.appendChild(item);
    });
  }

  function cell(value) {
    const td = document.createElement('td');
    td.textContent = value;
    return td;
  }

  function statusClass(status) {
    if (status === 'DRAFTED' || status === 'REVIEWED') return 'badge-pass';
    if (status === 'NEEDS_REVIEW') return 'badge-warn';
    return 'badge-fail';
  }

  function matchClass(status) {
    if (status === 'SUPPORTED') return 'badge-pass';
    if (status === 'PARTIAL' || status === 'NEEDS_VERIFICATION') return 'badge-warn';
    return 'badge-info';
  }
}());
