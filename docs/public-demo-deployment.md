# Railway 长期公网体验部署手册

> 适用模式：`public-demo`。公网域名必须在数据库迁移、虚构数据初始化和知识索引完成后再开放。

## 1. 运行边界

- 不开放注册，只使用固定 Admin、Operator、Reviewer 账号。
- 不上传真实简历、制度、客户资料或数据库。
- 五模块 AI 执行都从服务端 `scenarioId` 进入；浏览器不能提交正文资源或数据范围。
- Data 只连接固定只读账号，仅允许查询 6 张虚构业务表。
- 客服回复、报告、招聘评估都只生成草稿或建议，不自动发送、发布或决策。
- 每客户端每天默认 20 次业务操作，全站每天默认 500 次外部模型调用，最大并发 4。
- 普通账号只能访问场景目录和四类带一次性 token 的确认动作；原始模块列表、队列、导出和编辑接口默认拒绝。

## 2. Railway 服务

在同一 Railway Project 中创建：

1. PostgreSQL 服务，建议命名为 `Postgres`。
2. 从本仓库创建应用服务。根目录的 `railway.toml` 会使用 `Dockerfile`、`/actuator/health`、300 秒健康检查和崩溃自动重启。
3. 先不要生成公网域名。

Railway 的 Postgres 必须安装 pgvector 扩展。应用 Flyway V1 会执行 `CREATE EXTENSION IF NOT EXISTS vector`；所用数据库账号需要具备该权限。

应用只接受 Railway 代理转发的流量。`public-demo` profile 使用 Tomcat 原生可信代理解析，
额度指纹只读取容器解析后的客户端地址，不直接信任调用方传入的 `X-Forwarded-For`。
如果部署拓扑不是 Railway 默认私网代理，需要通过
`SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES` 显式配置可信代理范围，不能把应用端口直接暴露到公网。

## 3. 创建 Data 只读账号

在第一次启动应用前，从能够访问 Railway 私有数据库的受控终端执行：

```bash
export PLATFORM_DATABASE_URL='Railway Postgres 的私有 DATABASE_URL'
export BUSINESS_QUERY_DATASOURCE_PASSWORD='随机强密码'
./scripts/create-public-demo-reader.sh
```

脚本不保存密码。它创建 `business_reader` 登录角色和 `business_copilot_reader` 权限组；Flyway V10 随后只向权限组授予 `customers`、`products`、`orders`、`order_items`、`refunds`、`marketing_events` 的 `SELECT`。

## 4. Railway Variables

下列值在应用服务的 Variables 中配置。`${{Postgres.*}}` 是 Railway 跨服务引用，不要替换为公网数据库地址。

```dotenv
SPRING_PROFILES_ACTIVE=prod,public-demo
SPRING_DATASOURCE_URL=jdbc:postgresql://${{Postgres.PGHOST}}:${{Postgres.PGPORT}}/${{Postgres.PGDATABASE}}
SPRING_DATASOURCE_USERNAME=${{Postgres.PGUSER}}
SPRING_DATASOURCE_PASSWORD=${{Postgres.PGPASSWORD}}

BUSINESS_QUERY_DATASOURCE_ENABLED=true
BUSINESS_QUERY_DATASOURCE_URL=jdbc:postgresql://${{Postgres.PGHOST}}:${{Postgres.PGPORT}}/${{Postgres.PGDATABASE}}
BUSINESS_QUERY_DATASOURCE_USERNAME=business_reader
BUSINESS_QUERY_DATASOURCE_PASSWORD=与创建只读账号时相同的随机强密码

BUSINESS_COPILOT_ADMIN_USERNAME=admin
BUSINESS_COPILOT_ADMIN_PASSWORD=至少16字符的随机强密码
BUSINESS_COPILOT_OPERATOR_USERNAME=operator
BUSINESS_COPILOT_OPERATOR_PASSWORD=至少16字符的随机强密码
BUSINESS_COPILOT_REVIEWER_USERNAME=reviewer
BUSINESS_COPILOT_REVIEWER_PASSWORD=至少16字符的随机强密码
BUSINESS_COPILOT_PUBLIC_DEMO_FINGERPRINT_SECRET=至少32字符的随机密钥
```

