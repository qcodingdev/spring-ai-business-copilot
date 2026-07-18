# Prompt 10: 构建和测试收口

```text
请做 V1 功能闭环后的构建和轻量测试收口。

目标：
- 确认 Data Copilot 主流程可编译、可启动、可演示。
- 只补必要测试，不追求测试数量。

请处理：
- 让 mvn -q -DskipTests compile 通过。
- 尽量让 mvn test 通过。
- 如果 JDK 24 下 Mockito inline self-attach 仍失败，优先用最小配置修正；例如补充合适的 Mockito agent/surefire 配置，或调整测试避免不必要的 Mockito mock。
- 补充少量关键测试：
  - confirmation token 有效/无效。
  - query executor 成功和拒绝非只读 SQL。
  - execute API 不接受客户端 SQL。

要求：
- 不为了测试大改业务设计。
- 不补大而全测试矩阵。
- 测试要稳定，不依赖真实外部 AI API。

边界：
- 不引入复杂 E2E 框架。
- 不要求覆盖所有 SQL 语法边界。
- 不要求真实模型调用测试。
```
