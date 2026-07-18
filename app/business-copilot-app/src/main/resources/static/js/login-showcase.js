// 公开产品预览：不调用业务接口，受保护操作仍必须先登录。
const modulePreviews = {
  data: {
    title: 'Data Copilot',
    kicker: '自然语言转 SQL',
    headline: '从业务问题到安全查询结果',
    description: '自然语言生成只读 SQL，先展示候选语句与校验结论，确认后再执行。',
    points: ['Schema、表与字段白名单', '写操作、未知函数和无界结果默认拒绝', 'SQL 候选、执行结果与操作者完整审计'],
    input: '“上个月销售额最高的前 5 个客户是谁？”',
    steps: ['理解业务意图', '生成查询候选', '安全校验'],
    resultTitle: '安全规则校验通过',
    resultCopy: '只读查询 · 5 行上限 · 敏感字段脱敏 · 等待人工确认'
  },
  knowledge: {
    title: 'Knowledge Copilot',
    kicker: '有依据的企业知识问答',
    headline: '从企业文档到可追溯答案',
    description: '检索企业知识证据，答案必须携带引用；证据不足时明确降级，不编造结论。',
    points: ['TXT、Markdown、PDF、DOCX 文档索引', '答案与引用片段一一对应', '低相关或证据不足时触发安全降级'],
    input: '“员工年假如何计算？请给出制度依据。”',
    steps: ['检索相关文档', '组合引用证据', '引用一致性校验'],
    resultTitle: '发现 3 条有效证据',
    resultCopy: '员工手册 v3 · 3 个引用片段 · 证据覆盖率通过'
  },
  support: {
    title: 'Support Copilot',
    kicker: '可审计的客服流程',
    headline: '从客服工单到待确认回复',
    description: '识别工单意图与风险，检索知识依据并生成回复草稿，由人工确认后完成处理。',
    points: ['工单分类、优先级和风险识别', '回复内容必须关联知识证据', '发送前人工复核，高风险工单强制升级'],
    input: '“客户反馈扣款成功，但订单仍显示未支付。”',
    steps: ['分析工单风险', '检索处置依据', '生成回复草稿'],
    resultTitle: '回复草稿等待审核',
    resultCopy: '支付异常 · 高优先级 · 2 条处置依据 · 未自动发送'
  },
  report: {
    title: 'Report Copilot',
    kicker: '基于证据的报告生成',
    headline: '从业务证据到可复核报告',
    description: '组合指标、任务和文本来源生成报告草稿，标注证据新鲜度并保留人工确认。',
    points: ['多种业务来源统一归一化', '事实、建议与不确定项清晰分离', '报告确认后再导出交付'],
    input: '“生成本周运营周报，突出退款风险和待办。”',
    steps: ['汇总业务来源', '生成报告结构', '事实一致性校验'],
    resultTitle: '报告草稿已生成',
    resultCopy: '8 项指标 · 4 个重点事件 · 2 个待复核结论'
  },
  resume: {
    title: 'Resume Copilot',
    kicker: '基于证据的简历评估',
    headline: '从岗位标准到证据化评估',
    description: '先确认岗位评估标准，再分析单份简历；保留证据缺口，不推断敏感属性。',
    points: ['岗位硬性与加分标准先确认', '个人隐私字段默认脱敏', '输出匹配证据、缺口和面试核验问题'],
    input: '“依据 Java 后端岗位标准评估这份候选人简历。”',
    steps: ['确认岗位标准', '提取简历证据', '招聘合规校验'],
    resultTitle: '评估草稿等待复核',
    resultCopy: '6 项标准匹配 · 3 个证据缺口 · 隐私字段已脱敏'
  }
};

const previewElements = {
  kicker: document.getElementById('preview-kicker'),
  title: document.getElementById('preview-title'),
  description: document.getElementById('preview-description'),
  points: document.getElementById('preview-points'),
  actionLabel: document.getElementById('preview-action-label'),
  consoleTitle: document.getElementById('preview-console-title'),
  input: document.getElementById('preview-input'),
  stepOne: document.getElementById('preview-step-one'),
  stepTwo: document.getElementById('preview-step-two'),
  stepThree: document.getElementById('preview-step-three'),
  resultTitle: document.getElementById('preview-result-title'),
  resultCopy: document.getElementById('preview-result-copy'),
  moduleHint: document.getElementById('login-module-hint')
};

