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
- PostGIS （GIS 空间元数据与范围索引）
- Knife4j （接口文档）

## 模块

```text
ocean-kernel          稳定的公共common技术契约
ocean-platform-core   identity、audit、workbench
ocean-bootstrap       应用启动、配置和 Flyway
ocean-business-geo    GIS 数据管理、空间元数据提取、处理与发布
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

## GIS 空间元数据与范围查询

原始文件传输完成后，导入任务仍处于 `RUNNING`，阶段为 `EXTRACTING_METADATA`；空间元数据与导入结果在同一事务提交后才计入导入成功。批次内可继续上传下一文件，无需等待前一文件提取完成。

首期提取 GeoTIFF（`.tif/.tiff`）与 RFC 7946 GeoJSON（`.geojson/.json`）。GeoJSON 必须使用 WGS84 经度、纬度顺序；不接受旧版 `crs` 声明。配套附件标记 `NOT_APPLICABLE`，其他主格式返回 `UNSUPPORTED_FORMAT`，原文件保留。

文件分页接口 `GET /api/gis/filemeta/page` 默认仅返回 `metadataStatus=COMPLETED` 的可用数据。可显式传入 `metadataStatus=QUEUED/RUNNING/FAILED/NOT_APPLICABLE` 查看其他状态。回收站 `GET /api/gis/filemeta/deleted/page` 支持同样的筛选参数。

```text
GET /api/gis/filemeta/page?west=110&north=30&east=115&south=25&spatialRelation=INTERSECTS
GET /api/gis/filemeta/page?west=110&north=30&east=115&south=25&spatialRelation=COVERED_BY&requiredZoom=12&tileMatrixSetId=WebMercatorQuad
```

四个框选坐标必须同时提供；`west > east` 表示跨日期变更线。`INTERSECTS` 包含部分相交及边界接触，`COVERED_BY` 表示文件范围完全位于框内（含边界）。查询按文件去重，空间与层级条件必须由同一图层满足。

分页和详情返回提取状态、错误码及 `spatialReferences`。列表默认省略完整范围几何，`includeGeometry=true` 可返回 EPSG:4326 WKT；详情默认返回完整范围。GeoTIFF 范围沿每条边采样后转换并切分跨经线几何；GeoJSON 使用坐标包围范围，不保证框内存在实际要素，栅格范围也不扣除 NoData 区域。

`recommendedMaxZoom` 按文件所属数据集的默认处理类型估算，并限制为 0～22。影像使用 `WebMercatorQuad`、256×256 像素口径，以 3×3 位置采样的 EPSG:3857 像素间距最大值估算；向下取整前对距整数层级不超过 1e-7 的浮点误差归整，避免原生第 14 层被误判为第 13 层。地形使用 `WorldCRS84Quad`，按当前固定版本 mago-3d-terrainer 的源 X 像素间距及末级细分规则计算默认可用深度。查询 `requiredZoom` 时须同时选择对应的 `tileMatrixSetId`，两种矩阵不混比。矢量或无法估算的文件该值为空；未知层级不命中 `requiredZoom`。该值不代表显式修改处理参数后或多文件组合生成的成果实际层级。

`POST /api/gis/filemeta/{id}/metadata/retry` 重新提交提取任务；重新提取期间旧空间记录退出检索。任务使用数据库待办、版本校验和 15 分钟租约，服务重启后自动恢复。扫描间隔由 `gis.metadata.poll-interval` 配置，默认 5000 毫秒；使用独立的两个执行线程。

数据库须安装 PostGIS；部署镜像使用 `postgis/postgis:16-3.5-alpine`。业务表与索引统一维护在 `db/migration/V2__init_ocean_gis.sql`。已有开发库需先补齐最终结构，再依次执行 `mvn flyway:repair`、`mvn flyway:validate`；repair 本身不会修改业务表结构。

