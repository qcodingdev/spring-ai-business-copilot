# 从 2.3.0 升级到 2.3.1

`2.3.1` 是兼容维护版本，修复 Notion 大型/嵌套页面同步，并加强外部系统契约和持续安全
维护。该版本没有数据库迁移，也不改变五模块 API、业务状态或角色边界。

## 升级前

1. 记录当前应用镜像/JAR、配置和数据库备份位置。
2. 确认 Notion 连接具备读取页面内容的 capability，域名仍在外部连接 allowlist 中。
3. 在供应商沙箱中检查 SharePoint、Confluence、Notion 和客服连接使用的对象权限。

## 升级

```bash
git fetch --tags
git checkout v2.3.1
docker compose -f examples/docker-compose.yml build
docker compose -f examples/docker-compose.yml up -d
```

Flyway 仍停留在 V31；启动时不会执行新的数据库 DDL。升级后应同步一个包含超过 100 个块、
分页块和嵌套块的 Notion 测试页面，并确认索引内容完整。超过配置安全预算的页面会明确失败，
需要调整部署参数或拆分源文档，不能接受部分索引。

## 回退

停止 `2.3.1` 应用并重新部署原 `v2.3.0` JAR/镜像即可。因为没有数据库迁移，不需要回退
数据库结构；在回退前仍应保留常规备份和同步运行记录。

## 仍需部署方验证

开源测试使用本地确定性 HTTP 服务，不等同于真实 Notion、SharePoint、Confluence、Jira、
Zendesk、ServiceNow、飞书或企微租户认证。生产上线前仍要在部署方沙箱完成权限、限流、
网络出口和数据保留验收。
