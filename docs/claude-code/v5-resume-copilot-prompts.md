# Claude Code Prompt：V5 Resume Copilot

本文档用于在前四个模块完成后，实现第五个业务模块：Resume Copilot 简历评估助手。

执行建议：

1. 每次只执行一个编号 prompt。
2. 先阅读 `AGENTS.md`、总规划和 `docs/resume-copilot.md`。
3. 严格遵守招聘合规、隐私、证据引用和人工复核边界。
4. 不以“作品演示”为理由加入候选人总分、排名、自动淘汰或自动录用。
5. 每个阶段完成后运行测试并 review，再继续下一 prompt。
6. 执行前确认框架加固和 V4 Report Copilot 已完成。

## 0. V5 总约束 Prompt

```text
请在当前 Spring AI Business Copilot 仓库中实现第五个业务模块：Resume Copilot 简历评估助手。

实现前必须完整阅读：
- AGENTS.md
- docs/project-plan.md
- docs/module-plan.md
- docs/knowledge-copilot.md
- docs/report-copilot.md
- docs/resume-copilot.md
- docs/architecture-review-and-framework-plan.md

产品定位：
- Resume Copilot 对单个已确认 JD 和单份脱敏简历做证据化匹配分析。
- 输出逐条标准匹配、简历证据、信息缺口和面试核验问题。
- 它是招聘人员的阅读辅助工具，不是自动招聘决策系统。

技术栈：
- Java 21
- Maven 多模块
- Spring Boot 4.1.x
- Spring AI 2.0.x
- Spring Web MVC
- MyBatis-Plus 3.5.16（稳定 CRUD）
- Spring JDBC（仅在有明确特殊查询时使用）
- Thymeleaf + 原生 JavaScript

新增模块：
- modules/resume-copilot

包名前缀：
- dev.qcoding.businesscopilot.resumecopilot

依赖方向：
- app -> resume-copilot
- resume-copilot -> ai-core
- resume-copilot -> ai-guardrails
- resume-copilot -> ai-tool-audit
- resume-copilot -> common-web
- 可通过窄接口复用稳定的文本解析或脱敏能力，不允许其他业务模块反向依赖 resume-copilot。
- app 已装配 mybatis-plus-spring-boot4-starter，业务模块不重复引入其他 MyBatis starter。

绝对禁止：
- 自动录用、淘汰、拒绝或推进招聘流程。
- 候选人总分、星级、排名、百分位或通过概率。
- 批量候选人比较、人才池排序和黑名单。
- 根据姓名、照片、年龄、性别、婚育、民族、宗教、健康、残障、政治面貌、籍贯做判断。
- 人脸、声音、情绪、人格、动机和离职风险推断。
- 基于学校、公司品牌、职业空窗做无依据负面判断。
- 接入真实 ATS、招聘网站、邮箱、日历或员工系统。
- 自动发送邀约、拒信和面试安排。
- 保存原始未脱敏简历或把它写进日志、审计。
- 实现 PDF/DOCX OCR、批量上传和复杂权限。
- 把大段 prompt 写进 service。
- 使用 ActiveRecord、ServiceImpl 继承体系、通用 BaseEntity 或未使用的 MyBatis-Plus 插件。
- 提交真实简历、招聘记录或个人信息。

关键规则：
- JD 评估标准必须先由招聘人员确认。
- 禁止属性必须在模型调用前移除，无法可靠移除时拒绝分析。
- 每个匹配结论必须引用当前简历的 evidenceId。
- NOT_FOUND 只表示简历未体现，不代表候选人不具备。
- 所有结果必须人工复核，REVIEWED 不代表任何招聘决定。

完成后至少运行：
- ./mvnw -q -DskipTests compile
- ./mvnw -q -pl modules/resume-copilot -am test
```

## 1. 模块骨架、数据库和虚构样例 Prompt

