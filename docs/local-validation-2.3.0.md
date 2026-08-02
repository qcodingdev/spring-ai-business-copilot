# 2.3.0-SNAPSHOT 完整本地验证报告

> 验证日期：2026-07-29  
> 分支：`feature/v2.3-frontend-enterprise-upgrade`  
> 版本：全部 13 个 Maven POM 为 `2.3.0-SNAPSHOT`  
> Git 边界：未暂存、未提交、未推送、未创建 PR/标签/Release

## 1. 本地交付结论

2.3.0 的本地开发范围已完成：Vue 3 + TypeScript + Vite 取代已迁移的
Thymeleaf/原生 JavaScript，继续由 Spring Boot 同域、同镜像交付；登录、公开预览、
工作台、Admin 和五个 Copilot 支持 `zh-CN`/`en-US`；企业 API 具备受控 UI；
外部连接统一经过 HTTPS、allowlist、DNS/IP、重定向、超时、大小、密钥引用和审计边界。

未增加第六个 Copilot、微服务、多租户、复杂 IAM、浏览器 Agent 或跨域独立部署。

## 2. 已执行门禁

| 范围 | 实际命令或方式 | 结果 |
| --- | --- | --- |
| 前端依赖与唯一 lockfile | `npm ci` | 通过，304 个包 |
| Node/npm | Node `22.22.3`、npm `10.9.8` | 通过，Maven 与本地版本一致 |
| 类型/Lint/单测/构建 | `npm run check` | 通过；3 个 Vitest 文件、6 个测试 |
| 双语言与组件行为 | i18n key parity、非法值回退、Intl、API Client 单测 | 通过 |
| E2E | `npm run test:e2e` | Chromium 桌面/手机共 20 个用例通过 |
| 打包后 E2E | `E2E_BASE_URL=http://127.0.0.1:58080 npm run test:e2e` | 真实 Spring 登录后 20 个用例通过 |
| 无障碍/响应式 | Playwright + axe、桌面/Pixel 7、横向溢出与焦点检查 | 无 serious/critical 违规 |
| 固定评测 | `./scripts/check-evaluation-datasets.sh` | 五模块 67 条数据集门禁通过 |
| Shell | `bash -n scripts/*.sh` 与 `sh -n scripts/*.sh` | 通过 |
| Compose | `docker compose ... config --quiet` | 通过 |
| Maven/SBOM | `./mvnw --batch-mode --no-transfer-progress verify -Psbom` | 13 模块全部成功；生成 CycloneDX XML/JSON |
| PostgreSQL/pgvector | `PostgresPgvectorIntegrationTest` | PostgreSQL 16、pgvector、空库 V1→V29 通过 |
| 2.2.1 升级 | V28 写入既有审计记录后迁移到 V29 | 记录保留、五张审计表 locale 补齐 |
| MySQL | Testcontainers `mysql:5.7` 与 `mysql:8.4` | 只读查询、元数据和脱敏通过 |
| Docker build | `docker build -t spring-ai-business-copilot:2.3.0-snapshot .` | 通过；构建上下文约 154 KB |
| 镜像运行时 | image inspect + Compose | Java 21.0.11、UID/GID 10001、无 Node、健康检查 |
| 容器加固 | self-hosted Compose | 只读根文件系统、`cap_drop: ALL`、no-new-privileges |
| 实际 HTTP | `scripts/smoke-test.sh` | 健康、CSRF、登录、工作台、Data schema 通过 |
| public-demo | 独立 Compose + Admin 初始化 + 场景 API | 15 个虚构场景；预生成结果标记正确；原始模块 API 403 |
| 无模型降级 | public-demo 实时场景请求 | HTTP 503 + 稳定 `BIZ_0100`，未伪造实时结果 |
| 真实模型 | `scripts/release-ai-smoke-test.sh` | Data、Knowledge/Embedding、Support、Report、HR 全部通过 |
| 真实英文输出 | Data 请求发送 `Accept-Language: en-US` | HTTP 200；用户可见摘要/假设/警告无中文字符 |
| Git 空白错误 | `git diff --check` | 通过 |

真实模型测试只从已存在且被 Git 忽略的 `examples/.env` 注入密钥；验证过程未输出、
复制或提交密钥。测试数据均为虚构内容。

## 3. 关键安全证据

- `GET /api/session` 统一初始化 CSRF Cookie；前端 POST/PUT/PATCH/DELETE 发送
  `X-XSRF-TOKEN`，401/403/409/5xx 只按稳定 `errorCode` 和 `requestId` 展示。
- locale 进入低基数请求上下文和五模块审计列，不进入指标高基数标签，也不记录正文。
- AI 输出语言由集中 Prompt 上下文控制，并经过确定性语言校验；不符合时仅安全重试一次。
- 外部连接默认 HTTPS 和域名 allowlist，DNS 解析后阻断 loopback、link-local、私网、
  组播、云元数据、CGNAT 与 IPv6 ULA；禁用自动重定向，限制连接/读取/任务超时、
  响应字节、页数、条数和 JSON 深度。
- 外部密钥只保存环境变量引用；缺失密钥失败关闭；页面、API、日志不返回秘密。
- HR 保持证据化人工评估，不增加总分、排名、推荐概率、自动录用或自动淘汰。
- public-demo 默认拒绝原始业务 API；无模型时只允许明确标记的预生成结果，不冒充实时 AI。

## 4. 尚未验证和发布前事项

以下项目需要远端或正式环境，当前不能声明通过：

- GitHub/Gitee 远端 CI、dependency review 和远端安全门禁。
- Trivy/同类容器扫描：本机未安装扫描器；SBOM 已生成，但不能替代镜像漏洞扫描。
- 真实 Jira/Notion/ServiceNow/ATS 等外部厂商端点：未配置可安全使用的测试租户。
- 正式 `prod` 环境启动、域名/TLS、备份恢复和容量压测；本地仅通过生产配置校验测试。
- 正式版 `2.3.0`、发布提交、双远端推送、标签和 Release；均需用户另行授权。

发布授权前必须继续保持 `2.3.0-SNAPSHOT`，重新执行正式版本门禁后才能创建 `v2.3.0`。
