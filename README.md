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

运行环境必须提供数据库密码和持久化的 OAuth2 签名密钥。以下命令只用于生成本地开发密钥，生产环境应由 KMS、HSM 或受控密钥库提供：

```powershell
keytool -genkeypair -alias ocean-admin -keyalg RSA -keysize 3072 -storetype PKCS12 -keystore ocean-admin.p12 -validity 3650
```

启动应用：

```powershell
$env:DB_URL='jdbc:postgresql://localhost:5432/ocean_admin'
$env:DB_USERNAME='ocean_admin'
$env:DB_PASSWORD='replace-me'
$env:OAUTH2_ISSUER='http://localhost:8080'
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
```

种子脚本不会创建默认管理员和默认密码。首个管理员应通过受控初始化命令创建。

详细说明见：

- `docs/ARCHITECTURE 架构说明.md`
- `docs/DATABASE 数据库说明.md`
