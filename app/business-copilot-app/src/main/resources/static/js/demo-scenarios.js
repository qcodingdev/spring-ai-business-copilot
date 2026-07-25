/** 服务端场景目录：选择范例只填充，确认后才调用模型。 */
(function () {
  'use strict';

  const runtimeMode = document.querySelector('meta[name="runtime-mode"]')?.content || 'development';
  const moduleBindings = [
    { tab: 'data-copilot', module: 'DATA', title: '选择一个数据分析范例', max: 500 },
    { tab: 'knowledge-copilot', module: 'KNOWLEDGE', title: '选择一个企业知识范例', max: 500 },
    { tab: 'support-copilot', module: 'SUPPORT', title: '选择一个客服处理范例', max: 1000 },
    { tab: 'report-copilot', module: 'REPORT', title: '选择一个报告生成范例', max: 1000 },
    { tab: 'resume-copilot', module: 'HR', title: '选择 JD 生成或员工服务范例', max: 2000 }
  ];
  const selected = new Map();
  const adminUser = document.querySelector('meta[name="admin-user"]')?.content === 'true';

  document.addEventListener('DOMContentLoaded', () => {
    // 自部署和本地模式直接复用五个模块原有的快捷示例与完整流程，
    // 不再额外叠加一套场景工作台，避免出现两个入口。
    if (runtimeMode !== 'public-demo') return;
    moduleBindings.forEach(setupModule);
    loadUsage();
    loadOverview();
  });

  async function setupModule(binding) {
    const tab = document.getElementById(`tab-${binding.tab}`);
    if (!tab) return;
    const panel = buildPanel(binding);
    tab.prepend(panel);
    try {
      const response = await fetch(`/api/demo/scenarios?module=${binding.module}`);
      const payload = await response.json();
      if (!response.ok || !payload.success) throw new Error(payload.message || '范例加载失败');
      renderScenarioCards(binding, panel, payload.data || []);
    } catch (error) {
      renderUnavailable(panel, '范例暂时无法加载。请确认虚构数据已经完成初始化后重试。');
    }
  }

  function buildPanel(binding) {
    const panel = document.createElement('section');
    panel.className = 'panel scenario-workbench panel-emphasis';
    panel.dataset.module = binding.module;

    const heading = document.createElement('div');
    heading.className = 'panel-heading';
    const headingCopy = document.createElement('div');
    headingCopy.append(
      textElement('span', '可执行业务范例', 'section-kicker'),
      textElement('h2', binding.title)
    );
    const liveBadge = textElement('span', '选择不计额度', 'badge badge-pass');
    heading.append(headingCopy, liveBadge);

    const list = document.createElement('div');
    list.className = 'scenario-card-list';
    list.dataset.scenarioList = 'true';
    list.textContent = '正在加载业务范例…';

    const selectedScope = textElement('p', '请选择一个范例查看数据范围。', 'scenario-scope');
    selectedScope.dataset.scenarioScope = 'true';
    selectedScope.hidden = true;

    const label = textElement('label', '可修改的业务输入');
    const input = document.createElement('textarea');
    input.rows = 5;
    input.maxLength = binding.max;
    input.placeholder = '选择范例后自动填充，你可以在受控边界内修改。';
    input.dataset.scenarioInput = 'true';
    input.addEventListener('input', () => {
      counter.textContent = `${input.value.length} / ${binding.max}`;
    });

    const warning = textElement(
      'p',
      '请勿输入真实企业资料、客户信息、个人简历、账号凭据或其他敏感信息。',
      'privacy-warning'
    );
    const footer = document.createElement('div');
    footer.className = 'composer-footer';
    const counter = textElement('span', `0 / ${binding.max}`, 'input-hint');
    const execute = textElement('button', '确认并执行');
    execute.type = 'button';
    execute.disabled = true;
    execute.dataset.scenarioExecute = 'true';
    execute.addEventListener('click', () => executeScenario(binding, panel));
    footer.append(counter, execute);

    const result = document.createElement('section');
    result.className = 'scenario-result';
    result.dataset.scenarioResult = 'true';
    result.hidden = true;

    const fallback = textElement('button', '查看预生成示例结果', 'btn-secondary scenario-fallback');
    fallback.type = 'button';
    fallback.hidden = true;
    fallback.dataset.scenarioFallback = 'true';
    fallback.addEventListener('click', () => loadFallback(binding, panel));

    const composer = document.createElement('div');
    composer.dataset.scenarioComposer = 'true';
    composer.hidden = true;
    composer.append(label, input, warning, footer, fallback, result);

    panel.append(heading, list, selectedScope, composer);
    return panel;
  }

  function renderScenarioCards(binding, panel, scenarios) {
    const list = panel.querySelector('[data-scenario-list]');
    list.replaceChildren();
    if (!scenarios.length) {
      renderUnavailable(panel, '当前模块暂无可用范例，请先完成虚构数据初始化。');
      return;
    }
    panel.querySelector('[data-scenario-composer]').hidden = false;
    scenarios.forEach((scenario, index) => {
      const button = document.createElement('button');
      button.type = 'button';
      button.className = 'scenario-card';
      button.append(
        textElement('strong', scenario.title),
        textElement('span', scenario.description),
        textElement('small', scenario.dataScope)
      );
      button.addEventListener('click', () => selectScenario(binding, panel, scenario, button));
      list.appendChild(button);
      if (index === 0) selectScenario(binding, panel, scenario, button);
    });
  }

  function selectScenario(binding, panel, scenario, button) {
    selected.set(binding.module, scenario);
    panel.querySelectorAll('.scenario-card').forEach((item) => {
      const active = item === button;
      item.classList.toggle('active', active);
      item.setAttribute('aria-pressed', String(active));
    });
    const input = panel.querySelector('[data-scenario-input]');
    input.value = scenario.inputTemplate || '';
    input.dispatchEvent(new Event('input'));
    const scope = panel.querySelector('[data-scenario-scope]');
    scope.textContent = `本次可用资料：${scenario.dataScope}`;
    scope.hidden = false;
    panel.querySelector('[data-scenario-execute]').disabled = false;
    panel.querySelector('[data-scenario-fallback]').hidden = true;
    panel.querySelector('[data-scenario-result]').hidden = true;
  }

  function renderUnavailable(panel, message) {
    selected.delete(panel.dataset.module);
    panel.querySelector('[data-scenario-composer]').hidden = true;
    panel.querySelector('[data-scenario-scope]').hidden = true;
    const list = panel.querySelector('[data-scenario-list]');
    list.replaceChildren(textElement('p', message, 'empty-state'));
    if (adminUser) {
      const link = document.createElement('a');
      link.href = '/admin';
      link.className = 'scenario-empty-link';
      link.textContent = '前往管理与诊断初始化虚构数据';
      list.appendChild(link);
    }
  }

  async function executeScenario(binding, panel) {
    const scenario = selected.get(binding.module);
    const userInput = panel.querySelector('[data-scenario-input]').value.trim();
    if (!scenario || !userInput) {
      showBusinessError('请选择范例并填写业务输入。');
      return;
    }
    const executeButton = panel.querySelector('[data-scenario-execute]');
    executeButton.disabled = true;
    setBusinessLoading('正在处理业务资料并生成结果…');
    panel.querySelector('[data-scenario-fallback]').hidden = true;
    try {
      const response = await fetch('/api/demo/scenarios/execute', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ scenarioId: scenario.scenarioId, userInput })
      });
      const payload = await response.json().catch(() => ({}));
      if (!response.ok || !payload.success) {
        showBusinessError(payload.message || '业务处理失败，请稍后重试。');
        if (scenario.fallbackResultAvailable) {
          panel.querySelector('[data-scenario-fallback]').hidden = false;
        }
        return;
      }
      renderExecution(binding, panel, payload.data.execution);
      if (payload.data.usage) updateQuota(payload.data.usage);
    } catch (error) {
      showBusinessError('网络暂时不可用，请稍后重试。');
      if (scenario.fallbackResultAvailable) {
        panel.querySelector('[data-scenario-fallback]').hidden = false;
      }
    } finally {
      executeButton.disabled = false;
      setBusinessLoading(null);
    }
  }

  async function loadFallback(binding, panel) {
    const scenario = selected.get(binding.module);
    if (!scenario) return;
    setBusinessLoading('正在读取人工检查过的演示结果…');
    try {
      const response = await fetch(`/api/demo/scenarios/${encodeURIComponent(scenario.scenarioId)}/sample-result`);
      const payload = await response.json().catch(() => ({}));
      if (!response.ok || !payload.success) throw new Error(payload.message || '示例结果不可用');
      renderSample(panel, payload.data);
    } catch (error) {
      showBusinessError(error.message || '示例结果暂时不可用。');
    } finally {
      setBusinessLoading(null);
    }
  }

  function renderExecution(binding, panel, execution) {
    const container = panel.querySelector('[data-scenario-result]');
    container.replaceChildren();
    container.hidden = false;
    container.append(resultHeading('实时业务结果', execution.notice, '实时生成'));
    const result = execution.result || {};
    if (binding.module === 'DATA') renderDataResult(container, result);
    else if (binding.module === 'KNOWLEDGE') renderKnowledgeResult(container, result);
    else if (binding.module === 'SUPPORT') renderSupportResult(container, result);
    else if (binding.module === 'HR') renderHrResult(container, result);
    else if (binding.module === 'REPORT') renderReportResult(container, result);
    container.scrollIntoView({ behavior: reducedMotion() ? 'auto' : 'smooth', block: 'start' });
  }

  function renderSample(panel, sample) {
    const container = panel.querySelector('[data-scenario-result]');
    container.replaceChildren();
    container.hidden = false;
    container.append(resultHeading(
      '预生成演示结果',
      `${sample.notice} 生成时间：${formatDate(sample.generatedAt)}`,
      'PREGENERATED'
    ));
    renderSafeObject(container, sample.result);
    container.scrollIntoView({ behavior: reducedMotion() ? 'auto' : 'smooth', block: 'start' });
  }

  function renderDataResult(container, result) {
    appendBusinessSection(container, '业务结论', [result.summary || '查询候选已生成。']);
    const sqlSection = document.createElement('section');
    sqlSection.className = 'business-result-section sql-required-preview';
    sqlSection.append(textElement('h3', '执行前确认的只读查询'));
    const code = textElement('pre', result.sql || '未生成可执行查询', 'code-block');
    sqlSection.appendChild(code);
    container.appendChild(sqlSection);
    const check = result.executable
      ? ['表、字段、函数和结果范围检查已通过。']
      : ['安全检查未通过，当前查询不能执行。'];
    appendBusinessSection(container, '内容与合规检查', check);
    if (result.assumptions?.length) appendBusinessSection(container, '待核实事项', result.assumptions);
    if (result.warnings?.length) appendBusinessSection(container, '注意事项', result.warnings);
    if (result.executable && result.action) {
      appendActionButton(container, '确认执行只读查询', async (button) => {
        await performAction(
          `/api/data-copilot/sql-candidates/${encodeURIComponent(result.action.objectReference)}/execute`,
          { confirmationToken: result.action.confirmationToken },
          button,
          (data) => {
            if (typeof renderResult === 'function') renderResult(data);
            button.textContent = '查询已执行';
            button.disabled = true;
          });
      });
    }
  }

  function renderKnowledgeResult(container, result) {
    appendBusinessSection(container, '业务结论', [result.conclusion || '未找到有效依据。']);
    appendBusinessSection(container, '回答依据', result.evidence || []);
    appendBusinessSection(container, '待核实事项', result.warnings || []);
    appendBusinessSection(container, '下一步', result.nextActions || []);
  }

  function renderSupportResult(container, result) {
    appendBusinessSection(container, '处理结论', [result.conclusion]);
    appendBusinessSection(container, '建议回复', [result.suggestedReply || '当前风险较高，未生成可直接使用的回复。']);
    appendEvidence(container, result.evidence || []);
    appendBusinessSection(container, '待核实与风险', [
      ...(result.riskReasons || []),
      ...(result.nextActions || [])
    ]);
    if (result.action) {
      appendActionButton(container, '人工确认回复草稿', async (button) => {
        await performAction(
          `/api/support-copilot/reply-drafts/${result.action.objectReference}/confirm`,
          { confirmationToken: result.action.confirmationToken },
          button,
          () => {
            button.textContent = '已记录人工确认';
            button.disabled = true;
          });
      }, false);
    }
  }

  function renderHrResult(container, result) {
    if (result.jdDraft) {
      appendBusinessSection(container, '岗位画像', [result.jobProfile]);
      appendBusinessSection(container, '主要职责', result.responsibilities || []);
      appendBusinessSection(container, '必须条件', result.requiredQualifications || []);
      appendBusinessSection(container, '加分条件', result.preferredQualifications || []);
      appendBusinessSection(container, '待核实事项', result.verificationNotes || []);
      const details = document.createElement('details');
      details.append(textElement('summary', '查看并编辑 JD 草稿'), textElement('pre', result.jdDraft, 'business-draft'));
      container.appendChild(details);
      return;
    }
    if (result.questions) {
      appendBusinessSection(container, '证据缺口', result.evidenceGaps || []);
      appendBusinessSection(container, '推荐面试核实问题',
        result.questions.map((item) => item.question || String(item)));
      appendResumeEvidence(container, result.evidence || []);
      appendBusinessSection(container, '人工确认要求', result.limitations || []);
      appendResumeReviewAction(container, result);
      return;
    }
    const assessment = result.assessment || {};
    appendBusinessSection(container, '候选人材料摘要', [assessment.anonymousSummary]);
    const matches = assessment.criterionAssessments || [];
    appendBusinessSection(container, '有材料支持的岗位匹配点',
      matches.filter((item) => item.status === 'SUPPORTED').map(formatCriterion));
    appendBusinessSection(container, '部分支持或待核实',
      matches.filter((item) => item.status === 'PARTIAL' || item.status === 'NEEDS_VERIFICATION').map(formatCriterion));
    appendBusinessSection(container, '当前材料未体现的证据缺口', [
      ...(assessment.evidenceGaps || []),
      ...matches.filter((item) => item.status === 'NOT_FOUND').map(formatCriterion)
    ]);
    appendBusinessSection(container, '推荐面试题',
      (assessment.interviewQuestions || []).map((item) => item.question));
    appendResumeEvidence(container, result.evidence || []);
    appendBusinessSection(container, '限制与人工确认', [
      ...(assessment.limitations || []),
      ...(result.limitations || []),
      ...(result.reviewReasons || [])
    ]);
    appendResumeReviewAction(container, result);
  }

  function appendResumeReviewAction(container, result) {
    if (!result.action) return;
    const needsCorrection = result.status === 'NEEDS_REVIEW';
    appendActionButton(container,
      needsCorrection ? '需要人工修订后确认' : '记录人工复核结果',
      async (button) => {
        await performAction(
          `/api/resume-copilot/assessments/${result.action.objectReference}/review`,
          { token: result.action.confirmationToken, reviewerFeedback: '公网虚构场景人工确认' },
          button,
          () => {
            button.textContent = '已记录人工复核';
            button.disabled = true;
          });
      }, needsCorrection);
  }

  function renderReportResult(container, result) {
    const content = result.content || {};
    appendBusinessSection(container, '业务摘要', [content.executiveSummary]);
    appendBusinessSection(container, '关键指标',
      (content.metricHighlights || []).map((item) =>
        `${item.metricName || '指标'}：${item.metricValue || ''} ${item.unit || ''} ${item.summary || ''}`));
    appendBusinessSection(container, '风险与待核实事项',
      (content.risks || []).map((item) => item.text || String(item)));
    appendBusinessSection(container, '下一步行动',
      (content.actionItems || []).map((item) => item.text || String(item)));
    appendBusinessSection(container, '人工复核要求', [
      ...(result.reviewReasons || []),
      ...(result.nextActions || [])
    ]);
    if (result.action) {
      appendActionButton(container, '确认报告草稿', async (button) => {
        await performAction(
          `/api/report-copilot/reports/${result.action.objectReference}/confirm`,
          { confirmationToken: result.action.confirmationToken },
          button,
          () => {
            button.textContent = '报告草稿已确认（未发布）';
            button.disabled = true;
          });
      }, result.status !== 'DRAFTED');
    }
  }

  async function performAction(url, body, button, done) {
    button.disabled = true;
    setBusinessLoading('正在记录人工操作…');
    try {
      const response = await fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body)
      });
      const payload = await response.json().catch(() => ({}));
      if (!response.ok || !payload.success) throw new Error(payload.message || '操作失败');
      done(payload.data);
    } catch (error) {
      button.disabled = false;
      showBusinessError(error.message || '操作失败，请重试。');
    } finally {
      setBusinessLoading(null);
    }
  }

  function appendActionButton(container, label, handler, disabled) {
    const footer = document.createElement('div');
    footer.className = 'business-next-action';
    const copy = document.createElement('div');
    copy.append(textElement('strong', '人工复核与下一步'), textElement('span', '系统不会自动执行真实业务操作。'));
    const button = textElement('button', label);
    button.type = 'button';
    button.className = 'btn-primary';
    button.disabled = Boolean(disabled);
    if (disabled) button.title = '该结果需要先进行人工修订';
    button.addEventListener('click', () => handler(button));
    footer.append(copy, button);
    container.appendChild(footer);
  }

  function appendEvidence(container, evidence) {
    appendBusinessSection(container, '回答依据',
      evidence.map((item) => `${item.sourceTitle || '企业资料'}${item.sectionTitle ? ` · ${item.sectionTitle}` : ''}：${item.excerpt || ''}`));
  }

  function appendResumeEvidence(container, evidence) {
    appendBusinessSection(container, '简历原文依据',
      evidence.map((item) => `${item.evidenceId || '证据'} · ${item.section || '材料'}：${item.sanitizedText || ''}`));
  }

  function appendBusinessSection(container, title, values) {
    const filtered = (values || []).filter((value) => value !== null && value !== undefined && String(value).trim());
    if (!filtered.length) return;
    const section = document.createElement('section');
    section.className = 'business-result-section';
    section.appendChild(textElement('h3', title));
    const list = document.createElement('ul');
    filtered.forEach((value) => list.appendChild(textElement('li', String(value))));
    section.appendChild(list);
    container.appendChild(section);
  }

  function renderSafeObject(container, value) {
    if (!value || typeof value !== 'object') {
      appendBusinessSection(container, '演示内容', [String(value || '')]);
      return;
    }
    Object.entries(value).forEach(([key, item]) => {
      if (/token|model|prompt|id$/i.test(key)) return;
      const label = fieldLabel(key);
      if (Array.isArray(item)) appendBusinessSection(container, label, item.map(stringifySafe));
      else if (item && typeof item === 'object') appendBusinessSection(container, label,
        Object.entries(item).filter(([child]) => !/token|model|prompt|id$/i.test(child))
          .map(([child, childValue]) => `${fieldLabel(child)}：${stringifySafe(childValue)}`));
      else appendBusinessSection(container, label, [item]);
    });
  }

  function resultHeading(title, notice, badge) {
    const wrapper = document.createElement('div');
    wrapper.className = 'scenario-result-heading';
    const copy = document.createElement('div');
    copy.append(textElement('span', '业务结果', 'section-kicker'), textElement('h2', title), textElement('p', notice || ''));
    wrapper.append(copy, textElement('span', badge, badge === 'PREGENERATED' ? 'badge badge-warn' : 'badge badge-pass'));
    return wrapper;
  }

  async function loadUsage() {
    if (runtimeMode !== 'public-demo') {
      document.getElementById('overview-quota').textContent = '不限';
      return;
    }
    try {
      const response = await fetch('/api/demo/usage');
      const payload = await response.json();
      if (payload.success && payload.data) updateQuota(payload.data);
    } catch (_) {
      document.getElementById('overview-quota').textContent = '—';
    }
  }

  async function loadOverview() {
    try {
      const response = await fetch('/api/demo/overview');
      const payload = await response.json();
      if (!payload.success || !payload.data) return;
      const data = payload.data;
      document.getElementById('overview-scenario-count').textContent = data.availableScenarios;
      document.getElementById('overview-review-count').textContent = data.pendingReviews;
      document.getElementById('overview-knowledge-ready').textContent =
        `${data.readiness.knowledgeReady}/${data.readiness.knowledgeTotal} 可检索`;
      document.getElementById('overview-data-ready').textContent =
        `${data.readiness.fictionalCustomerRows} 条虚构客户`;
      const tasks = document.getElementById('overview-recent-tasks');
      if (tasks) {
        tasks.replaceChildren();
        (data.recentTasks || []).forEach((task) => {
          const row = document.createElement('div');
          row.append(
            textElement('strong', task.taskType),
            textElement('span', `${task.status} · ${formatDate(task.createdAt)}`)
          );
          tasks.appendChild(row);
        });
        if (!data.recentTasks?.length) {
          tasks.appendChild(textElement('p', '暂无最近任务，可以从下方范例开始。', 'empty-state'));
        }
      }
    } catch (_) {
      // 总览失败不影响五个业务流程。
    }
  }

  function updateQuota(usage) {
    const quota = document.getElementById('overview-quota');
    if (quota) quota.textContent = `${usage.remaining}/${usage.dailyLimit}`;
  }

  function fieldLabel(key) {
    const labels = {
      conclusion: '业务结论',
      evidence: '支撑依据',
      toVerify: '待核实事项',
      nextAction: '下一步操作',
      supported: '有材料支持',
      partial: '部分支持',
      gaps: '证据缺口',
      questions: '面试核实问题',
      risks: '风险',
      actions: '行动项',
      suggestedReply: '建议回复',
      executiveSummary: '业务摘要',
      responsibilities: '岗位职责',
      required: '必须条件',
      preferred: '加分条件',
      jobProfile: '岗位画像',
      notice: '说明',
      sqlPreview: '查询结构'
    };
    return labels[key] || key.replace(/([A-Z])/g, ' $1');
  }

  function formatCriterion(item) {
    return `${item.criterionId || '岗位标准'}：${item.explanation || '待核实'}`;
  }

  function stringifySafe(value) {
    if (value === null || value === undefined) return '';
    if (typeof value === 'object') return Object.entries(value)
      .filter(([key]) => !/token|model|prompt|id$/i.test(key))
      .map(([key, child]) => `${fieldLabel(key)}：${stringifySafe(child)}`).join('；');
    return String(value);
  }

  function textElement(tag, text, className) {
    const element = document.createElement(tag);
    element.textContent = text || '';
    if (className) element.className = className;
    return element;
  }

  function showBusinessError(message) {
    if (typeof showError === 'function') showError(message);
  }

  function setBusinessLoading(message) {
    if (typeof setLoading === 'function') setLoading(message);
  }

  function reducedMotion() {
    return window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;
  }

  function formatDate(value) {
    try {
      return new Date(value).toLocaleString('zh-CN', { hour12: false });
    } catch (_) {
      return value || '—';
    }
  }
}());