const previewTabs = Array.from(document.querySelectorAll('[data-preview-module]'));
const previewStage = document.querySelector('.capability-stage');
const loginCard = document.getElementById('login-form');
const loginHero = document.querySelector('.public-hero');
const loginTriggers = Array.from(document.querySelectorAll('[data-login-trigger]'));
let activePreviewModule = 'data';

function rememberModule(moduleId, moduleTitle) {
  try {
    localStorage.setItem('business-copilot.active-module', `${moduleId}-copilot`);
  } catch {
    // 本地存储仅用于增强体验；不可用时不影响认证功能。
  }
  if (previewElements.moduleHint) {
    previewElements.moduleHint.textContent = `登录后将直接进入 ${moduleTitle}。`;
  }
}

function activatePreview(moduleId, focusTab = false) {
  const preview = modulePreviews[moduleId] || modulePreviews.data;
  activePreviewModule = modulePreviews[moduleId] ? moduleId : 'data';
  previewTabs.forEach((tab) => {
    const active = tab.dataset.previewModule === moduleId;
    tab.classList.toggle('active', active);
    tab.setAttribute('aria-selected', String(active));
    tab.tabIndex = active ? 0 : -1;
    if (active && focusTab) tab.focus();
  });

  previewStage?.classList.remove('preview-refresh');
  window.requestAnimationFrame(() => previewStage?.classList.add('preview-refresh'));

  previewElements.kicker.textContent = preview.kicker;
  previewElements.title.textContent = preview.headline;
  previewElements.description.textContent = preview.description;
  previewElements.points.innerHTML = '';
  preview.points.forEach((point) => {
    const item = document.createElement('li');
    item.textContent = point;
    previewElements.points.appendChild(item);
  });
  previewElements.actionLabel.textContent = preview.title;
  previewElements.consoleTitle.textContent = `${preview.title} / 安全执行`;
  previewElements.input.textContent = preview.input;
  previewElements.stepOne.textContent = preview.steps[0];
  previewElements.stepTwo.textContent = preview.steps[1];
  previewElements.stepThree.textContent = preview.steps[2];
  previewElements.resultTitle.textContent = preview.resultTitle;
  previewElements.resultCopy.textContent = preview.resultCopy;

  const action = document.querySelector('.capability-login-action');
  if (action) action.dataset.moduleTarget = moduleId;
}

previewTabs.forEach((tab, index) => {
  tab.addEventListener('click', () => activatePreview(tab.dataset.previewModule));
  tab.addEventListener('keydown', (event) => {
    if (!['ArrowRight', 'ArrowLeft', 'Home', 'End'].includes(event.key)) return;
    event.preventDefault();
    let nextIndex = index;
    if (event.key === 'ArrowRight') nextIndex = (index + 1) % previewTabs.length;
    if (event.key === 'ArrowLeft') nextIndex = (index - 1 + previewTabs.length) % previewTabs.length;
    if (event.key === 'Home') nextIndex = 0;
    if (event.key === 'End') nextIndex = previewTabs.length - 1;
    activatePreview(previewTabs[nextIndex].dataset.previewModule, true);
  });
});

document.querySelectorAll('[data-preview-jump]').forEach((button) => {
  button.addEventListener('click', () => {
    const moduleId = button.dataset.previewJump;
    activatePreview(moduleId);
    document.querySelector('.capability-explorer')?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  });
});

function setLoginExpanded(expanded) {
  if (!loginCard) return;
  loginCard.hidden = !expanded;
  loginHero?.classList.toggle('login-card-open', expanded);
  loginTriggers.forEach((trigger) => trigger.setAttribute('aria-expanded', String(expanded)));
}

loginTriggers.forEach((trigger) => {
  trigger.addEventListener('click', (event) => {
    event.preventDefault();
    const moduleId = trigger.dataset.moduleTarget || activePreviewModule;
    if (moduleId && modulePreviews[moduleId]) {
      rememberModule(moduleId, modulePreviews[moduleId].title);
    }
    setLoginExpanded(true);
    window.requestAnimationFrame(() => {
      loginCard?.scrollIntoView({ behavior: 'smooth', block: 'center' });
      window.setTimeout(() => document.getElementById('username')?.focus({ preventScroll: true }), 350);
    });
  });
});

if (loginCard && !loginCard.hidden) {
  setLoginExpanded(true);
  window.setTimeout(() => document.getElementById('username')?.focus({ preventScroll: true }), 0);
}
