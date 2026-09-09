# 本地 PostgreSQL 与 Redis 容器

## 配置对应关系

| 服务 | 镜像 | 容器名 | 本地端口 | 数据/认证 |
| --- | --- | --- | --- | --- |
| PostgreSQL | `postgres:16-alpine` | `ocean-admin-postgres` | `5432` | 数据库、用户和密码均为 `ocean_admin` |
| Redis | `redis:7-alpine` | `ocean-admin-redis` | `6379` | 密码为 `123456`，默认 DB 为 `0` |

数据分别保存在 Docker 命名卷 `ocean-admin-postgres-data` 和 `ocean-admin-redis-data` 中，重建容器时不会随容器删除。

## 创建并启动

以下 PowerShell 命令仅使用本机已有镜像，不执行镜像拉取：

```powershell
docker run -d `
  --name ocean-admin-postgres `
  --restart unless-stopped `
  -e POSTGRES_DB=ocean_admin `
  -e POSTGRES_USER=ocean_admin `
  -e POSTGRES_PASSWORD=ocean_admin `
  -p 5432:5432 `
  -v ocean-admin-postgres-data:/var/lib/postgresql/data `
  --health-cmd="pg_isready -U ocean_admin -d ocean_admin" `
  --health-interval=5s `
  --health-timeout=3s `
  --health-retries=10 `
  postgres:16-alpine

docker run -d `
  --name ocean-admin-redis `
  --restart unless-stopped `
  -p 6379:6379 `
  -v ocean-admin-redis-data:/data `
  --health-cmd="redis-cli -a 123456 ping" `
  --health-interval=5s `
  --health-timeout=3s `
  --health-retries=10 `
  redis:7-alpine `
  redis-server --requirepass 123456 --appendonly yes
```

## 验证

```powershell
docker ps --filter "name=ocean-admin-" --format "table {{.Names}}\t{{.Image}}\t{{.Status}}\t{{.Ports}}"
docker exec ocean-admin-postgres pg_isready -U ocean_admin -d ocean_admin
docker exec ocean-admin-postgres psql -U ocean_admin -d ocean_admin -c "SELECT current_database(), current_user;"
docker exec ocean-admin-redis redis-cli -a 123456 ping
```

Redis 验证命令可能输出一条在命令行传递密码的安全提示，本地开发环境中可忽略；返回 `PONG` 即表示连接成功。

## 日常启停

```powershell
docker stop ocean-admin-postgres ocean-admin-redis
docker start ocean-admin-postgres ocean-admin-redis
docker logs -f ocean-admin-postgres
docker logs -f ocean-admin-redis
```

## 应用连接配置

仓库默认值已经可以直接连接上述容器。需要显式设置时：

```powershell
$env:DB_URL='jdbc:postgresql://localhost:5432/ocean_admin'
$env:DB_USERNAME='ocean_admin'
$env:DB_PASSWORD='ocean_admin'
$env:REDIS_HOST='localhost'
$env:REDIS_PORT='6379'
$env:REDIS_PASSWORD='123456'
$env:REDIS_DB='0'
```

## 清理

仅删除容器、保留数据卷：

```powershell
docker rm -f ocean-admin-postgres ocean-admin-redis
```

确认不再需要本地数据后，才删除数据卷：

```powershell
docker volume rm ocean-admin-postgres-data ocean-admin-redis-data
```
