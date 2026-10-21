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

原始文件传输完成后，导入任务仍处于 `RUNNING`，阶段为 `EXTRACTING_METADATA`。普通文件在元数据提交后计入导入成功；矢量数据集中的 GeoJSON 还会自动进入 `IMPORTING_VECTOR`，图层 READY 后才计入成功。批次内可继续上传下一文件，无需等待前一文件处理完成。

首期提取 GeoTIFF（`.tif/.tiff`）与 GeoJSON（`.geojson/.json`）。GeoJSON 必须使用 WGS84 经度、纬度顺序；默认按 RFC 7946 读取，文件顶层也接受下文列出的旧式 WGS84 命名 `crs` 声明。配套附件标记 `NOT_APPLICABLE`，其他主格式返回 `UNSUPPORTED_FORMAT`，原文件保留。

文件分页接口 `GET /api/gis/filemeta/page` 默认返回所有未删除文件，可显式传入 `metadataStatus=QUEUED/RUNNING/COMPLETED/FAILED/NOT_APPLICABLE` 按元数据状态筛选。回收站 `GET /api/gis/filemeta/deleted/page` 支持同样的筛选参数。

```text
GET /api/gis/filemeta/page?west=110&north=30&east=115&south=25&spatialRelation=INTERSECTS
GET /api/gis/filemeta/page?west=110&north=30&east=115&south=25&spatialRelation=COVERED_BY&requiredZoom=12&tileMatrixSetId=WebMercatorQuad
```

四个框选坐标必须同时提供；`west > east` 表示跨日期变更线。`INTERSECTS` 包含部分相交及边界接触，`COVERED_BY` 表示文件范围完全位于框内（含边界）。查询按文件去重，空间与层级条件必须由同一图层满足。

分页和详情返回提取状态、错误码及 `spatialReferences`。列表默认省略完整范围几何，`includeGeometry=true` 可返回 EPSG:4326 WKT；详情默认返回完整范围。GeoTIFF 范围沿每条边采样后转换并切分跨经线几何；GeoJSON 使用坐标包围范围，不保证框内存在实际要素，栅格范围也不扣除 NoData 区域。

`recommendedMaxZoom` 按文件所属数据集的默认处理类型估算，并限制为 0～22。影像使用 `WebMercatorQuad`、256×256 像素口径，以 3×3 位置采样的 EPSG:3857 像素间距最大值估算；向下取整前对距整数层级不超过 1e-7 的浮点误差归整，避免原生第 14 层被误判为第 13 层。地形使用 `WorldCRS84Quad`，按当前固定版本 mago-3d-terrainer 的源 X 像素间距及末级细分规则计算默认可用深度。查询 `requiredZoom` 时须同时选择对应的 `tileMatrixSetId`，两种矩阵不混比。矢量或无法估算的文件该值为空；未知层级不命中 `requiredZoom`。该值不代表显式修改处理参数后或多文件组合生成的成果实际层级。

`POST /api/gis/filemeta/{id}/metadata/retry` 重新提交提取任务；重新提取期间旧空间记录退出检索。任务使用数据库待办、版本校验和 15 分钟租约，服务重启后自动恢复。扫描间隔由 `gis.metadata.poll-interval` 配置，默认 5000 毫秒；使用独立的两个执行线程。

数据库须安装 PostGIS；部署镜像使用 `postgis/postgis:16-3.5-alpine`。业务表与索引统一维护在 `db/migration/V2__init_ocean_gis.sql`。已有开发库需先补齐最终结构，再依次执行 `mvn flyway:repair`、`mvn flyway:validate`；repair 本身不会修改业务表结构。

## 可编辑矢量图层与要素

向矢量数据集（`categoryId=2`）调用现有 `POST /api/gis/uploads/chunks` 上传 `.geojson/.json`，空间元数据完成后会自动生成可编辑图层，无需从已管理文件再次提交导入。默认每个文件一个 SINGLE 图层，名称取原文件名去扩展名并截取前100个字符。

原上传批次持续展示 `UPLOADING → EXTRACTING_METADATA → IMPORTING_VECTOR → COMPLETED/FAILED/PARTIAL_FAILED`；多个文件可并行提取和入库，批次阶段反映当前尚未完成的工作。仅图层 READY 后，该文件的 `importCompleted` 才为 true、批次成功数量才增加。`fileCompleted=true` 仅表示文件已完整上传。

通过 `GET /api/gis/file-transfer-records/{taskId}` 查询原上传批次；`files[]` 中的 `vectorTaskId`、`vectorImportStatus`、`vectorLayerIds` 提供矢量任务及图层结果，图层 ID 为字符串数组，供后续扩展多图层。上传或重复分块响应也包含这些字段以及 `fileMetaId`；自动任务尚未创建时任务号为空、图层数组为空。自动入库共用原批次的 Redis/WebSocket 进度，不另创建一条实时任务。

`POST /api/gis/file-transfer-records/{taskId}/retry` 重试该批次中已完整上传的失败文件：元数据失败重新提取，要素入库失败复用原矢量任务和图层；成功文件及其编辑成果不重新导入。传输失败的文件需重新上传。矢量任务排队、执行或失败待重试时，元数据重试接口拒绝使来源快照失效，应通过原批次重试。任务中断后由数据库待办和租约自动恢复。

