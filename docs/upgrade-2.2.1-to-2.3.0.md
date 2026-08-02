# 从 2.2.1 升级到 2.3.0

本文描述 `2.3.0-SNAPSHOT` 的本地兼容边界；当前开发任务不创建正式版本。

## 兼容内容

- 仍是一个 Spring Boot 模块化单体、一个可执行 JAR 和一个运行镜像。
- API 路径、统一响应结构、表单登录、CSRF、`ADMIN`/`OPERATOR`/`REVIEWER`
  服务端权限与 2.2.1 保持兼容。
- 五个业务模块和既有 Flyway V1-V28 历史不改写；V29 只为五类审计表增加
  低基数 `locale`。
- `development`、`self-hosted`、`public-demo`、`prod` 配置入口保留。

## 需要注意的变化

- 页面由 Thymeleaf/原生 JavaScript 迁移到 Vue 3 + TypeScript + Vite。
- 构建机需要 Node 22/npm 10；Maven 会自行安装固定版本。运行 JAR/镜像不需要 Node。
- SPA history 路由由 Spring MVC 转发到打包后的 `index.html`。
- 默认语言固定为 `zh-CN`，用户可切换并持久化 `en-US`。
- 企业连接默认失败关闭。部署方必须配置 HTTPS 域名白名单和仅保存环境变量名的
  `secretRef`；缺少真实环境变量时连接不会执行。

数据库升级前先备份，再让 Flyway 执行 V29。回滚应用版本时 V29 的附加列可保留，
旧版不会读取它；不要手工改写 Flyway 历史。

## 构建

```bash
./scripts/check-frontend-syntax.sh
./mvnw --batch-mode --no-transfer-progress verify -Psbom
docker compose -f examples/docker-compose.yml config
docker build -t spring-ai-business-copilot:2.3.0-snapshot .
```

