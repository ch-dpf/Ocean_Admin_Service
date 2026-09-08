# Ocean Admin Service 架构说明

## 技术基线

项目基线为 Java 21、Spring Boot 4.1.1、Spring Security Authorization Server 7.1.1、MyBatis-Plus 3.5.17 和 springdoc-openapi 3.0.3。依赖版本由 Spring Boot BOM 与 MyBatis-Plus BOM 统一管理，并通过 Maven Enforcer 校验 Java 版本和依赖收敛。

## 当前边界

当前只建设三个平台侧领域：

1. `identity`：用户、平台、角色、权限、认证和会话。
2. `audit`：登录、操作、安全和异常审计。
3. `workbench`：通用平台指标与业务卡片聚合。

三个领域位于同一个 `ocean-platform-core` Maven 模块，使用包边界隔离，避免初期过度拆分。

## 未来业务子平台

新的业务子平台添加为 `ocean-business-<code>` Maven 模块，例如：

```text
ocean-business-vision
ocean-business-analyst
ocean-business-cloud
```

业务模块只能通过 `org.ocean.admin.platform.api` 使用平台能力。禁止直接访问平台模块的实体、Mapper 和数据表。

扩展点：

- `CurrentUserAccessor`：读取当前用户与平台上下文。
- `AuditPublisher`：提交经过脱敏的业务审计事实。
- `WorkbenchContributor`：向统一工作台贡献平台卡片。

## 数据所有权

第一阶段只使用 `ocean_platform` Schema。平台核心表采用 `iam_`、`audit_`、`wb_` 前缀。

业务模块应使用自己的表前缀；只有出现独立团队、发布周期、数据库权限、备份或伸缩需求时，才拆分 Schema 或服务。

## 安全约束

- JWT 必须使用非对称密钥并校验 `iss`、`aud`、`exp` 和 `nbf`。
- 开发环境在进程启动时动态生成 RSA KeyPair；测试环境从外部 KeyStore 加载固定密钥；生产环境只允许接入 KMS/HSM Provider，不得回退到进程级临时密钥。
- JWT `kid` 使用公钥的 RFC 7638 JWK Thumbprint（SHA-256），不得由部署人员手工指定。
- 首位管理员初始化默认关闭，密码只能来自专用环境变量；初始化必须在事务和数据库锁内完成，且不得覆盖已有账号或密码。
- 首个浏览器 OAuth 客户端通过默认关闭的初始化命令创建；协议客户端、IAM 元数据和回调地址必须同事务写入，已存在但不完全匹配时拒绝覆盖。
- 浏览器客户端使用 Authorization Code + PKCE。
- 活跃会话和短期撤销状态存放 Redis；OAuth 协议状态存放 PostgreSQL，令牌材料必须采用字段级加密或经验证的自定义摘要持久化方案保护。
- 密码、令牌、Cookie、Authorization 和密钥不得写入审计表。
- 平台角色只能授予其所属平台，由数据库触发器和应用服务双重校验。