对已管理文件另行创建图层时，也可通过 `POST /api/gis/vector/layers/import` 显式提交。文件须属于矢量数据集，且为 `LOCAL`、`uploadStatus=READY`、`metadataStatus=COMPLETED`。

```json
{"fileMetaId":"文件ID","layerName":"海岛基础要素","requestId":"客户端唯一标识","splitMode":"SINGLE"}
```

接口返回 HTTP 202，`data.taskId` 为稳定任务号，`data.layers` 为图层数组。首期每次导入生成一个 `layerKey=default` 的图层，所有 Feature 进入该图层；MultiPolygon、GeometryCollection 不拆成多条要素。默认 `splitMode=SINGLE`，其他模式返回400。来源文件、导入任务与图层的关系允许后续扩展多个图层。

同一 requestId 和相同参数复用原任务，不同参数返回409。通过 `GET /api/gis/vector/imports/{taskId}` 查询 `QUEUED/RUNNING/COMPLETED/FAILED`、已写入数量、失败要素序号及图层结果；失败任务可调用 `POST /api/gis/vector/imports/{taskId}/retry`。失败要素序号从0开始。导入采用流式解析、分批写入和数据库租约；任务中断后自动重新领取，清理未完成结果后从头执行。整次导入成功后图层才变为 READY；失败会清理已写入要素。

```text
GET    /api/gis/vector/layers?dataSetId=...&current=1&size=10
GET    /api/gis/vector/layers/{layerId}
DELETE /api/gis/vector/layers/{layerId}
GET    /api/gis/vector/layers/{layerId}/features?bbox=110,20,115,25&limit=200
GET    /api/gis/vector/layers/{layerId}/features/{featureId}
POST   /api/gis/vector/layers/{layerId}/features
PUT    /api/gis/vector/layers/{layerId}/features/{featureId}
DELETE /api/gis/vector/layers/{layerId}/features/{featureId}
```

管理接口沿用 ResponseResult；空间查询、要素详情及新增修改响应直接返回 `application/geo+json`。
要素新增、修改请求为完整 GeoJSON Feature，例如：

```json
{"type":"Feature","geometry":{"type":"Point","coordinates":[110,20,50]},"properties":{"name":"观测点","level":2}}
```

系统分配稳定字符串ID，保留属性 JSON 类型及 XY/XYZ 坐标；properties 可以为对象或null，geometry 必须非空有效。编辑须保留原要素的坐标维度。在线新增、修改及对外输出使用 RFC 7946 经度/纬度坐标，新增修改请求仍拒绝 crs。空几何、混合维度和未切分的跨日期变更线线段会被拒绝。几何拓扑由 PostGIS 校验，不自动修复。单要素最多100000个顶点，几何与属性合计不超过4MiB；HTTP新增修改请求体也不得超过4MiB。来源文件不会随编辑回写。

文件导入长期兼容顶层旧式 WGS84 命名 CRS，格式为 `{"crs":{"type":"name","properties":{"name":"..."}}}`。允许的 name 为 `CRS84`、`OGC:CRS84`、`urn:ogc:def:crs:OGC:1.3:CRS84`、`urn:ogc:def:crs:OGC::CRS84`、`EPSG:4326`、`urn:ogc:def:crs:EPSG::4326`。crs 可以出现在 features 之前或之后；仅校验声明并保持原坐标，不交换经纬度、不执行坐标转换。空间元数据与要素导入共用校验，原声明保存在空间元数据的 `extraMetadata.declaredCrs`，内部按 OGC:CRS84/SRID4326 处理，原文件不变。

`crs:null`、链接型声明、未知或其他坐标系（如 EPSG:3857）以及 FeatureCollection 内的子级声明均不支持；元数据提取阶段返回 UNSUPPORTED_CRS。顶层 Feature、Geometry 或 GeometryCollection 文件同样支持上述声明，子级声明一律拒绝。仅在业务 properties 内名为 crs 的普通属性不受影响。没有 crs 的标准文件继续按原规则导入；此前因受支持声明被拒绝的上传文件可重试原导入批次，元数据提取成功后自动继续要素入库。

要素详情及新增修改响应提供 ETag。PUT、DELETE 要素时必须把该值原样放入 `If-Match`；删除图层使用图层详情的 ETag。缺失返回428，版本过期返回412。编辑在同一事务内更新要素版本、图层数据版本、数量及范围；图层范围与原文件的空间元数据独立维护。被活动图层或待执行导入引用的来源文件，不能删除或变更来源信息；删除图层会解除其来源引用。

范围查询使用真实要素 `ST_Intersects`，`bbox=west,south,east,north`，west>east 表示跨日期变更线。limit 为1至1000，默认200；每页另有约8MiB载荷预算。响应包含 hasMore、nextCursor、dataVersion；后续页保留相同bbox并传cursor。图层编辑后旧cursor返回409，客户端应重新加载第一页。省略bbox可用于有界的属性列表遍历。

后台默认每5秒扫描，使用独立的两个矢量执行线程；可通过 `gis.vector.poll-interval` 配置扫描间隔。持久化任务为权威状态，Redis/WebSocket仅用于实时进度。