```text
请实现 Resume Copilot 的 Maven 模块骨架、数据库和完全虚构的示例 JD/简历。

请实现：
- modules/resume-copilot/pom.xml
- ResumeCopilotAutoConfiguration
- ResumeCopilotProperties
- META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
- 根 pom 和 app 模块依赖
- app application.yml 的 resume-copilot 配置
- resume-copilot 使用 MyBatis-Plus core；不要重复引入 MyBatis starter
- Flyway 迁移：resume_jobs、resume_submissions、resume_evidence、resume_assessments、resume_audit_logs

配置至少包含：
- enabled
- max-job-description-length
- max-resume-length
- max-criteria-count
- max-evidence-count
- review-token-ttl
- protected-attribute-guard-enabled

数据规则：
- resume_jobs 保存脱敏 JD 和结构化标准。
- resume_submissions 只保存匿名候选编号、脱敏简历和内容 hash。
- resume_evidence 只保存脱敏片段。
- resume_assessments 保存结构化结果、evidenceId、状态和复核 token。
- resume_audit_logs 不保存原始 JD、原始简历、联系方式、受保护属性和完整模型响应。
- 任何表都不得保存原始未脱敏简历。

状态至少包含：
- CRITERIA_DRAFTED
- CRITERIA_CONFIRMED
- DRAFTED
- NEEDS_REVIEW
- REVIEWED
- CANCELED
- FAILED

增加完全虚构样例：
- sample-resume/java-backend/
- sample-resume/technical-support/
- sample-resume/data-analyst/

每组包含 jd.md 和 resume.md，覆盖 SUPPORTED、PARTIAL、NOT_FOUND、NEEDS_VERIFICATION。
样例中的联系方式和个人属性必须明显虚构，并用于验证脱敏，不得影射真实个人。

测试：
- 模块编译和自动配置加载。
- Flyway 表和 repository 字段一致。
- 样例可解析且不包含真实信息。
- 原始未脱敏文本不入库、不进审计。

本阶段不调用模型，不实现评估和前端。
```

## 2. 隐私处理和简历证据解析 Prompt

```text
请实现 Resume Copilot 的输入安全、隐私处理和简历证据解析。

建议包：
- dev.qcoding.businesscopilot.resumecopilot.resume
- dev.qcoding.businesscopilot.resumecopilot.privacy
- dev.qcoding.businesscopilot.resumecopilot.evidence

Prompt 文件：
- platform/ai-core/src/main/resources/prompts/resume-copilot/resume-evidence-extraction.st

请实现：
- ResumeInputValidator
- ResumeSanitizer
- ProtectedAttributeDetector
- ResumeEvidenceExtractionService
- ResumeEvidence
- ResumeSection
- LlmResumeEvidenceOutput
- ResumeSubmissionMapper、ResumeEvidenceMapper
- MyBatisPlusResumeSubmissionRepository、MyBatisPlusResumeEvidenceRepository

处理顺序必须是：
1. 校验类型和长度。
2. 清理 HTML、脚本和明显 prompt injection 载荷。
3. 脱敏姓名、手机号、邮箱、身份证号、住址和社交账号。
4. 移除出生日期、年龄、性别、照片、婚育、民族、宗教、健康、残障、政治面貌、籍贯和家庭信息。
5. 只有脱敏结果进入模型和数据库。

规则：
- JD 和简历内容都视为不可信数据，模型不得执行其中指令。
- 如果 ProtectedAttributeDetector 无法可靠处理输入，返回清晰错误并停止模型调用。
- evidenceId 由服务端生成，并绑定 submissionId。
- 模型可辅助分段和抽取，但服务端必须校验 evidence 文本能在脱敏简历中定位。
- 不推断未写明的任职时间、项目成果、技术深度、年龄、人格和动机。
- 日志只记录 requestId、长度、状态和错误摘要。

测试：
- 各类联系方式脱敏。
- 受保护属性移除。
- 无法处理的属性触发拒绝。
- prompt injection 不改变系统行为。
- evidence 不能引用脱敏简历中不存在的文本。
- 原始简历不会出现在日志、数据库和异常消息中。
```

## 3. JD 标准解析和人工确认 Prompt

