/** 简历助手工作台：确认职位标准、脱敏单份简历并人工复核证据。 */
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
  $('rsc-delete-submission-btn').addEventListener('click', deleteSubmission);

  async function extractCriteria() {
    const title = $('rsc-job-title').value.trim();
    const jobDescription = $('rsc-job-description').value.trim();
    const file = $('rsc-job-file').files && $('rsc-job-file').files[0];
    if (!title || (!jobDescription && !file)) return showError('请填写职位名称，并提供职位描述或 JD 文件');
    if (file && file.size > 2 * 1024 * 1024) return showError('JD 文件不能超过 2 MB');
    const done = (data) => {
      job = data;
      renderCriteria(data);
    };
    if (file) {
      const form = new FormData();
      form.append('title', title);
      form.append('file', file);
      if (job && job.logicalJobId) form.append('logicalJobId', job.logicalJobId);
      await postForm('/jobs/criteria/file', form, '正在解析 JD 文件…', done);
      return;
    }
    await post('/jobs/criteria', {
      title,
      jobDescription,
      logicalJobId: job && job.logicalJobId ? job.logicalJobId : null
    }, '正在解析职位标准…', done);
  }

  async function confirmCriteria() {
    if (!job || !job.confirmationToken) return showError('缺少职位标准确认凭证');
    await post(`/jobs/${job.jobId}/criteria/confirm`, { token: job.confirmationToken }, '正在确认职位标准…', (data) => {
      job.status = data.status;
      job.confirmationToken = null;
      $('rsc-criteria-status').textContent = resumeStatusLabel(data.status);
      $('rsc-criteria-status').className = 'badge badge-pass';
      $('rsc-confirm-criteria-btn').hidden = true;
      $('rsc-resume-panel').hidden = false;
    });
  }

  async function assessResume() {
    const resumeText = $('rsc-resume-text').value.trim();
    const file = $('rsc-resume-file').files && $('rsc-resume-file').files[0];
    if (!job || job.status !== 'CRITERIA_CONFIRMED') return showError('请先确认职位标准');
    if (!resumeText && !file) return showError('请输入简历文本或选择简历文件');
    if (file && file.size > 2 * 1024 * 1024) return showError('简历文件不能超过 2 MB');
    const done = (data) => {
      assessment = data;
      renderAssessment(data);
    };
    if (file) {
      const form = new FormData();
      form.append('jobId', job.jobId);
      form.append('file', file);
      await postForm('/assessments/file', form, '正在解析并评估简历文件…', done);
      return;
    }
    await post('/assessments', { jobId: job.jobId, resumeText }, '正在生成证据化评估…', done);
  }

  async function updateAssessment(action) {
    if (!assessment || !assessment.reviewToken) return showError('缺少评估复核凭证');
    const body = { token: assessment.reviewToken };
    if (action === 'review') {
      body.reviewerFeedback = $('rsc-review-feedback').value.trim() || null;
      if ($('rsc-apply-correction').checked) {
        try {
          body.correctedContent = JSON.parse($('rsc-corrected-content').value);
        } catch (error) {
          return showError('人工修订内容必须是有效 JSON');
        }
      }
    }
    await post(`/assessments/${assessment.assessmentId}/${action}`, body,
      action === 'review' ? '正在记录人工复核…' : '正在取消评估…', (data) => {
        assessment.status = data.status;
        assessment.reviewToken = null;
        renderAssessment(assessment);
      });
  }

  async function deleteSubmission() {
    if (!assessment || !assessment.submissionId) return showError('缺少简历提交记录');
    setLoading('正在删除脱敏简历数据…');
    clearError();
    try {
      const response = await fetch(`${API}/submissions/${assessment.submissionId}`, { method: 'DELETE' });
      const payload = await response.json().catch(() => ({}));
      if (!response.ok || !payload.success) throw new Error(payload.message || '删除失败');
      assessment.submissionId = null;
      assessment.reviewToken = null;
      assessment.content = null;
      assessment.evidence = [];
      renderAssessment(assessment);
    } catch (error) {
      showError(error.message || '删除失败，请重试');
    } finally {
      setLoading(null);
    }
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

  async function postForm(path, form, loadingText, done) {
    setLoading(loadingText);
    clearError();
    try {
      const response = await fetch(API + path, { method: 'POST', body: form });
      const payload = await response.json().catch(() => ({}));
      if (!response.ok || !payload.success) throw new Error(payload.message || '请求失败');
      done(payload.data);
    } catch (error) {
      showError(error.message || '文件处理失败，请重试');
    } finally {
      setLoading(null);
    }
  }

  function renderCriteria(data) {
    const tbody = $('rsc-criteria-tbody');
    tbody.replaceChildren();
    (data.criteria || []).forEach((criterion) => {
      const row = document.createElement('tr');
      [criterionCategoryLabel(criterion.category), requirementTypeLabel(criterion.requirementType),
        criterion.description, criterion.sourceText]
        .forEach((value) => row.appendChild(cell(value || '—')));
      tbody.appendChild(row);
    });
    $('rsc-criteria-status').textContent = resumeStatusLabel(data.status);
    $('rsc-criteria-status').className = 'badge badge-info';
    $('rsc-confirm-criteria-btn').hidden = !data.confirmationToken;
    $('rsc-criteria-panel').hidden = false;
    $('rsc-resume-panel').hidden = true;
    $('rsc-assessment-panel').hidden = true;
  }

  function renderAssessment(data) {
    const status = data.status || 'UNKNOWN';
    $('rsc-assessment-panel').hidden = false;
    $('rsc-assessment-status').textContent = resumeStatusLabel(status);
    $('rsc-assessment-status').className = 'badge ' + statusClass(status);
    renderList($('rsc-review-reasons-list'), data.reviewReasons || []);
    $('rsc-review-reasons').hidden = !(data.reviewReasons && data.reviewReasons.length);
    $('rsc-assessment-content').hidden = !data.content;
    if (data.content) renderContent(data.content);
    renderEvidence(data.evidence || []);
    const actionable = (status === 'DRAFTED' || status === 'NEEDS_REVIEW') && data.reviewToken;
    $('rsc-assessment-actions').hidden = !actionable && !data.submissionId;
    $('rsc-review-btn').hidden = !actionable;
    $('rsc-cancel-btn').hidden = !actionable;
    $('rsc-delete-submission-btn').hidden = !data.submissionId;
    $('rsc-review-editor').hidden = !actionable;
    const requiresCorrection = status === 'NEEDS_REVIEW';
    $('rsc-apply-correction').checked = requiresCorrection;
    $('rsc-apply-correction').disabled = requiresCorrection;
    $('rsc-corrected-content').value = JSON.stringify(data.content || correctionTemplate(), null, 2);
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
      badge.textContent = matchStatusLabel(item.status);
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

  function renderEvidence(evidence) {
    const list = $('rsc-evidence-list');
    list.replaceChildren();
    evidence.forEach((item) => {
      const row = document.createElement('li');
      const id = document.createElement('strong');
      id.textContent = item.evidenceId || '—';
      const text = document.createElement('p');
      text.className = 'snippet';
      text.textContent = item.sanitizedText || '';
      row.append(id, document.createTextNode(` · ${resumeSectionLabel(item.section)}`), text);
      list.appendChild(row);
    });
    $('rsc-evidence-section').hidden = evidence.length === 0;
  }

  function correctionTemplate() {
    return {
      anonymousSummary: '',
      criterionAssessments: ((job && job.criteria) || []).map((criterion) => ({
        criterionId: criterion.criterionId,
        status: 'NOT_FOUND',
        explanation: '简历中未找到相关信息，需人工核验',
        evidenceIds: []
      })),
      evidenceGaps: [],
      interviewQuestions: [],
      limitations: ['仅基于当前脱敏简历证据，不能作为录用或淘汰决定。']
    };
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

  function resumeStatusLabel(status) {
    const labels = {
      CRITERIA_DRAFTED: '职位标准待确认',
      CRITERIA_CONFIRMED: '职位标准已确认',
      DRAFTED: '评估待复核',
      NEEDS_REVIEW: '需人工修订',
      REVIEWED: '已人工复核',
      CANCELED: '已取消',
      FAILED: '失败',
      UNKNOWN: '未知'
    };
    return labels[status] || status || '未知';
  }

  function criterionCategoryLabel(category) {
    const labels = {
      SKILL: '技能',
      EXPERIENCE: '经验',
      EDUCATION: '教育',
      CERTIFICATION: '认证',
      LANGUAGE: '语言',
      OTHER: '其他'
    };
    return labels[category] || category || '—';
  }

  function requirementTypeLabel(type) {
    return ({ REQUIRED: '必须', PREFERRED: '加分' })[type] || type || '—';
  }

  function matchStatusLabel(status) {
    const labels = {
      SUPPORTED: '有证据支持',
      PARTIAL: '部分支持',
      NOT_FOUND: '未找到',
      NEEDS_VERIFICATION: '需要核验'
    };
    return labels[status] || status || '—';
  }

  function resumeSectionLabel(section) {
    return section && section !== 'GENERAL' ? section : '通用信息';
  }
}());