启用实时 AI 时再配置：

```dotenv
SPRING_AI_MODEL_CHAT=openai
SPRING_AI_OPENAI_CHAT_API_KEY=聊天模型密钥
SPRING_AI_OPENAI_CHAT_BASE_URL=https://api.deepseek.com
SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL=deepseek-v4-flash

SPRING_AI_MODEL_EMBEDDING=openai
SPRING_AI_OPENAI_EMBEDDING_API_KEY=向量模型密钥
SPRING_AI_OPENAI_EMBEDDING_BASE_URL=https://api.openai.com
SPRING_AI_OPENAI_EMBEDDING_MODEL=text-embedding-3-small
SPRING_AI_OPENAI_EMBEDDING_DIMENSION=1536
```

Key 和密码只放 Railway Variables，建议设为 sealed variable；不得写入 `.env`、代码、日志、构建参数或初始化脚本。未启用模型或模型异常时，页面会明确提示实时执行失败，再允许用户查看标记为 `PREGENERATED` 的示例结果。

可选额度与成本变量：

```dotenv
BUSINESS_COPILOT_PUBLIC_DEMO_CLIENT_DAILY_OPERATIONS=20
BUSINESS_COPILOT_PUBLIC_DEMO_GLOBAL_DAILY_MODEL_CALLS=500
BUSINESS_COPILOT_PUBLIC_DEMO_MAX_CONCURRENT_EXECUTIONS=4
BUSINESS_COPILOT_INPUT_TOKEN_PRICE_PER_MILLION=
BUSINESS_COPILOT_OUTPUT_TOKEN_PRICE_PER_MILLION=
```

## 5. 初始化和开放顺序

1. 部署应用并确认 `/actuator/health` 为 `UP`。
2. 使用 Admin 登录 `/admin`，执行“初始化虚构数据”；或在受控终端执行：

   ```bash
   export ADMIN_BASE_URL='https://临时内部访问地址'
   export ADMIN_USERNAME='admin'
   export ADMIN_PASSWORD='Railway Variables 中的 Admin 密码'
   ./scripts/seed-public-demo.sh
   ```

3. 管理台确认 15 个场景、6 份系统知识资料和索引任务。启用 Embedding 时等待全部资料进入 `INDEXED`；未启用时确认文本检索降级状态符合预期。
4. 用 Operator 账号分别执行五模块至少一个范例；Data 必须在执行前显示实际 SQL。
5. 验证示例结果显示 `PREGENERATED`、场景版本和生成时间。
6. 完成备份与回滚演练后，再生成 Railway 公网域名。

## 6. 恢复初始状态

管理台先展示将删除的临时数据数量，再签发 10 分钟有效的一次性 token。第二步必须输入固定文案：

```text
恢复公网演示初始数据
```

也可以运行：

```bash
export ADMIN_BASE_URL='https://体验站域名'
export ADMIN_USERNAME='admin'
export ADMIN_PASSWORD='Admin 密码'
./scripts/reset-public-demo.sh
```

恢复只删除 demo 账号产生的临时查询候选、客服工单、报告草稿、招聘分析和非系统知识资料；不清除每日额度、Admin 操作记录和系统审计，不连接任何外部业务源。

## 7. 上线检查

- 连续初始化两次，场景、文档版本和索引任务不重复。
- Operator 无法访问 `/admin`；普通账号不能读取共享队列、原始模块对象、完整制度、简历、数据库范围、Prompt 或 Key。
- 一次性确认 token 只随当前场景结果返回，并且只能用于对应对象的查询执行、客服草稿确认、报告确认或招聘复核。
- 手机号、身份证号、银行卡号、邮箱、API Key、JWT、私钥和提示注入均由服务端阻断。
- 24 小时临时数据、7 天操作记录、30 天用量聚合按配置清理，`systemManaged=true` 永久保留。
- Railway 重启后场景、索引、额度和任务状态仍在 PostgreSQL。
- 数据库备份、上一个成功 Deployment 回滚和恢复脚本均已演练。