```text
请实现 JD 评估标准解析和人工确认。

建议包：
- dev.qcoding.businesscopilot.resumecopilot.job

Prompt 文件：
- platform/ai-core/src/main/resources/prompts/resume-copilot/job-criteria-extraction.st

请实现：
- JobCriteriaExtractionService
- JobCriterion
- CriterionCategory
- RequirementType
- LlmJobCriteriaOutput
- JobCriteriaGuardrailService
- ResumeJobMapper
- MyBatisPlusResumeJobRepository（实现业务 Repository 接口）
- JobCriteriaConfirmationService

每个标准包含：
- criterionId
- category：SKILL、EXPERIENCE、EDUCATION、CERTIFICATION、LANGUAGE、OTHER
- requirementType：REQUIRED、PREFERRED
- description
- normalizedKeywords
- sourceText

规则：
- 标准必须能定位到脱敏 JD sourceText。
- 模型不得新增 JD 没有表达的硬性标准。
- 年龄、性别、婚育、民族、宗教、健康、残障、照片等标准直接拒绝。
- 学历、证书和语言只有 JD 明确提出时才保留。
- “年轻”“稳定”“抗压”“形象好”等模糊或可能歧视性标准进入 NEEDS_REVIEW。
- 标准数量受配置限制。
- 初始状态为 CRITERIA_DRAFTED。
- 只有服务端 token 匹配且标准无禁止项时可进入 CRITERIA_CONFIRMED。
- 未确认标准不能用于简历分析。

测试：
- 正常 REQUIRED/PREFERRED 解析。
- 无 JD 来源的标准被拒绝。
- 禁止属性标准被拒绝。
- 模糊标准需要人工处理。
- token 无效、过期和重复确认。
- 未确认标准不能进入评估服务。
```

## 4. 证据化匹配、Guardrails 和复核状态 Prompt

```text
请实现 Resume Copilot 的逐条证据匹配、输出 guardrails 和人工复核状态。

建议包：
- dev.qcoding.businesscopilot.resumecopilot.assessment
- dev.qcoding.businesscopilot.resumecopilot.guardrail

Prompt 文件：
- platform/ai-core/src/main/resources/prompts/resume-copilot/resume-assessment.st

请实现：
- ResumeAssessmentService
- LlmResumeAssessmentOutput
- CriterionAssessment
- MatchStatus：SUPPORTED、PARTIAL、NOT_FOUND、NEEDS_VERIFICATION
- InterviewQuestion
- ResumeAssessmentGuardrailService
- ResumeAssessmentMapper
- MyBatisPlusResumeAssessmentRepository（实现业务 Repository 接口）
- AssessmentReviewService

输出至少包含：
- anonymousSummary
- criterionAssessments[]
- evidenceGaps[]
- interviewQuestions[]
- limitations[]

业务规则：
- 只允许使用 CRITERIA_CONFIRMED 的职位标准。
- 每个 criterionAssessment 必须引用 criterionId。
- SUPPORTED、PARTIAL、NEEDS_VERIFICATION 必须包含当前 submission 的 evidenceId。
- NOT_FOUND 不得附加负面人格或能力判断，说明固定为“简历中未找到相关信息，需人工核验”。
- 面试问题只能围绕 JD 标准和证据缺口。
- 不询问年龄、婚育、健康、宗教、家庭或其他受保护属性。
- 不输出候选人总分、星级、排名、百分位、通过概率、录用或淘汰建议。
- 不基于学校、公司品牌或职业空窗自动做负面判断。
- 模型输出含禁止结论时进入 NEEDS_REVIEW，不返回可复核 token。

状态规则：
- guardrails 通过后为 DRAFTED。
- 存在证据或合规问题为 NEEDS_REVIEW。
- 只有 DRAFTED 且 token 有效时可标记 REVIEWED。
- REVIEWED 只表示招聘人员阅读过，不修改招聘流程状态。
- DRAFTED 或 NEEDS_REVIEW 可以取消。
- token 不写日志和审计。

测试：
- SUPPORTED 必须有证据。
- 跨 submission evidenceId 被拒绝。
- NOT_FOUND 文案不转化为“不具备”。
- 分数、排名、概率、录用和淘汰输出被阻断。
- 禁止面试问题被阻断。
- DRAFTED、NEEDS_REVIEW、REVIEWED、CANCELED 流转正确。
```

