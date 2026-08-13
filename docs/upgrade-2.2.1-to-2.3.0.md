# 从 2.2.1 升级到 2.3.0

本文描述正式 `2.3.0` 从 `v2.2.1` 升级时的兼容边界和上线动作。

## 兼容内容

- 仍是一个 Spring Boot 模块化单体、一个可执行 JAR 和一个运行镜像。
- API 路径、统一响应结构、表单登录、CSRF、`ADMIN`/`OPERATOR`/`REVIEWER`
  服务端权限与 2.2.1 保持兼容。
- 五个业务模块和既有 Flyway V1-V28 历史不改写；V29 增加低基数审计 `locale` 与外部
  连接安全字段，V30 增加知识质量复核维度，V31 收口五模块企业状态与并发约束。
- `development`、`self-hosted`、`public-demo`、`prod` 配置入口保留。

## 需要注意的变化

- 页面由 Thymeleaf/原生 JavaScript 迁移到 Vue 3 + TypeScript + Vite。
- 构建机需要 Node 22/npm 10；Maven 会自行安装固定版本。运行 JAR/镜像不需要 Node。
- SPA history 路由由 Spring MVC 转发到打包后的 `index.html`。
- 默认语言固定为 `zh-CN`，用户可切换并持久化 `en-US`。
- 企业连接默认失败关闭。部署方必须配置 HTTPS 域名白名单和仅保存环境变量名的
  `secretRef`；缺少真实环境变量时连接不会执行。

数据库升级前先备份，再让 Flyway 顺序执行 V29、V30、V31；不要手工改写 Flyway 历史。
V31 会保留但停用不可执行的旧 Report 来源/调度，把旧的外部回写 `CONFIRMED` 状态改为
`UNKNOWN`，并合并历史并发产生的重复外部工单。升级后应由管理员检查停用调度和
`UNKNOWN` 回写，依据外部系统回执处置，不能盲目重试。

应用回滚到 2.2.1 不会自动回滚数据库。V29-V31 含约束、状态和值域变化，正式环境如需
应用回退，应恢复升级前备份并先隔离写流量；不要仅回退 JAR 后继续写入新结构。

## 构建

```bash
./scripts/check-frontend-syntax.sh
./mvnw --batch-mode --no-transfer-progress verify -Psbom
docker compose -f examples/docker-compose.yml config
docker build -t spring-ai-business-copilot:2.3.0 .
```
