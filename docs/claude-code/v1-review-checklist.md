# V1 Review Checklist

本清单用于 Claude Code 每完成一个阶段后自检，也用于 Codex 后续 review。

## 1. 总体架构

- [ ] Maven `groupId` 是 `dev.qcoding`。
- [ ] Java 包名以 `dev.qcoding.businesscopilot` 开头。
- [ ] 保留 Maven 多模块结构。
- [ ] 没有循环依赖。
- [ ] `common-web` 不依赖业务模块。
- [ ] `platform` 模块没有反向依赖 `app`。
- [ ] 第一版只实现 Data Copilot。
- [ ] 没有实现多租户、登录注册、BI 看板、多模型平台、工作流平台。

## 2. 技术栈

- [ ] 使用 Java 21。
- [ ] 使用 Spring Boot 4.1.x。
- [ ] 使用 Spring AI 2.0.x。
- [ ] 使用 Spring Web MVC。
- [ ] 使用 Spring JDBC 执行动态只读 SQL。
- [ ] 使用 PostgreSQL。
- [ ] 使用 Flyway。
- [ ] 使用 JSQLParser。
- [ ] 使用 Thymeleaf 和原生 JavaScript。

## 3. Prompt 管理

- [ ] Prompt 模板在 resources 文件中。
- [ ] service 代码中没有散落大段 prompt。
- [ ] SQL 生成 prompt 明确只读、单语句、白名单、禁止编造。
- [ ] 结果解释 prompt 明确不得编造数字。
- [ ] 模型输出 SQL 是结构化 JSON。

## 4. SQL Guardrails

- [ ] 单条 SELECT 通过。
- [ ] `WITH ... SELECT` 通过。
- [ ] 多语句拒绝。
- [ ] Parser 失败拒绝。
- [ ] 禁止关键字拒绝。
- [ ] 白名单外表拒绝。
- [ ] 高敏字段拒绝。
- [ ] 非聚合查询缺少 limit 拒绝或自动安全补齐。
- [ ] limit 超限拒绝或降到最大值。
- [ ] 校验失败不生成可执行 token。
- [ ] SQL 安全测试覆盖主要拒绝场景。

## 5. 执行确认

- [ ] 前端展示 SQL 后才允许确认。
- [ ] 执行接口不信任客户端 SQL。
- [ ] 服务端保存 SQL 候选。
- [ ] token 使用安全随机数。
- [ ] 候选设置过期时间。
- [ ] 过期 token 不可执行。
- [ ] 执行前再次做 guardrails 防御式校验。

## 6. 查询执行

- [ ] 使用只读查询路径。
- [ ] 设置 query timeout。
- [ ] 设置 max rows。
- [ ] 返回 columns、rows、rowCount、truncated。
- [ ] 空结果处理友好。
- [ ] SQL 异常不暴露堆栈。
- [ ] 执行失败记录审计。

## 7. 敏感数据

- [ ] `phone` 已脱敏。
- [ ] `email` 已脱敏。
- [ ] `id_card` 默认阻断或全遮蔽。
- [ ] `password` 默认阻断。
- [ ] `token` 默认阻断。
- [ ] `secret` 默认阻断。
- [ ] 审计日志不记录完整敏感结果。
- [ ] 示例数据不包含真实个人信息。

## 8. 审计

- [ ] 成功查询写审计。
- [ ] SQL 校验失败写审计。
- [ ] 模型调用失败写审计。
- [ ] SQL 执行失败写审计。
- [ ] 用户取消或未确认有状态记录。
- [ ] 审计表不暴露给自然语言查询 schema。

## 9. 前端

- [ ] 首页是 Data Copilot 工作台。
- [ ] 没有营销 landing page。
- [ ] 问题输入、SQL 候选、校验结果、确认按钮、表格、解释都可见。
- [ ] 校验失败时确认按钮禁用。
- [ ] loading 状态清晰。
- [ ] 错误提示不泄露堆栈。
- [ ] 表格横向滚动。
- [ ] 手机端布局可用。
- [ ] 页面文案清晰，界面不夸大能力。

## 10. Docker 和运行

- [ ] Docker Compose 可启动 PostgreSQL。
- [ ] 应用可连接数据库。
- [ ] Flyway 自动迁移。
- [ ] 示例数据可查询。
- [ ] 不提交真实 API Key。
- [ ] README 说明环境变量。

## 11. 测试和构建

- [ ] `mvn -q -DskipTests compile` 通过。
- [ ] `mvn test` 通过，或清楚说明需要 Docker 的测试如何运行。
- [ ] `mvn -q -DskipTests package` 通过。
- [ ] 核心 SQL guardrails 有单元测试。
- [ ] 敏感字段脱敏有单元测试。
- [ ] 关键 API 有 MockMvc 或集成测试。

## 12. 注释和可读性

- [ ] 关键 public 类有英文 Javadoc。
- [ ] 复杂安全规则有简短中文注释。
- [ ] 注释解释业务原因，不解释显而易见代码。
- [ ] 命名清晰，避免无意义缩写。
- [ ] 没有无关重构和大范围格式化噪音。

