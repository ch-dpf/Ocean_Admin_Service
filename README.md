# Ocean Admin Service

面向多个业务模块的统一Java管理后台，当前平台侧模块仅包含用户认证、审计日志和工作台功能。

## 技术基线

- Java 21
- Spring Boot 4
- Spring WebSocket
- MyBatis-Plus
- JWT（登录鉴权）
- PostgreSQL （数据库服务）
- Flyway （数据库迁移）
- Redis （缓存中间件）
- Knief4j （接口文档）

## 模块

```text
ocean-kernel          稳定的公共common技术契约
ocean-platform-core   identity、audit、workbench
ocean-bootstrap       应用启动、配置和 Flyway
```
未来业务模块命名为 `ocean-business-<platform-code>`，并在父 `pom.xml` 与 `ocean-bootstrap` 中装配。

## 初始化

必须提供数据库密码：
```powershell
$env:DB_URL='jdbc:postgresql://localhost:5432/ocean_admin'
$env:DB_USERNAME='ocean_admin'
$env:DB_PASSWORD='ocean_admin'
mvn -pl ocean-bootstrap -am spring-boot:run
```

Flyway 会执行：
```text
V1__init_ocean_platform.sql
V2__seed_platform_core.sql
```

详细说明见：

- `docs/ARCHITECTURE 架构说明.md`
- `docs/DATABASE 数据库说明.md`

