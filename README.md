# Ocean Admin Service

面向多个业务子平台的统一 Java 管理后台骨架，当前仅包含用户认证、审计日志和工作台三个平台侧领域。

## 技术基线

- Java 21
- Spring Boot 4.1.1
- Spring Security 7.1.1 / OAuth 2.1 Authorization Server / Resource Server
- PostgreSQL + Flyway
- Redis
- MyBatis-Plus
- Actuator / Micrometer

## 模块

```text
ocean-kernel          稳定的公共技术契约
ocean-platform-core   identity、audit、workbench
ocean-bootstrap       应用启动、配置和 Flyway
```

未来业务模块命名为 `ocean-business-<platform-code>`，并在父 `pom.xml` 与 `ocean-bootstrap` 中装配。

## 初始化

开发环境默认启用 `dev` profile。应用启动时动态生成 3072-bit RSA KeyPair，`kid` 根据公钥的 JWK Thumbprint 自动计算。动态密钥只在当前进程内有效，应用重启后已有开发令牌会失效。

启动开发环境：

```powershell
$env:DB_URL='jdbc:postgresql://localhost:5432/ocean_admin'
$env:DB_USERNAME='ocean_admin'
$env:DB_PASSWORD='replace-me'
$env:OAUTH2_ISSUER='http://localhost:8090'
mvn -pl ocean-bootstrap -am spring-boot:run
```

测试环境启用 `test` profile，并必须提供固定 KeyStore。以下命令只用于生成本地测试密钥，生产环境后续由 KMS、HSM 或受控密钥库提供：

```powershell
keytool -genkeypair -alias ocean-admin -keyalg RSA -keysize 3072 -storetype PKCS12 -keystore ocean-admin.p12 -validity 3650
```

启动测试环境：

```powershell
$env:DB_URL='jdbc:postgresql://localhost:5432/ocean_admin'
$env:DB_USERNAME='ocean_admin'
$env:DB_PASSWORD='replace-me'
$env:SPRING_PROFILES_ACTIVE='test'
$env:OAUTH2_ISSUER='http://localhost:8090'
$env:OAUTH2_KEYSTORE_LOCATION='file:./ocean-admin.p12'
$env:OAUTH2_KEYSTORE_PASSWORD='replace-me'
$env:OAUTH2_KEY_ALIAS='ocean-admin'
mvn -pl ocean-bootstrap -am spring-boot:run
```

Flyway 会执行：

```text
V1__init_ocean_platform.sql
V2__seed_platform_core.sql
V3__add_oauth2_authorization_server.sql
V4__add_refresh_token_lifecycle.sql
```

种子脚本不会创建默认管理员和默认密码。首个管理员应通过受控初始化命令创建。

## 首位管理员初始化

初始化命令默认关闭，仅当 IAM 用户表为空时才会创建首位全局管理员。密码只从 `OCEAN_BOOTSTRAP_ADMIN_PASSWORD` 环境变量读取，并按照 `iam_security_policy` 校验后使用带算法标识的 bcrypt 哈希保存。

```powershell
$env:OCEAN_BOOTSTRAP_ADMIN_ENABLED='true'
$env:OCEAN_BOOTSTRAP_ADMIN_USERNAME='admin'
$env:OCEAN_BOOTSTRAP_ADMIN_PASSWORD='replace-with-a-strong-password'
mvn -pl ocean-bootstrap -am spring-boot:run
```

看到管理员创建成功日志后停止进程并清除敏感环境变量；后续启动无需继续启用初始化命令：

```powershell
Remove-Item Env:OCEAN_BOOTSTRAP_ADMIN_PASSWORD
Remove-Item Env:OCEAN_BOOTSTRAP_ADMIN_ENABLED
```

重复执行同一管理员会安全跳过且不会修改密码；如果 IAM 中已经存在其他用户，命令会拒绝执行，避免通过初始化入口提升已有系统权限。

## 首个 OAuth 客户端初始化

管理员创建完成后，通过独立且默认关闭的初始化命令注册管理后台公共客户端。命令会在同一事务内写入 Spring Authorization Server 官方客户端表、IAM 客户端元数据和回调地址；重复执行相同配置会安全跳过，发现残缺记录或配置漂移则拒绝覆盖。

```powershell
$env:OCEAN_BOOTSTRAP_OAUTH_CLIENT_ENABLED='true'
$env:OCEAN_BOOTSTRAP_OAUTH_CLIENT_ID='ocean-admin-web'
$env:OCEAN_BOOTSTRAP_OAUTH_REDIRECT_URI='http://127.0.0.1:3000/login/oauth2/code/ocean-admin'
$env:OCEAN_BOOTSTRAP_OAUTH_POST_LOGOUT_REDIRECT_URI='http://127.0.0.1:3000/'
mvn -pl ocean-bootstrap -am spring-boot:run
```

该客户端固定采用 Authorization Code + Refresh Token、公共客户端认证、强制 PKCE、15 分钟访问令牌、7 天刷新令牌和刷新令牌不复用策略。初始化成功后清除开关：

