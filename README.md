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
- Knife4j （接口文档）

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

在仓库根目录执行 `mvn flyway:info`，或直接点击 IDEA Maven 面板中的 `flyway:info`，可查看迁移状态。Flyway Maven 插件在父项目连接本地 PostgreSQL（`localhost:5432/ocean_admin`，用户名和密码均为 `ocean_admin`），读取 `./db/migration`；子模块跳过该 goal。首次在新环境点击前，先执行 `mvn -pl ocean-bootstrap -am install -DskipTests`，把子模块依赖安装到本地 Maven 仓库。

连接其他数据库时，在 IDEA 的 Maven 运行配置中设置 `FLYWAY_URL`、`FLYWAY_USER`、`FLYWAY_PASSWORD`，或传入 `-Dflyway.url=... -Dflyway.user=... -Dflyway.password=...`。Spring 应用的 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD` 不会自动传给 Maven 插件。

详细说明见：

- `docs/ARCHITECTURE 架构说明.md`
- `docs/DATABASE 数据库说明.md`

