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
- 应用连接池在建立连接时将 PostgreSQL 会话时区设置为 `Asia/Shanghai`（UTC+8）；`TIMESTAMPTZ` 仍保存绝对时间，查询和数据库默认值按东八区呈现。
- 用户和平台采用软删除；审计数据不提供业务删除接口。
- 审计表保存用户名和平台编码快照，不对 IAM 建强外键。
- 当 `audit_event` 达到千万级或需要差异化留存时，再改为按月分区。
- 已执行的 Flyway 文件不得修改，只能追加新版本。
- OAuth2 三张协议表的字段集合与 Spring Security Authorization Server 7.1.1 官方 JDBC Schema 保持一致；按 PostgreSQL 官方适配要求使用 `TIMESTAMPTZ` 和 `TEXT`。
- 授权和同意记录通过外键归属于注册客户端；令牌等值查询使用非空部分 HASH 索引；`iam_oauth_client.registered_client_id` 关联平台归属元数据与协议客户端。

## 首位管理员

- Flyway 种子迁移不保存默认用户或默认密码。
- 首位管理员由显式启用的应用初始化命令创建，密码经 `iam_security_policy` 校验后只保存哈希。
- 初始化仅允许在 `iam_user` 为空时执行，并在同一事务内写入用户及全局 `SUPER_ADMIN` 角色关系。
- 已初始化的同名超级管理员会被幂等跳过，任何其他已有用户都会阻止初始化。

## 首个 OAuth 客户端

- 首个管理后台浏览器客户端由显式启用的初始化命令创建，不在 Flyway 中硬编码环境相关回调地址。
- 初始化使用官方 `RegisteredClientRepository`，并在同一数据库事务内写入 `oauth2_registered_client`、`iam_oauth_client` 和 `iam_oauth_redirect_uri`。
- 相同完整配置可幂等跳过；协议表与 IAM 表记录残缺或配置漂移时拒绝自动覆盖。
- 默认客户端为公共客户端，使用 Authorization Code + PKCE，刷新令牌每次使用后轮换。
