# ADR-0004：Data PostgreSQL/MySQL 查询方言边界

- 状态：已采纳
- 日期：2026-07-16

## 背景

当前 Data metadata 实现包含 PostgreSQL 特有 SQL。若先直接增加 MySQL 分支，schema/catalog、标识符、分页和只读诊断会散落到生成、校验和执行层，后续难以证明双方言遵守同一安全契约。

## 决策

1. 在 v1.2.2 PostgreSQL 切片先定义：
   - `SqlDialect`
   - `SchemaMetadataDialect`
   - `ReadOnlyCapabilityInspector`
2. 当前 PostgreSQL 特有 metadata 和诊断逻辑迁入 PostgreSQL 实现。
3. metadata、Prompt schema、生成后校验和执行前校验共享同一 dialect context。
4. schema/table/column、函数、LIMIT、敏感列和 JDBC 资源上限形成双方言共享契约。
5. v1.2.3 只增加 MySQL 实现和双方言契约测试，不重新设计接口。
6. 方言可显式配置或根据 JDBC URL 识别；两者冲突时启动失败关闭。
7. MySQL 只作为业务查询目标，不承载平台表、Flyway、向量和审计。

## 后果

- PostgreSQL 仍是平台唯一数据库和默认 Data 查询实现。
- MySQL metadata 使用 catalog/database 语义和反引号，但不能绕过统一 allowlist。
- 不支持跨库 JOIN、存储过程、多语句或写操作。
- 新增其他数据库必须先证明真实业务需求和完整契约，不通过通用方言平台提前扩张。

## 验证

- PostgreSQL/MySQL 独立 Testcontainers 使用相同查询安全契约。
- quoted identifier、schema/catalog 越权、函数、LIMIT 和资源上限对抗测试。
- 方言配置冲突失败关闭。
- 平台 DataSource 与业务查询 DataSource 使用关系不回退。
