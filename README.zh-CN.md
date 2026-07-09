# Spring AI Business Copilot

[English](README.md) | 简体中文

Spring AI Business Copilot 是一个面向个人开发者、中小团队和企业内部系统的 Java AI 业务智能助手套件。

它不是另一个 AI 框架，而是一组可以直接运行、学习、改造和接入真实业务的 Spring AI 应用模块。

> **V3 状态：** Data Copilot 已实现且稳定。Knowledge Copilot（企业知识库助手）已作为第二模块实现。Support Copilot（智能客服助手）已作为第三模块实现。Resume Copilot 和 Report Copilot 仍作为后续候选。

![Data Copilot 工作台](img.png)

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
#   SPRING_AI_OPENAI_API_KEY=<your-api-key>
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

## 第二模块：Knowledge Copilot

Knowledge Copilot 是第二个业务模块，用于基于企业内部文档进行问答，并返回来源引用。

**核心能力：**

- 文档上传（Markdown、TXT），自动分片和向量化
- 基于 pgvector 的语义检索，topK 和相似度阈值可配置
- LLM 驱动的答案生成，强制带有来源引用
- 引用 guardrail 校验 — 无引用的回答会被拒绝
- 知识库无相关内容时返回"无依据"
- 敏感内容脱敏（手机号、邮箱、token、secret、password、id_card）
- 问答审计日志
- 文档启用/停用控制检索范围

**Prompt 约束：** LLM 被指示只能基于提供的知识片段回答，不得使用模型常识补充企业内部事实。每个关键结论必须对应引用。不确定时输出 `NO_EVIDENCE`。

完整模块文档见 [docs/knowledge-copilot.md](docs/knowledge-copilot.md)。

---

## 第三模块：Support Copilot

Support Copilot 是第三个业务模块，定位为智能客服助手，帮助客服团队分类工单、识别紧急程度和情绪、检索知识库依据，并生成需要人工确认的回复草稿。

**核心能力：**

- 工单分类（退款、开通、故障、账号安全、计费、产品使用等）
- 情绪识别（中立、困惑、不满、愤怒）
- 紧急程度判断（低、中、高、严重）
- 通过 Knowledge Copilot 集成检索知识依据
- AI 生成带有强制引用的回复草稿
- 高风险工单自动转人工（退款、赔偿、安全、故障）
- 回复草稿风险 guardrail — 拦截禁止承诺（退款承诺、明确时效等）
- 服务端确认 token 机制 — 不信任客户端传递的草稿正文
- 完整审计链路（分类、草稿生成、转人工、确认、取消、失败）
- 所有输入输出敏感信息脱敏

**重要边界：**
- 不自动发送消息 — 所有回复需人工确认
- 不执行真实退款、订单、账号、赔偿或合同操作
- 不接入真实客服平台
- 不实现多渠道聚合、排班、SLA 流转
- 不存储或训练未脱敏客户数据

完整模块文档见 [docs/support-copilot.md](docs/support-copilot.md)。

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
│   ├── data-copilot/               # Data Copilot 模块（V1）
│   ├── knowledge-copilot/          # Knowledge Copilot 模块（V2）
│   └── support-copilot/            # Support Copilot 模块（V3）
├── examples/
│   ├── docker-compose.yml          # 一键启动（PostgreSQL + pgvector）
│   └── .env.example                # 环境变量模板
├── scripts/
│   └── install-jdk21.sh            # 当前项目专用 JDK 安装脚本
├── Dockerfile                       # 多阶段构建
└── docs/
    ├── data-copilot.md             # Data Copilot 文档
    ├── knowledge-copilot.md        # Knowledge Copilot 文档
    └── support-copilot.md          # Support Copilot 规划
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
