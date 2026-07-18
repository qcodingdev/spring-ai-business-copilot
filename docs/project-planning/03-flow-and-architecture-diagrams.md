# 当前与目标架构图

> 更新日期：2026-07-16

## 1. 当前系统架构

```mermaid
flowchart LR
    Browser["浏览器工作台"] --> App["business-copilot-app"]

    App --> Data["Data Copilot"]
    App --> Knowledge["Knowledge Copilot"]
    App --> Support["Support Copilot"]
    App --> Report["Report Copilot"]
    App --> Resume["Resume Copilot"]

    Data --> Core["ai-core"]
    Data --> Guard["ai-guardrails"]
    Data --> Audit["ai-tool-audit"]
    Knowledge --> Core
    Knowledge --> Guard
    Support --> Core
    Support --> Guard
    Support --> Knowledge
    Report --> Core
    Report --> Guard
    Resume --> Core
    Resume --> Guard

    Core --> Model["OpenAI-compatible model"]
    Data --> DB["PostgreSQL platform DB"]
    Data --> QueryDB["PostgreSQL/MySQL read-only target"]
    Knowledge --> DB
    Support --> DB
    Report --> DB
    Resume --> DB
    Knowledge --> Vector["pgvector"]
```

## 2. 目标模块依赖

```mermaid
flowchart TD
    Root["root BOM and build"] --> App["app"]
    Root --> Modules["business modules"]
    Root --> Platform["platform modules"]

    App --> Data
    App --> Knowledge
    App --> Support
    App --> Report
    App --> Resume

    Data --> AiCore
    Data --> Guardrails
    Data --> ToolAudit
    Data --> CommonWeb

    Knowledge --> AiCore
    Knowledge --> Guardrails
    Knowledge --> CommonWeb

    Support --> AiCore
    Support --> Guardrails
    Support --> CommonWeb
    Support --> KnowledgeAdapter["Knowledge narrow adapter"]
    Data --> CommonSecurity
    Support --> CommonSecurity
    Report --> CommonSecurity
    Resume --> CommonSecurity
```

规则：

- platform 不依赖业务模块。
- Knowledge 不反向依赖 Support。
- Data、Knowledge、Support、Report、Resume 均已进入 Maven reactor。
- 未使用的 `ai-tool-audit` 依赖应删除。

## 3. 显式 JDBC 持久层

```mermaid
flowchart LR
    Service["Business service"] --> Repository["Repository interface"]
    Repository --> JDBC["Explicit JDBC repository"]

    JDBC --> CRUD["CRUD + conditional transitions"]
    JDBC --> Dynamic["Dynamic read-only SQL"]
    JDBC --> Metadata["information_schema"]
    JDBC --> Pgvector["vector insert/search"]

    CRUD --> DB["PostgreSQL + pgvector"]
    Dynamic --> DB
    Metadata --> DB
    Pgvector --> DB
```

平台 Repository 使用默认 PostgreSQL DataSource 和 Spring 事务；Data Copilot 外部查询使用独立只读 DataSource，不参与平台状态写入。

## 4. Spring AI 2.0 结构化输出

```mermaid
flowchart TD
    Input["Sanitized business input"] --> SystemMessage["System instruction"]
    Evidence["Schema / chunks / ticket evidence"] --> UserMessage["User data message"]
    SystemMessage --> ChatClient["Spring AI ChatClient"]
    UserMessage --> ChatClient
    ChatClient --> Entity["entity Type + schema validation"]
    Entity --> BusinessGuardrail["Deterministic business guardrails"]
    BusinessGuardrail --> Draft["Draft / candidate"]
    Draft --> Confirm["Human confirmation"]
    Confirm --> Action["Allowed business action"]
```

结构化输出只提高格式可靠性，不能替代业务 Guardrails。

## 5. Data Copilot 流程

```mermaid
flowchart TD
    Q["用户问题"] --> Schema["读取允许 schema"]
    Schema --> Prompt["集中式 SQL prompt"]
    Prompt --> Entity["结构化 SQL candidate"]
    Entity --> G1["Guardrails 第一次校验"]
    G1 --> Show["展示 SQL 和确认 token"]
    Show --> Human{"用户确认？"}
    Human -- "否" --> Cancel["取消并审计"]
    Human -- "是" --> G2["Guardrails 第二次校验"]
    G2 --> JDBC["JDBC 只读执行"]
    JDBC --> Mask["结果脱敏"]
    Mask --> Explain["AI 解释，失败可降级"]
    Explain --> Audit["执行审计"]
```

Data 动态 SQL executor 只使用专用 JDBC 只读边界。

## 6. Knowledge Copilot 流程

```mermaid
flowchart TD
    Upload["上传 Markdown/TXT"] --> Sanitize["脱敏与校验"]
    Sanitize --> Parse["解析和分片"]
    Parse --> Embed["Spring AI EmbeddingModel"]
    Embed --> Persist["JDBC metadata/chunks/vector"]
    Question["用户问题"] --> QueryVector["问题向量"]
    QueryVector --> Search["JDBC pgvector 检索"]
    Search --> Answer["Spring AI structured answer"]
    Answer --> Citation["引用 guardrail"]
    Citation --> Result["ANSWERED / NO_EVIDENCE / REJECTED"]
```

## 7. Support Copilot 流程

```mermaid
flowchart TD
    Ticket["工单输入"] --> Mask["敏感信息脱敏"]
    Mask --> Classify["分类/情绪/紧急程度"]
    Classify --> Retrieve["Knowledge evidence"]
    Retrieve --> HasEvidence{"有依据且低风险？"}
    HasEvidence -- "否" --> Human["NEEDS_HUMAN"]
    HasEvidence -- "是" --> Draft["回复草稿"]
    Draft --> Guard["承诺/引用 guardrails"]
    Guard --> DRAFTED["DRAFTED"]
    DRAFTED --> Confirm{"人工确认或取消"}
    Confirm --> Final["CONFIRMED / CANCELED"]
```

确认不等于自动发送，MVP 不调用外部客服系统。

## 8. 自动配置目标

```mermaid
flowchart TD
    Imports["AutoConfiguration.imports"] --> Auto["@AutoConfiguration"]
    Auto --> ClassCondition["@ConditionalOnClass"]
    Auto --> PropertyCondition["@ConditionalOnProperty"]
    Auto --> BeanCondition["@ConditionalOnBean / MissingBean"]
    ClassCondition --> Beans["Explicit beans"]
    PropertyCondition --> Beans
    BeanCondition --> Beans
    Beans --> ContextTest["ApplicationContextRunner tests"]
```

业务模块不能依赖宿主应用恰好位于同一个根包下才能工作。

## 9. 框架改造顺序

```mermaid
flowchart LR
    P0["v1.1 SQL/security closeout"] --> P1["v1.2 actor/object policy"]
    P1 --> P2["Database-backed confirmations"]
    P2 --> P3["Module AutoConfig + audit v2"]
    P3 --> P4["PostgreSQL/MySQL query targets"]
    P4 --> NEXT["Knowledge/Support/Report/Resume vertical upgrades"]
```

完整风险、迁移矩阵和验收标准见 `docs/architecture-review-and-framework-plan.md`。