```powershell
Remove-Item Env:OCEAN_BOOTSTRAP_OAUTH_CLIENT_ENABLED
```

## 刷新令牌数据库状态机

V4 将刷新令牌状态从会话表拆到 `iam_refresh_token`。数据库是会话与令牌族状态的唯一权威源；IAM 表只保存带 `sha256:` 前缀的令牌摘要，不保存令牌原文。当前状态转换为 `ISSUED → USED`（正常轮换）和 `ISSUED → REVOKED`（注销或过期）；再次提交已消费令牌会撤销整个 token family。

SAS 官方 JDBC 授权服务已由该状态机装饰：授权码换取令牌时建立会话，刷新时轮换，RFC 7009 撤销时注销会话。公共浏览器客户端仅在 `client_authentication_method=none`、强制 PKCE 且显式允许 `refresh_token` 时启用受控扩展。

轮换在单个数据库事务中完成，并通过会话/令牌行锁、乐观版本号和“每会话最多一枚 `ISSUED` 令牌”的唯一索引处理并发。受管理客户端的访问令牌携带 `sid`，资源服务器据此检查活跃会话，使注销同时令现有访问令牌失效。Redis 仅缓存不含令牌原文的活跃会话投影：数据库事务提交后才回填或删除，读取未命中/连接失败时回源 PostgreSQL，缓存写入失败不回滚业务；TTL 上限默认 5 分钟。

详细说明见：

- `docs/ARCHITECTURE 架构说明.md`
- `docs/DATABASE 数据库说明.md`

## IAM Web API

首批人工验证接口使用 Spring Web 暴露，DAO 使用 MyBatis-Plus 访问 PostgreSQL；实体只映射用户非敏感字段，不读取或返回 `password_hash`。调用 `/api/v1/**` 时需要携带本授权服务器签发的 Bearer access token。

| 方法 | 路径 | 用途 | 所需权限 |
| --- | --- | --- | --- |
| `POST` | `/api/user/login` | 第一方用户登录；`platformCode` 可选 | 匿名 |
| `POST` | `/api/user/platform-login` | 指定平台登录；强制校验 `platformCode` 与客户端平台绑定 | 匿名 |
| `POST` | `/api/user/logout` | 注销当前会话及刷新令牌族 | 已登录 |
| `GET` | `/api/v1/me` | 当前用户、平台、角色和权限 | 已登录 |
| `GET` | `/api/v1/users` | 分页查询用户，支持 `page`、`size`、`keyword`、`status` | `SUPER_ADMIN` 或 `admin:user:read` |
| `GET` | `/api/v1/users/{userId}` | 用户详情 | `SUPER_ADMIN` 或 `admin:user:read` |
| `GET` | `/api/v1/roles` | 角色列表 | `SUPER_ADMIN` 或 `admin:user:read` |
| `GET` | `/api/v1/permissions` | 权限列表 | `SUPER_ADMIN` 或 `admin:user:read` |
| `GET` | `/api/v1/users/{userId}/roles` | 用户角色 | `SUPER_ADMIN` 或 `admin:user:read` |
| `POST` | `/api/v1/users/{userId}/roles/{roleId}` | 授予角色，可提交 `validFrom`、`validUntil` | `SUPER_ADMIN` 或 `admin:user:write` |
| `DELETE` | `/api/v1/users/{userId}/roles/{roleId}` | 撤销角色 | `SUPER_ADMIN` 或 `admin:user:write` |
| `GET` | `/api/v1/me/sessions` | 当前用户活跃会话 | 已登录 |
| `DELETE` | `/api/v1/me/sessions/{sessionId}` | 注销指定会话 | 已登录且会话属于本人 |
| `DELETE` | `/api/v1/me/sessions` | 注销本人全部会话 | 已登录 |

`/api/user/login` 和 `/api/user/platform-login` 都复用 Spring Security 用户认证、SAS 令牌生成器、官方 JDBC 授权存储及 V4 状态机；它们不是另一套简化令牌。前者适合本管理前端直接登录，后者用于调用方必须明确声明平台的场景。当前第一方客户端由 `ocean.security.first-party-client-id` 配置，默认是 `ocean-admin-web`。

Postman 登录示例：

```http
POST http://localhost:8090/api/user/login
Content-Type: application/json

{
  "username": "admin",
  "password": "your-password",
  "platformCode": "OCEAN_ADMIN",
  "deviceId": "postman-local",
  "deviceName": "Postman"
}
```

成功响应包含 `accessToken`、`refreshToken`、`expiresInSeconds`、`sessionId`、`roles` 和 `permissions`。调用受保护接口或注销时携带访问令牌：

```http
POST http://localhost:8090/api/user/logout
Authorization: Bearer <accessToken>
```

OpenAPI 文档位于 `/v3/api-docs`，Swagger UI 位于 `/swagger-ui.html`。既可使用以上登录 API 直接取得令牌，也可通过 Authorization Code + PKCE 获取令牌，再调用管理接口验证完整链路；角色变更只影响之后签发或刷新的 JWT，注销会通过数据库状态机立即使对应现有 access token 失效。
