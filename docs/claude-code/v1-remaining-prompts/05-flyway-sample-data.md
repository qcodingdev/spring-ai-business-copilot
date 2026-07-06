# Prompt 05: Flyway 迁移和示例数据

```text
请补充 Data Copilot V1 所需的数据库迁移和示例业务数据。

位置：
- app/business-copilot-app/src/main/resources/db/migration

请实现：
- V1__create_sample_business_tables.sql
- V2__insert_sample_business_data.sql
- V3__create_query_audit_logs.sql

示例表：
- customers
- products
- orders
- order_items
- refunds
- marketing_events
- query_audit_logs

要求：
- 示例数据必须是假数据。
- 邮箱使用 example.com。
- 手机号使用测试号段，不使用真实个人信息。
- 不插入真实 id_card、password、token、secret。
- query_audit_logs 表字段要匹配 ai-tool-audit 的 JdbcQueryAuditRepository。
- Data Copilot schema 白名单不得包含 query_audit_logs。
- app 模块引入 Flyway 和 PostgreSQL driver。
- application.yml 补充 datasource、flyway、data-copilot、guardrails、query execution 默认配置。

轻量验证：
- mvn -q -DskipTests compile 通过。
- 如果本地有 PostgreSQL 或 Docker，可启动后确认 Flyway 迁移成功。

边界：
- 不连接生产库。
- 不做真实个人数据。
- 不做复杂权限 DDL。
```
