/** 简历助手工作台：确认职位标准、脱敏单份简历并人工复核证据。 */
(function () {
  'use strict';
  const API = '/api/resume-copilot';
  const $ = (id) => document.getElementById(id);
  let job = null;
  let assessment = null;
  let jobDraft = null;
  let confirmedJobs = [];

  document.querySelectorAll('.hr-tabs [data-hr-view]').forEach((button) => {
    button.addEventListener('click', () => activateHrView(button.dataset.hrView));
  });
  document.querySelectorAll('.recruitment-tabs [data-recruitment-view]').forEach((button) => {
    button.addEventListener('click', () => activateRecruitmentView(button.dataset.recruitmentView));
  });
  $('rsc-draft-job-btn').addEventListener('click', draftJob);
  $('rsc-use-draft-btn').addEventListener('click', useDraftForCriteria);
  $('rsc-hr-ask-btn').addEventListener('click', askHrPolicy);
  $('rsc-confirm-criteria-btn').addEventListener('click', confirmCriteria);
  $('rsc-refresh-jobs-btn').addEventListener('click', () => loadConfirmedJobs(job?.jobId));
  $('rsc-confirmed-job-select').addEventListener('change', selectConfirmedJob);
  $('rsc-assess-btn').addEventListener('click', assessResume);
  $('rsc-review-btn').addEventListener('click', () => updateAssessment('review'));
  $('rsc-cancel-btn').addEventListener('click', () => updateAssessment('cancel'));
  $('rsc-delete-submission-btn').addEventListener('click', deleteSubmission);
  document.querySelectorAll('#rsc-job-samples [data-job-requirements]').forEach((button) => {
    button.addEventListener('click', () => {
      $('rsc-job-title').value = button.dataset.jobTitle || '';
      $('rsc-job-description').value = button.dataset.jobRequirements || '';
      $('rsc-job-description').focus();
    });
  });
  document.querySelectorAll('#rsc-hr-question-samples [data-hr-question]').forEach((button) => {
    button.addEventListener('click', () => {
      $('rsc-hr-question').value = button.dataset.hrQuestion || '';
      $('rsc-hr-question').focus();
    });
  });

  function activateHrView(view) {
    const employeeService = view === 'employee-service';
    const recruitmentPanel = $('rsc-recruitment-view');
    const employeePanel = $('rsc-employee-service-panel');
    if (recruitmentPanel) recruitmentPanel.hidden = employeeService;
    if (employeePanel) employeePanel.hidden = !employeeService;
    document.querySelectorAll('.hr-tabs [data-hr-view]').forEach((button) => {
      const active = button.dataset.hrView === view;
      button.classList.toggle('active', active);
      button.setAttribute('aria-selected', String(active));
      button.tabIndex = active ? 0 : -1;
    });
  }

  function activateRecruitmentView(view) {
    const candidateAssessment = view === 'candidate-assessment';
    $('rsc-job-standard-view').hidden = candidateAssessment;
    $('rsc-candidate-assessment-view').hidden = !candidateAssessment;
    if (candidateAssessment) loadConfirmedJobs(job?.jobId);
    document.querySelectorAll('.recruitment-tabs [data-recruitment-view]').forEach((button) => {
      const active = button.dataset.recruitmentView === view;
      button.classList.toggle('active', active);
      button.setAttribute('aria-selected', String(active));
      button.tabIndex = active ? 0 : -1;
    });
  }

  async function askHrPolicy() {
    const question = $('rsc-hr-question').value.trim();
    if (!question) return showError('请输入员工制度或流程问题');
    setLoading('正在检索 HR 制度并生成答复…');
    clearError();
    try {
      const response = await fetch('/api/knowledge-copilot/questions', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ question, category: 'HR_POLICY' })
      });
      const payload = await response.json().catch(() => ({}));
      if (!response.ok || !payload.success) throw new Error(payload.message || '制度问答请求失败');
      renderHrPolicyAnswer(payload.data);
    } catch (error) {
      showError(error.message || '制度问答请求失败，请稍后重试');
    } finally {
      setLoading(null);
    }
  }

  function renderHrPolicyAnswer(data) {
    $('rsc-hr-answer-panel').hidden = false;
    $('rsc-hr-answer').textContent = data.answer || '未找到足够制度依据，请转人工确认。';
    const citations = $('rsc-hr-citations');
    citations.replaceChildren();
    (data.citations || []).forEach((citation) => {
      const item = document.createElement('li');
      item.textContent = citation.excerpt || '制度依据片段';
      citations.appendChild(item);
    });
    $('rsc-hr-warnings').textContent = (data.warnings || []).join('；');
    scrollResultIntoView($('rsc-hr-answer-panel'));
  }

  async function draftJob() {
    const title = $('rsc-job-title').value.trim();
    const requirements = $('rsc-job-description').value.trim();
    if (!title) return showError('请先填写职位名称');
    if (!requirements) return showError('请先填写岗位需求');
    await post('/jobs/draft', { title, requirements }, '正在生成岗位画像与完整 JD 草稿…', (data) => {
      jobDraft = data;
      renderJobDraft(data);
    });
  }

  function renderJobDraft(data) {
    $('rsc-job-draft-panel').hidden = false;
    if (data.title) $('rsc-job-title').value = data.title;
    $('rsc-job-profile').textContent = data.jobProfile || '—';
    renderList($('rsc-job-responsibilities'), data.responsibilities || []);
    renderList($('rsc-job-required'), data.requiredQualifications || []);
    renderList($('rsc-job-preferred'), data.preferredQualifications || []);
    $('rsc-job-draft').value = data.jdDraft || '';
    $('rsc-job-verification').textContent = (data.verificationNotes || []).join('；')
      || '请编辑这份完整虚构 JD；确认后即可继续提取评估标准并进入简历分析。';
    setStandardStep(2);
    scrollResultIntoView($('rsc-job-draft-panel'));
  }

  function useDraftForCriteria() {
    const editableDraft = $('rsc-job-draft').value.trim();
    if (!editableDraft) return showError('请先生成并保留可编辑 JD 草稿');
    // 保留招聘负责人最终填写的职位名称，不能被模型返回值覆盖。
    $('rsc-job-title').value = $('rsc-job-title').value.trim();
    $('rsc-job-description').value = editableDraft;
    extractCriteria();
  }

  async function extractCriteria() {
    const title = $('rsc-job-title').value.trim();
    const jobDescription = $('rsc-job-description').value.trim();
    if (!title || !jobDescription) return showError('请填写职位名称和 JD 内容');
    const done = (data) => {
      job = data;
      renderCriteria(data, true);
    };
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
      setStandardStep(3);
      showSuccess('JD 已确认为评估标准。如需评估候选人，请点击”候选人评估”标签页。');
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
      renderAssessment(data, true);
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

  async function loadConfirmedJobs(preferredJobId) {
    const select = $('rsc-confirmed-job-select');
    if (!select) return;
    select.disabled = true;
    try {
      const response = await fetch(`${API}/jobs/confirmed`);
      const payload = await response.json().catch(() => ({}));
      if (!response.ok || !payload.success) throw new Error(payload.message || '岗位标准加载失败');
      confirmedJobs = payload.data || [];
      select.replaceChildren(option('', confirmedJobs.length ? '请选择岗位标准' : '暂无已确认的岗位标准'));
      confirmedJobs.forEach((item) => {
        const label = `${item.title} · 标准 v${item.criteriaVersion} · ${item.criteria?.length || 0} 项要求`;
        select.appendChild(option(String(item.jobId), label));
      });
      const selectedId = preferredJobId || job?.jobId;
      if (selectedId && confirmedJobs.some((item) => item.jobId === Number(selectedId))) {
        select.value = String(selectedId);
        selectConfirmedJob();
      } else {
        $('rsc-resume-panel').hidden = true;
        $('rsc-selected-job-summary').textContent = confirmedJobs.length
          ? '请选择一个岗位标准，再提交候选人简历。'
          : '请先在“岗位标准”中确认一份 JD 标准。';
      }
    } catch (error) {
      select.replaceChildren(option('', '岗位标准加载失败，请刷新重试'));
      $('rsc-selected-job-summary').textContent = error.message || '岗位标准加载失败。';
    } finally {
      select.disabled = false;
    }
  }

  function selectConfirmedJob() {
    const selectedId = Number($('rsc-confirmed-job-select').value);
    const selected = confirmedJobs.find((item) => item.jobId === selectedId);
    if (!selected) {
      job = null;
      $('rsc-resume-panel').hidden = true;
      $('rsc-selected-job-summary').textContent = '请选择一个已确认的岗位标准。';
      return;
    }
    job = { ...selected, status: 'CRITERIA_CONFIRMED' };
    $('rsc-resume-panel').hidden = false;
    $('rsc-selected-job-summary').textContent =
      `已选择“${selected.title}”标准（v${selected.criteriaVersion}，${selected.criteria?.length || 0} 项要求）。`;
  }

  function option(value, label) {
    const item = document.createElement('option');
    item.value = value;
    item.textContent = label;
    return item;
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
        renderAssessment(assessment, false);
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
      renderAssessment(assessment, false);
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

  function renderCriteria(data, scrollToResult) {
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
    setStandardStep(3);
    if (scrollToResult) scrollResultIntoView($('rsc-criteria-panel'));
  }

  function setStandardStep(step) {
    [1, 2, 3].forEach((number) => {
      const item = $(`rsc-standard-step-${number}`);
      if (item) item.classList.toggle('active', number === step);
    });
  }

  function renderAssessment(data, scrollToResult) {
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
    if (scrollToResult) scrollResultIntoView($('rsc-assessment-panel'));
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

  activateHrView('employee-service');
}());
