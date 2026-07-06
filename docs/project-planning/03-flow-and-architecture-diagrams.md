# 流程图与架构图

## 1. 第一版总流程图

```mermaid
flowchart TD
    A["用户输入业务问题"] --> B["Data Copilot 接收请求"]
    B --> C["读取可访问 Schema 上下文"]
    C --> D["渲染 SQL 生成 Prompt"]
    D --> E["Spring AI 调用模型"]
    E --> F["解析结构化 SQL 候选"]
    F --> G["SQL Guardrails 校验"]
    G --> H{"校验通过？"}
    H -- "否" --> I["返回违规原因"]
    I --> J["写入失败审计日志"]
    H -- "是" --> K["生成服务端 SQL 候选和确认 Token"]
    K --> L["前端展示 SQL、假设和校验结果"]
    L --> M{"用户确认执行？"}
    M -- "否" --> N["取消执行并记录状态"]
    M -- "是" --> O["读取服务端保存的 SQL 候选"]
    O --> P["执行只读查询"]
    P --> Q["结果脱敏与行数截断"]
    Q --> R["渲染结果解释 Prompt"]
    R --> S["Spring AI 生成业务解释"]
    S --> T["返回 SQL、表格结果、解释"]
    T --> U["写入成功审计日志"]
```

## 2. 系统架构图

```mermaid
flowchart LR
    subgraph Client["浏览器"]
        UI["Data Copilot 工作台"]
    end

    subgraph App["app/business-copilot-app"]
        Web["Web 页面与 REST Controller"]
        Config["配置装配"]
    end

    subgraph Module["modules/data-copilot"]
        Orchestrator["DataCopilotService"]
        Schema["SchemaContextService"]
        Candidate["SqlCandidateService"]
        Executor["ReadOnlyQueryExecutor"]
        Explain["ResultExplanationService"]
    end

    subgraph Platform["platform"]
        Core["ai-core\nChatClient / PromptTemplate"]
        Guardrails["ai-guardrails\nSQL 校验 / 脱敏"]
        Audit["ai-tool-audit\n审计事件 / 日志"]
        CommonWeb["common-web\n响应 / 异常 / 分页"]
    end

    subgraph Infra["基础设施"]
        LLM["OpenAI 兼容模型或 Ollama"]
        DB["PostgreSQL 示例业务库"]
    end

    UI --> Web
    Web --> Orchestrator
    Web --> CommonWeb
    Orchestrator --> Schema
    Orchestrator --> Candidate
    Orchestrator --> Executor
    Orchestrator --> Explain
    Orchestrator --> Core
    Orchestrator --> Guardrails
    Orchestrator --> Audit
    Core --> LLM
    Schema --> DB
    Executor --> DB
    Audit --> DB
```

## 3. Maven 模块依赖图

```mermaid
flowchart TD
    Root["root pom"] --> App["app/business-copilot-app"]
    Root --> Data["modules/data-copilot"]
    Root --> AiCore["platform/ai-core"]
    Root --> Guardrails["platform/ai-guardrails"]
    Root --> Audit["platform/ai-tool-audit"]
    Root --> CommonWeb["platform/common-web"]

    App --> Data
    App --> CommonWeb
    Data --> AiCore
    Data --> Guardrails
    Data --> Audit
    Data --> CommonWeb
    Audit --> CommonWeb
```

## 4. Schema 上下文流程图

```mermaid
flowchart TD
    A["应用启动或请求触发"] --> B["读取数据库 Metadata"]
    B --> C["加载可查询表白名单"]
    C --> D["合并字段业务描述"]
    D --> E["标记敏感字段"]
    E --> F["过滤审计表和系统表"]
    F --> G["生成 SchemaContext"]
    G --> H["压缩为 Prompt Schema Section"]
    H --> I["传入 SQL 生成 Prompt"]
```

## 5. 自然语言转 SQL 流程图

```mermaid
flowchart TD
    A["用户问题"] --> B["输入校验"]
    B --> C["获取 SchemaContext"]
    C --> D["渲染 sql-generation Prompt"]
    D --> E["调用 ChatClient"]
    E --> F["解析 JSON 输出"]
    F --> G{"解析成功？"}
    G -- "否" --> H["返回模型输出格式错误"]
    G -- "是" --> I["生成 GeneratedSqlCandidate"]
    I --> J["进入 SQL Guardrails"]
```

