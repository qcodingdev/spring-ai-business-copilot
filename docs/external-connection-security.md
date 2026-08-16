# 外部连接安全边界

2.3.1 的 SharePoint、Confluence、Notion、Jira、Support、会议纪要和 ATS
REST 请求统一经过 `common-security` 的失败关闭客户端。

- 只允许配置的 HTTPS 主机；开发环境也不会默认允许 HTTP。
- 配置时和每次请求前重新解析 DNS，拒绝 loopback、link-local、私网、组播、
  CGNAT、IPv6 ULA 和云元数据地址；混合公共/私有解析同样拒绝。
- 基础地址与请求必须同 origin，禁用自动重定向，因此 Authorization 不会跨
  origin 转发。
- 连接、读取、整体任务、响应字节、分页、条目总数和 JSON 深度均有上限。
- 连接只持久化 `secretRef` 环境变量名。API/UI 会遮蔽 token、secret、Cookie、
  Authorization 和密码字段；引用不存在时失败关闭。
- 只读操作不做无限重试；外部写操作不自动重试，失败后必须重新预览和确认。
- 对外错误只返回稳定错误码与 request ID，不返回供应商原始异常、完整敏感 URL
  或内部堆栈。

2.3.1 把 Notion 固定到 `2026-03-11` API 契约。页面内容按不透明 `next_cursor`
完整分页，并递归读取 `has_children` 子块；所有块请求共享配置的整体超时，每个文档仍受
最大页数、最大条目和最大层级限制。游标缺失、重复或超过预算时同步失败，不会把部分页面
误标记为完整文档。

SharePoint、Confluence、Notion 以及 Jira Service Management、Zendesk、ServiceNow、
飞书、企微使用本地确定性 HTTP 契约测试验证请求方法、路径、认证、分页/内容映射和内部备注
幂等键。GitHub 每周重新执行 Trivy 仓库与容器扫描；依赖升级按明确范围集中评审，不自动
创建维护分支，并继续经过主 CI、依赖评审和容器门禁。

默认配置位于 `application.yml`，示例环境变量位于 `examples/.env.example`。
生产仍应使用出口防火墙或代理作为第二道网络边界，并在供应商沙箱中验证权限。
