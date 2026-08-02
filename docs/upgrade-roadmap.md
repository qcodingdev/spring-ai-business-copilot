# 2.3.0-SNAPSHOT 升级执行路线

1. 保留 2.2.1 全 Reactor 基线和版本证据。
2. 先定义页面/API/角色/状态/确认/i18n 设计，再迁移到 Vue。
3. 按 Data、Knowledge、Support、Report、HR 顺序接入主闭环和企业能力。
4. 后端统一传播显式 locale，并把低基数 locale 写入审计。
5. 所有 REST 企业连接统一接入 SSRF、超时、大小、分页、条目、JSON 与密钥边界。
6. 删除已无消费者的 Thymeleaf/原生 JS，保留 Spring SPA fallback。
7. 执行前端、Reactor、固定评测、数据库、Docker、运行模式、真实模型和 SBOM 门禁。
8. 更新中英文公开文档、模块文档和虚构数据截图，停在 `2.3.0-SNAPSHOT`。

浏览器控制按 `browser-control-deferred.md` 延期，不混入本版本。

