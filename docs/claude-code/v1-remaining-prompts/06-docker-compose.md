# Prompt 06: Docker Compose 一键启动

```text
请补充 Docker Compose 一键启动能力。

位置：
- examples/docker-compose.yml
- Dockerfile 或 app 模块 Docker 构建配置
- 可选：examples/.env.example

目标：
- 一条 docker compose 命令启动 PostgreSQL 和 Spring Boot 应用。
- Flyway 自动初始化示例业务库和审计表。

要求：
- PostgreSQL 暴露本地端口，默认数据库名可读。
- 应用通过环境变量读取 datasource 和 Spring AI 配置。
- 不提交真实 API Key。
- OpenAI 兼容模型配置用环境变量占位。
- README 后续能引用这套启动方式。

轻量验证：
- docker compose config 通过。
- 能说明如何启动和访问应用首页。

边界：
- 不做 Kubernetes。
- 不做云部署脚本。
- 不做生产级镜像优化。
```
