# Prompt 01: SQL 候选确认机制

```text
请在 modules/data-copilot 中实现 SQL 候选确认机制。

目标：
- SQL 生成后，只有通过 guardrails 的候选才能生成 confirmationToken。
- 执行阶段只能使用服务端保存的 SQL，不能信任前端传回的 SQL。

包名：
- dev.qcoding.businesscopilot.datacopilot.confirmation

请实现：
- SqlCandidate
- SqlCandidateStore
- InMemorySqlCandidateStore
- SqlConfirmationService
- SqlCandidateExpiredException
- SqlCandidateNotExecutableException

请调整：
- SqlGenerationResponse 增加 candidateId、confirmationToken、expiresAt。
- SqlGenerationService 在 guardrails 通过时保存候选并返回 token；guardrails 失败时不返回可执行 token。

要求：
- token 使用安全随机数，不能用 requestId 代替。
- 候选默认 10 分钟过期，配置项放在 data-copilot 配置中。
- 取出候选时校验 candidateId、confirmationToken、过期时间、executable。
- 执行接口后续只能传 candidateId + confirmationToken，不能传 SQL。
- 在服务层加一句中文注释说明为什么不能信任客户端 SQL。

轻量测试：
- 有效 token 可取出候选。
- 无效 token 拒绝。
- 过期候选拒绝。
- guardrails 失败候选不生成 token。

边界：
- 第一版只用内存存储。
- 不引入 Redis。
- 不做集群会话一致性。
```
