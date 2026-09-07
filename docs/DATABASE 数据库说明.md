# 数据库设计

## Schema

```text
ocean_platform
```

## 表清单

| 表 | 所属领域 | 用途 |
|---|---|---|
| `iam_platform` | IAM | 子平台注册信息 |
| `iam_user` | IAM | 用户、密码哈希、有效期和锁定状态 |
| `iam_oauth_client` | IAM | 子平台 OAuth 客户端 |
| `iam_oauth_redirect_uri` | IAM | OAuth 回调地址白名单 |
| `iam_role` | IAM | 全局或平台级角色 |
| `iam_permission` | IAM | 资源动作权限 |
| `iam_user_role` | IAM | 用户角色及平台作用域 |
| `iam_role_permission` | IAM | 角色权限关联 |
| `iam_auth_session` | IAM | 会话管理和刷新令牌哈希 |
| `iam_security_policy` | IAM | 全局安全策略 |
| `oauth2_registered_client` | OAuth2 | Spring Authorization Server 注册客户端 |
| `oauth2_authorization` | OAuth2 | 授权码、访问令牌与刷新令牌协议状态 |
| `oauth2_authorization_consent` | OAuth2 | 用户对客户端的授权确认 |
| `audit_event` | Audit | 登录、操作、安全和系统审计事件 |
| `audit_exception` | Audit | 去重聚合后的异常事件 |
| `wb_metric_daily` | Workbench | 各平台每日指标投影 |

## 迁移原则

- 应用生成 UUID；日志使用数据库自增主键。
- 所有时间使用 `TIMESTAMPTZ`。
- 用户和平台采用软删除；审计数据不提供业务删除接口。
- 审计表保存用户名和平台编码快照，不对 IAM 建强外键。
- 当 `audit_event` 达到千万级或需要差异化留存时，再改为按月分区。
- 已执行的 Flyway 文件不得修改，只能追加新版本。
- OAuth2 三张协议表遵循 Spring Authorization Server 1.2.3 JDBC Schema；`iam_oauth_client.registered_client_id` 关联平台归属元数据与协议客户端。
