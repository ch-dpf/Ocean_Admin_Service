<!-- generated-by: gsd-doc-writer -->
# Ocean Admin Service 部署

本目录提供三种部署方式：Linux 裸机、Windows 裸机和 Docker Compose。三种方式均从仓库根目录构建 `ocean-bootstrap` 可执行 JAR，并使用同一份 `deploy/application.env` 配置。

## 目录

```text
deploy/
├── application.env.example      配置模板
├── linux/deploy.sh              Linux 裸机部署脚本
├── windows/deploy.ps1           Windows 裸机部署脚本
└── docker/
    ├── Dockerfile               应用镜像
    ├── Dockerfile.dockerignore  Docker 构建上下文过滤规则
    └── compose.yml              应用、PostgreSQL、Redis 编排
```

## 部署前准备

1. 克隆仓库后执行 `git lfs pull`，确保 `ocean-business-geo/tools/mago/mago-3d-terrainer-1.14.2-release.jar` 不是 Git LFS 指针文件。
2. 复制配置模板并修改所有 `change-me`：

```bash
cp deploy/application.env.example deploy/application.env
```

PowerShell：

```powershell
Copy-Item deploy/application.env.example deploy/application.env
```

`deploy/application.env` 包含密码，不应提交到版本库；仓库的 `.gitignore` 已忽略 `.env` 文件。

## 配置说明

| 变量 | 默认示例                                           | 说明 |
| --- |------------------------------------------------| --- |
| `APP_PORT` | `8090`                                         | 对外端口；裸机脚本将其映射为 Spring 服务端口，Docker 将其映射为宿主机端口 |
| `JAVA_TOOL_OPTIONS` | 空                                              | 应用 JVM 参数，例如堆大小；Mago 子进程使用独立配置 |
| `POSTGRES_DB` | `ocean_admin`                                  | Docker PostgreSQL 数据库名 |
| `POSTGRES_BIND_ADDRESS` | `0.0.0.0`                                      | Docker PostgreSQL 宿主机绑定地址；|
| `POSTGRES_PORT` | `5432`                                         | Docker PostgreSQL 宿主机端口 |
| `DB_URL` | `jdbc:postgresql://127.0.0.1:5432/ocean_admin` | 裸机模式数据库连接地址；Docker 模式自动改为容器地址 |
| `DB_USERNAME` | `ocean_admin`                                  | PostgreSQL 用户名 |
| `DB_PASSWORD` | 无安全默认值                                         | PostgreSQL 密码，必须修改 |
| `REDIS_HOST` | `127.0.0.1`                                    | 裸机模式 Redis 地址；Docker 模式自动改为容器地址 |
| `REDIS_BIND_ADDRESS` | `0.0.0.0`                                      | Docker Redis 宿主机绑定地址；|
| `REDIS_PORT` | `6379`                                         | 裸机 Redis 端口及 Docker Redis 宿主机端口 |
| `REDIS_PASSWORD` | 无安全默认值                                         | Redis 密码，必须修改 |
| `REDIS_DB` | `0`                                            | Redis 数据库编号 |
| `FLYWAY_ENABLED` | `true`                                         | 启动时执行并校验 `db/migration` 迁移 |
| `GIS_*_PATH` | 仓库内相对路径                                        | 上传、处理、工作区和影像缓存目录 |
| `GIS_PUBLIC_BASE_URL` | `http://127.0.0.1:8090`                        | 对浏览器可访问的服务地址；反向代理部署时改为实际域名 |
| `GIS_TERRAIN_MAGO_ENABLED` | `true`                                         | 是否启用 Mago 地形引擎 |
| `GIS_TERRAIN_MAGO_JAR_PATH` | 空                                              | 裸机脚本自动使用仓库内 JAR，容器模式自动使用镜像内路径 |
| `GIS_TERRAIN_MAGO_MAX_HEAP` | `32g`                                          | Mago 子进程最大堆，来自当前应用默认配置 |

所有相对存储路径均以仓库根目录（裸机）或 `/app`（Docker）为基准。生产环境应根据数据规模将 GIS 数据目录和数据库卷纳入备份。

## Linux 裸机部署

前置条件：Java 21、Maven、PostgreSQL 16 和 Redis 7。PostgreSQL 与 Redis 需已启动，并与 `deploy/application.env` 一致。

```bash
chmod +x deploy/linux/deploy.sh
./deploy/linux/deploy.sh build
./deploy/linux/deploy.sh start
./deploy/linux/deploy.sh status
./deploy/linux/deploy.sh logs
```

停止或重启：

```bash
./deploy/linux/deploy.sh stop
./deploy/linux/deploy.sh restart
```

应用 PID 和控制台日志保存在仓库根目录的 `logs/` 下；应用业务日志为 `logs/ocean.log`。

## Windows 裸机部署

前置条件：Java 21、Maven、PostgreSQL 16 和 Redis 7。使用 PowerShell 7 或 Windows PowerShell 5.1 执行：

```powershell
.\deploy\windows\deploy.ps1 build
.\deploy\windows\deploy.ps1 start
.\deploy\windows\deploy.ps1 status
.\deploy\windows\deploy.ps1 logs
```

停止或重启：

```powershell
.\deploy\windows\deploy.ps1 stop
.\deploy\windows\deploy.ps1 restart
```

脚本只停止由自身 PID 文件识别且命令行包含当前应用 JAR 的 Java 进程，避免误停其他 Java 服务。

## Docker Compose 部署

前置条件：Docker Engine 及 Docker Compose v2。应用镜像使用 Java 21 多阶段构建，Compose 同时启动 PostgreSQL 16 与 Redis 7。

```bash
docker compose `
  --env-file deploy/application.env `
  -f deploy/docker/compose.yml `
  up -d --build
```

查看状态与日志：

```bash
docker compose --env-file deploy/application.env -f deploy/docker/compose.yml ps
docker compose --env-file deploy/application.env -f deploy/docker/compose.yml logs -f app
```

停止服务但保留数据：

```bash
docker compose --env-file deploy/application.env -f deploy/docker/compose.yml down
```

删除服务及全部命名卷会永久删除数据库、Redis 和 GIS 数据，只有确认不再需要数据时才执行：

```bash
docker compose --env-file deploy/application.env -f deploy/docker/compose.yml down -v
```

## 验证

启动日志出现 `Started OceanAdminApplication` 后，验证端口与接口文档：

```bash
curl -I http://127.0.0.1:8090/doc.html
```

同时检查 PostgreSQL 与 Redis 无连接错误、Flyway 迁移成功，并执行一次登录及 GIS 文件读写验证。Docker 默认将 PostgreSQL 和 Redis 分别发布到宿主机 `127.0.0.1:5432` 和 `127.0.0.1:6379`，仅供本机管理工具访问；对外防火墙只需开放实际使用的 `APP_PORT`。

## 更新与回滚

裸机部署先停止服务，保留上一版 JAR，再拉取目标版本、重新执行 `build` 和 `start`。回滚时恢复上一版 JAR，并确认其数据库结构与当前迁移兼容后启动。

Docker 部署应为发布镜像使用唯一标签。更新前备份 PostgreSQL 和 GIS 数据卷；回滚时恢复上一镜像标签。数据库迁移脚本没有自动降级逻辑，涉及结构变化时必须准备经过验证的数据恢复方案。

## 运行维护

仓库未提供 CI/CD 发布流水线或独立监控平台配置。部署后至少应采集应用日志、进程或容器状态、磁盘可用空间、PostgreSQL 与 Redis 健康状态，并针对 `logs/ocean.log` 中的启动失败、数据库连接失败和 GIS 处理异常设置告警。