## 6. SQL Guardrails 流程图

```mermaid
flowchart TD
    A["SQL 候选"] --> B["规范化 SQL 文本"]
    B --> C["检测多语句"]
    C --> D{"是否单语句？"}
    D -- "否" --> X["拒绝：多语句"]
    D -- "是" --> E["SQL Parser 解析 AST"]
    E --> F{"解析成功？"}
    F -- "否" --> Y["拒绝：无法解析"]
    F -- "是" --> G["检查只读语句类型"]
    G --> H{"SELECT 或 WITH SELECT？"}
    H -- "否" --> Z["拒绝：非只读"]
    H -- "是" --> I["检查禁止关键字"]
    I --> J["检查表字段白名单"]
    J --> K["检查敏感字段策略"]
    K --> L["检查 LIMIT / 最大行数"]
    L --> M{"全部通过？"}
    M -- "否" --> N["返回违规列表"]
    M -- "是" --> O["返回可确认 SQL"]
```

## 7. 执行前确认流程图

```mermaid
sequenceDiagram
    participant U as 用户
    participant UI as 前端工作台
    participant API as Data Copilot API
    participant Store as SqlCandidateStore
    participant DB as PostgreSQL

    U->>UI: 输入自然语言问题
    UI->>API: POST /sql-candidates
    API->>API: 生成 SQL 并校验
    API->>Store: 保存候选 SQL 和确认 Token
    API-->>UI: 返回 SQL、校验结果、candidateId
    UI-->>U: 展示 SQL 和确认按钮
    U->>UI: 点击确认执行
    UI->>API: POST /sql-candidates/{id}/execute
    API->>Store: 校验 candidateId 和 token
    Store-->>API: 返回服务端保存的 SQL
    API->>DB: 执行只读查询
    DB-->>API: 返回结果集
    API-->>UI: 返回表格结果和 AI 解释
```

## 8. 查询执行与脱敏流程图

```mermaid
flowchart TD
    A["已确认 SQL"] --> B["设置 query timeout"]
    B --> C["设置 max rows"]
    C --> D["使用只读数据源执行"]
    D --> E{"执行成功？"}
    E -- "否" --> F["转换为用户可理解错误"]
    F --> G["写入失败审计"]
    E -- "是" --> H["读取 ResultSet Metadata"]
    H --> I["构造 QueryResultTable"]
    I --> J["根据字段名和 schema 标记脱敏"]
    J --> K["标记 rowCount 和 truncated"]
    K --> L["返回安全表格结果"]
```

## 9. AI 结果解释流程图

```mermaid
flowchart TD
    A["用户原始问题"] --> D["渲染 result-explanation Prompt"]
    B["已执行 SQL"] --> D
    C["脱敏后的结果摘要"] --> D
    D --> E["调用 ChatClient"]
    E --> F["生成业务解释"]
    F --> G["事实一致性约束：只解释结果中存在的数据"]
    G --> H["返回结论、关键数字、限制说明"]
```

## 10. 查询审计流程图

```mermaid
flowchart TD
    A["请求开始"] --> B["创建 requestId"]
    B --> C["记录用户问题"]
    C --> D["记录模型生成 SQL"]
    D --> E["记录 Guardrails 校验结果"]
    E --> F{"是否确认执行？"}
    F -- "否" --> G["记录未执行状态"]
    F -- "是" --> H["记录执行 SQL"]
    H --> I{"执行成功？"}
    I -- "否" --> J["记录错误信息和耗时"]
    I -- "是" --> K["记录行数、耗时、模型名称"]
    G --> L["保存审计日志"]
    J --> L
    K --> L
```

## 11. Docker Compose 启动流程图

```mermaid
flowchart TD
    A["docker compose up"] --> B["启动 PostgreSQL"]
    B --> C["初始化数据库和只读用户"]
    C --> D["Spring Boot 应用启动"]
    D --> E["Flyway 执行迁移"]
    E --> F["加载示例业务数据"]
    F --> G["应用健康检查通过"]
    G --> H["访问 Data Copilot 工作台"]
```

