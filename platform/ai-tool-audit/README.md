# ai-tool-audit

English | [简体中文](#简体中文)

## English

Audit boundary currently used by Data Copilot's SQL lifecycle.

### Current Capabilities

- Records generation, validation, confirmation, execution, failure, and cancellation metadata.
- Stores SQL lifecycle metadata without storing result rows.
- Supports recent-log pagination for the workbench.

### Known Decision

`AuditService.record` currently fails open when persistence fails. This conflicts with the strict “every executed query is audited” product rule. The framework-hardening phase must define risk-based behavior: SQL execution should fail closed when mandatory audit cannot be persisted, while optional AI explanation may degrade.

### Persistence Plan

The stable `query_audit_logs` CRUD is a MyBatis-Plus migration candidate. Audit policy remains a business decision and cannot be delegated to the ORM.

### Test

```bash
../../mvnw -f ../../pom.xml -pl platform/ai-tool-audit -am test
```

## 简体中文

该模块目前服务 Data Copilot 查询生命周期审计，不保存查询结果明细。

现有审计写入失败会放行主流程，这与“所有执行查询必须审计”存在语义冲突。框架加固阶段需要改为按风险决策：关键 SQL 执行审计应 fail-closed，可选 AI 解释可以降级。

稳定审计表 CRUD 计划迁移到 MyBatis-Plus。
