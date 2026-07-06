# Prompt 07: Thymeleaf 前端工作台

```text
请实现 Data Copilot V1 前端工作台。

位置：
- app/business-copilot-app/src/main/resources/templates/index.html
- app/business-copilot-app/src/main/resources/static/css/app.css
- app/business-copilot-app/src/main/resources/static/js/app.js

技术：
- Thymeleaf 页面。
- 原生 JavaScript fetch。
- 不引入 React/Vue。

页面目标：
- 第一屏就是可用的 Data Copilot 查询工作台。
- 不做营销 landing page。

页面区域：
- 问题输入区。
- SQL candidate 区：SQL、summary、assumptions、warnings。
- Guardrails 区：通过/失败、违规原因。
- 确认执行按钮。
- 结果表格：columns、rows、rowCount、truncated。
- AI explanation 区。
- 最近审计记录预览。
- 错误提示和 loading 状态。

交互：
- 点击生成 SQL 调用 POST /api/data-copilot/sql-candidates。
- 校验失败时禁用确认执行按钮。
- 点击确认执行调用 POST /api/data-copilot/sql-candidates/{candidateId}/execute，只传 confirmationToken。
- 空结果显示友好空状态。
- 表格支持横向滚动。
- SQL 使用等宽字体展示，可复制。

视觉要求：
- 工具型后台风格，克制、清晰、可扫描。
- 卡片只用于具体工具面板，不要卡片套卡片。
- 手机端纵向布局，桌面端两列布局。
- 界面主文案中文，技术标签可用英文。

轻量测试：
- 首页返回 200。
- 前端手动验证生成 SQL、确认执行、错误展示三条主路径。

边界：
- 不做登录页。
- 不做多模块导航。
- 不做 BI 图表。
- 不做复杂前端测试框架。
```
