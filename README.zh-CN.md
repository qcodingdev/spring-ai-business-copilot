# Spring AI Business Copilot

[English](README.md) | 简体中文

Spring AI Business Copilot 是一个面向个人开发者、中小团队和企业内部系统的 Java AI 业务智能助手套件。

它不是另一个 AI 框架，而是一组可以直接运行、学习、改造和接入真实业务的 Spring AI 应用模块。

> **V1 状态：** 目前只实现了 Data Copilot。其他模块（Resume Copilot、Support Copilot、Knowledge Copilot、Report Copilot）仅预留位置，尚未实现。
---

## 快速开始

### 前置条件

- Java 21（也可以运行 `./scripts/install-jdk21.sh`，安装到当前项目的 `.jdk/`）
- Maven 3.9+
- PostgreSQL 16（或 Docker）
- OpenAI 兼容模型 API Key（可选；无 Key 时应用仍可启动，AI 功能不可用）

### 方式一：Docker Compose（推荐）

```bash
cd examples
cp .env.example .env
# 如果有 API Key，编辑 .env 添加：
#   SPRING_AI_OPENAI_API_KEY=sk-...
docker compose up --build
```

应用启动后访问 **http://localhost:8080**。PostgreSQL 暴露在 5432 端口。

Flyway 会在首次启动时自动创建示例业务表（customers、products、orders 等）和审计日志表。

### 方式二：本地开发

1. 安装当前项目专用 JDK 21：

```bash
./scripts/install-jdk21.sh
./mvnw -version
```

`./mvnw` 只会使用 `.jdk/` 下的 JDK，不会修改全局 `JAVA_HOME`。

2. 启动 PostgreSQL 并创建数据库：

```sql
CREATE USER copilot WITH PASSWORD 'copilot';
CREATE DATABASE business_copilot OWNER copilot;
```

3. 运行应用：

```bash
./mvnw spring-boot:run -pl app/business-copilot-app \
  -DSPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/business_copilot \
  -DSPRING_DATASOURCE_USERNAME=copilot \
  -DSPRING_DATASOURCE_PASSWORD=copilot \
  -DSPRING_AI_MODEL_CHAT=openai \
  -DSPRING_AI_OPENAI_API_KEY=<your-api-key> \
  -DSPRING_AI_OPENAI_BASE_URL=https://api.deepseek.com \
  -DSPRING_AI_OPENAI_CHAT_OPTIONS_MODEL=deepseek-v4-flash
```

4. 浏览器打开 **http://localhost:8080**。

### Spring AI / OpenAI 兼容模型配置

应用使用 Spring AI 的 OpenAI 兼容 API。通过环境变量或 `application.yml` 配置：

| 变量 | 默认值 | 说明 |
|---|---|---|
| `SPRING_AI_MODEL_CHAT` | `openai` | 设为 `none` 可关闭 AI 功能 |
| `SPRING_AI_OPENAI_API_KEY` | _(空)_ | API Key |
| `SPRING_AI_OPENAI_BASE_URL` | `https://api.deepseek.com` | API 基础地址（可替换为兼容服务商） |
| `SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL` | `deepseek-v4-flash` | 模型名称 |

`SPRING_AI_MODEL_CHAT=none`（默认）时 AI 功能关闭，工作台可正常加载但 SQL 生成会报错。适合无 Key 时验证基础设施。

---

## 第一模块：Data Copilot

Data Copilot 是数据库查询助手。用户用自然语言提问，系统生成安全的 SQL 并返回可解释的查询结果。

**核心流程：**

1. 用户输入业务问题（例如"上个月总销售额是多少？"）
2. 系统生成 SQL 候选并经过 guardrails 校验
3. 用户确认 SQL 后系统执行
4. 系统执行只读 SQL、脱敏敏感字段、返回结果和 AI 解释

**安全默认值：**

- **默认只读** — 只允许 `SELECT` 和 `WITH ... SELECT`；`INSERT`、`UPDATE`、`DELETE`、`DROP` 等全部拦截
- **执行前确认** — SQL 展示给用户确认后才执行；只执行服务端保存的 SQL，不信任客户端传回的 SQL
- **双重 guardrails** — 生成阶段和执行阶段各校验一次
- **敏感字段脱敏** — phone 和 email 在结果中部分遮蔽；password、token、secret、id_card 直接阻断查询
- **审计日志** — 查询全生命周期（成功、失败、校验失败、用户取消）均记录审计
- **结果截断** — 默认最多返回 100 行

完整模块文档见 [docs/data-copilot.md](docs/data-copilot.md)。

---

## 项目结构

```
spring-ai-business-copilot/
├── app/business-copilot-app/       # Spring Boot 应用入口
├── platform/
│   ├── ai-core/                    # LLM 集成、prompt 模板
│   ├── ai-guardrails/              # SQL 安全、敏感字段策略
│   ├── ai-tool-audit/              # 查询审计日志
│   └── common-web/                 # 统一 API 响应、异常处理
├── modules/
│   └── data-copilot/               # Data Copilot 模块（V1）
├── examples/
│   ├── docker-compose.yml          # 一键启动
│   └── .env.example                # 环境变量模板
├── scripts/
│   └── install-jdk21.sh            # 当前项目专用 JDK 安装脚本
├── Dockerfile                       # 多阶段构建
└── docs/
    └── data-copilot.md             # 模块文档
```

---

## 技术栈

- Java 21
- Spring Boot 4.1
- Spring AI 2.0
- Spring JDBC + PostgreSQL
- Flyway 数据库迁移
- Thymeleaf（工作台 UI）
- Maven 多模块

---

## 许可证

本项目许可证见 [LICENSE](LICENSE) 文件。