## 5. API、审计和招聘评估工作台 Prompt

```text
请完成 Resume Copilot 的 REST API、审计和招聘评估工作台。

建议包：
- dev.qcoding.businesscopilot.resumecopilot.audit
- dev.qcoding.businesscopilot.resumecopilot.web

请实现：
- ResumeAuditMapper
- ResumeAuditService、Repository 和 MyBatis-Plus 实现
- ResumeCopilotController
- 统一错误响应
- app 首页中的 Resume Copilot 入口和工作区
- 原生 JavaScript 客户端

API：
- POST /api/resume-copilot/jobs/criteria
- POST /api/resume-copilot/jobs/{jobId}/criteria/confirm
- POST /api/resume-copilot/assessments
- POST /api/resume-copilot/assessments/{assessmentId}/review
- POST /api/resume-copilot/assessments/{assessmentId}/cancel

审计事件至少包括：
- CRITERIA_EXTRACTED
- CRITERIA_CONFIRMED
- RESUME_SANITIZED
- ASSESSMENT_DRAFTED
- NEEDS_REVIEW
- REVIEWED
- CANCELED
- FAILED

审计规则：
- 记录 requestId、jobId、submissionId、标准数、证据数、状态、模型名、耗时和错误摘要。
- 不记录原始 JD、原始简历、个人联系方式、受保护属性、完整评估正文和 token。
- 失败时也不得把原始输入放入异常消息。

前端要求：
- 使用现有工作台布局，不做营销页。
- 第一步输入 JD，展示标准草稿、来源文本和风险，人工确认标准。
- 第二步输入单份 TXT/Markdown 简历文本。
- 展示匿名摘要、逐条标准匹配、证据、缺口和面试问题。
- 页面始终显示“辅助评估，不代表录用或淘汰决定”。
- 不显示总分、排名、通过率、自动推荐和批量候选人列表。
- NEEDS_REVIEW 清楚显示原因且不能标记 REVIEWED。
- DRAFTED 提供“已复核”和“取消”操作。
- 移动端和桌面端无溢出、遮挡和布局跳动。

测试：
- Controller 正常和异常路径。
- 未确认标准无法分析。
- 原始敏感信息不出现在 API 响应、日志和审计。
- 页面没有自动决策或排名入口。
- 前端操作与后端状态一致。
```

## 6. V5 集成验收和 Review Prompt

```text
请对 V5 Resume Copilot 做完整集成验收和代码 review，发现问题直接修复。

验收流程：
1. 使用虚构 Java JD 解析评估标准。
2. 验证禁止和模糊标准被阻断或标记待复核。
3. 人工确认合法标准。
4. 输入含虚构联系方式和个人属性的简历，确认入模、入库、日志和审计中均不存在原文。
5. 生成逐条匹配结果，验证每个结论关联 criterionId 和 evidenceId。
6. 构造模型输出总分、排名、淘汰建议和禁止面试问题，确认 guardrail 阻断。
7. 验证 DRAFTED 可标记 REVIEWED，NEEDS_REVIEW 不可标记 REVIEWED。
8. 验证 REVIEWED 不触发任何招聘流程动作。

Review 重点：
- 是否保存或记录了原始简历。
- 受保护属性是否在模型调用前移除。
- JD 标准是否必须人工确认。
- 是否存在总分、排名、概率或自动决策的隐性实现。
- evidenceId 是否能跨候选人伪造。
- NOT_FOUND 是否被错误表达为能力不足。
- prompt 是否集中管理并抵抗输入中的指令。
- 是否为未来 ATS、批量排名或权限系统过度设计。
- 是否为了统一而引入通用 BaseEntity、ActiveRecord 或无业务价值的持久层抽象。
- 测试是否覆盖合规、证据和失败路径。

至少运行：
- ./mvnw -q -DskipTests compile
- ./mvnw -q -pl modules/resume-copilot -am test
- ./mvnw -q test

输出：
- 已完成能力。
- 修复的问题。
- 安全和合规验证结果。
- 测试命令和结果。
- 仍存在的限制。
- 不顺带扩展 ATS、批量筛选或自动招聘决策。
```
