# common-security

## v1.2 职责

为 Data、Support、Report、Resume 提供当前操作者、角色读取、对象动作策略、token 摘要和安全测试辅助。

## 核心契约

- `CurrentActor(actorId, roles)`
- `CurrentActorProvider`
- `BusinessRole`：`ADMIN`、`OPERATOR`、`REVIEWER`
- `ObjectAction`
- `ObjectAccessPolicy`
- 高熵 token 生成、SHA-256 摘要和常量时间比较辅助

## 边界

- Spring Security 适配留在 app。
- 不保存业务对象或确认状态。
- 不实现用户管理、密码、OIDC、LDAP、多租户、通用 ACL 或管理后台。
- ADMIN 可处理全部对象；OPERATOR 只处理自己的对象；REVIEWER 只处理明确复核队列对象。

## 验证

`./mvnw -pl platform/common-security -am test`
