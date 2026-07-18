# ADR-0001：平台库与业务查询库双 DataSource 边界

- 状态：已采纳
- 日期：2026-07-15

## 背景

平台审计、Knowledge 向量、Support 草稿、Report 和 Resume 状态需要可写平台库；Data Copilot 面向业务数据库时必须使用数据库层只读账号。复用一个高权限 DataSource 会让应用 guardrail 成为唯一防线，也会让外部业务库承载不属于它的平台表。

## 决策

1. `spring.datasource.*` 始终表示平台 DataSource，是 Spring 容器的默认候选。Flyway、平台仓储和未显式限定的 `JdbcTemplate` 只使用它。
2. `business-copilot.data-copilot.datasource.*` 可选创建 `businessQueryDataSource` 和 `businessQueryJdbcTemplate`。两个 Bean 都是非默认候选，只允许 Data Copilot 通过限定名注入。
3. 未启用独立查询库时，Data Copilot 回退到平台 `JdbcTemplate`，保留单库演示体验。
4. 独立查询库连接池声明 JDBC read-only；真正的安全边界仍是数据库账号权限。示例 PostgreSQL 角色只有 CONNECT、schema USAGE 和表 SELECT。
5. 凭据只从部署配置或 secret 注入，不提供 Web 保存明文凭据的能力。

## 后果

- 启用独立查询库不会替换平台主库，也不会让 Flyway 或审计仓储误用只读账号。
- Data Copilot 的 metadata 和 SQL 执行必须始终使用 `businessQueryJdbcTemplate`。
- 运维需要分别监控两个连接；v1.2.2 补充查询库健康、schema 探测、只读能力和权限诊断。
- 当前支持 PostgreSQL/MySQL 查询目标；MySQL 仅用于 Data Copilot 外部业务查询，不承载平台、向量和审计数据。

## 验证

- ApplicationContext 测试同时断言 `dataSource`/`jdbcTemplate` 与 `businessQueryDataSource`/`businessQueryJdbcTemplate` 存在且互不替代。
- Compose 空卷初始化后验证 `business_reader` 的 SELECT=true、UPDATE=false、schema CREATE=false。
- Testcontainers 验证平台库 V1-V12 迁移、历史升级、pgvector 行为和 PostgreSQL/MySQL 只读查询契约。
