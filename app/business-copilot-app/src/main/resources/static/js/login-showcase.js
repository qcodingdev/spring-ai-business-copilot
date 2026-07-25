// 公开产品预览：不调用业务接口，受保护操作仍必须先登录。
const modulePreviews = {
  data: {
    title: '数据分析助手',
    kicker: '安全查询业务数据',
    headline: '从业务问题到安全查询结果',
    description: '直接提出销售、客户或商品问题，系统先展示将要执行的只读查询，确认后再返回业务结果。',
    points: ['只访问获准的虚构业务数据', '写操作和无边界结果默认拒绝', '查询内容、处理结果与操作者均可追溯'],
    input: '“上个月销售额最高的前 5 个客户是谁？”',
    steps: ['理解业务意图', '生成查询候选', '安全校验'],
    resultTitle: '内容与安全检查通过',
    resultCopy: '只读查询 · 5 行上限 · 敏感字段脱敏 · 等待人工确认'
  },
  knowledge: {
    title: '企业知识助手',
    kicker: '有依据的企业知识问答',
    headline: '从企业文档到可追溯答案',
    description: '检索企业知识证据，答案必须携带引用；证据不足时明确降级，不编造结论。',
    points: ['企业资料按角色和业务范围开放', '答案与原文依据一一对应', '未找到有效依据时拒答或转人工'],
    input: '“员工年假如何计算？请给出制度依据。”',
    steps: ['检索相关文档', '组合引用证据', '引用一致性校验'],
    resultTitle: '发现 3 条有效证据',
    resultCopy: '员工手册 v3 · 3 个引用片段 · 证据覆盖率通过'
  },
  support: {
    title: '客服工作台',
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
    title: '报告生成助手',
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
    title: 'HR Copilot',
    kicker: '招聘与员工服务',
    headline: '从岗位画像到面试核实问题',
    description: '先生成并确认岗位标准，再整理虚构候选人材料中的匹配证据、缺口和待核实事项。',
    points: ['岗位画像和 JD 先由人工编辑确认', '只使用预置虚构简历', '输出证据位置、缺口和面试核实问题'],
    input: '“分析这份虚构简历与 Java AI 岗位要求的证据对应关系。”',
    steps: ['确认岗位标准', '整理材料证据', '检查招聘合规'],
    resultTitle: '招聘辅助结果等待复核',
    resultCopy: '6 项材料证据 · 3 个待核实问题 · 不输出录用或淘汰建议'
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
  previewElements.consoleTitle.textContent = `${preview.title} / 受控处理`;
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
