# Ocean Admin Service

面向多个业务模块的统一Java管理后台，当前平台侧模块仅包含用户认证、审计日志和工作台功能。

## 技术基线

- Java 21
- Spring Boot 4
- Spring MVC SSE
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

原始文件传输完成后，导入批次仍处于 `RUNNING`，阶段为 `EXTRACTING_METADATA`。普通文件提取空间元数据；矢量数据集中的 GeoJSON 在同一文件提取阶段继续生成图层与要素，图层 READY 后该文件才计入成功。批次内可继续上传下一文件，无需等待前一文件处理完成。

首期提取 GeoTIFF（`.tif/.tiff`）与 GeoJSON（`.geojson/.json`）。GeoJSON 必须使用 WGS84 经度、纬度顺序；默认按 RFC 7946 读取，文件顶层也接受下文列出的旧式 WGS84 命名 `crs` 声明。配套附件标记 `NOT_APPLICABLE`，其他主格式返回 `UNSUPPORTED_FORMAT`，原文件保留。

文件分页接口 `GET /api/gis/filemeta/page` 返回当前管理文件，可显式传入 `metadataStatus=QUEUED/RUNNING/COMPLETED/FAILED/NOT_APPLICABLE` 按元数据状态筛选。`DELETE /api/gis/filemeta/{id}` 删除文件元数据；上传来源同时删除托管物理文件，工作区来源保留原文件。活动处理任务引用时返回冲突，调用方必须进行二次确认。

```text
GET /api/gis/filemeta/page?west=110&north=30&east=115&south=25&spatialRelation=INTERSECTS
GET /api/gis/filemeta/page?west=110&north=30&east=115&south=25&spatialRelation=COVERED_BY&requiredZoom=12&tileMatrixSetId=WebMercatorQuad
```

四个框选坐标必须同时提供；`west > east` 表示跨日期变更线。`INTERSECTS` 包含部分相交及边界接触，`COVERED_BY` 表示文件范围完全位于框内（含边界）。查询按文件去重，空间与层级条件必须由同一图层满足。

分页和详情返回提取状态、错误码及 `spatialReferences`。列表默认省略完整范围几何，`includeGeometry=true` 可返回 EPSG:4326 WKT；详情默认返回完整范围。GeoTIFF 范围沿每条边采样后转换并切分跨经线几何；GeoJSON 使用坐标包围范围，不保证框内存在实际要素，栅格范围也不扣除 NoData 区域。

`recommendedMaxZoom` 按文件所属数据集的默认处理类型估算，并限制为 0～22。影像使用 `WebMercatorQuad`、256×256 像素口径，以 3×3 位置采样的 EPSG:3857 像素间距最大值估算；向下取整前对距整数层级不超过 1e-7 的浮点误差归整，避免原生第 14 层被误判为第 13 层。地形使用 `WorldCRS84Quad`，按当前固定版本 mago-3d-terrainer 的源 X 像素间距及末级细分规则计算默认可用深度。查询 `requiredZoom` 时须同时选择对应的 `tileMatrixSetId`，两种矩阵不混比。矢量或无法估算的文件该值为空；未知层级不命中 `requiredZoom`。该值不代表显式修改处理参数后或多文件组合生成的成果实际层级。

`POST /api/gis/filemeta/{id}/metadata/retry` 仅重新提交提取失败的文件；已成功生成且可能被编辑的矢量图层不会被覆盖。提取使用数据库待办、版本校验和 15 分钟租约，服务重启后自动恢复。扫描间隔由 `gis.metadata.poll-interval` 配置，默认 5000 毫秒；使用独立的两个执行线程。

数据库须安装 PostGIS；部署镜像使用 `postgis/postgis:16-3.5-alpine`。业务表与索引统一维护在 `db/migration/V2__init_ocean_gis.sql`。已有开发库需先补齐最终结构，再依次执行 `mvn flyway:repair`、`mvn flyway:validate`；repair 本身不会修改业务表结构。

## 从受控服务器工作区接入数据

`POST /api/gis/filemeta/workspace-files?dataSetId={id}` 原位接入服务器文件，必填查询参数 `dataSetId` 指定所属数据集。不复制、不移动源文件，也不创建文件传输记录或切片处理任务。工作区沿用 `gis.processing.workspaces` 配置。接口只校验入口，立即以HTTP 202返回 `dataSetId` 和统一 `progress` 引用（`progressId`、`type=GIS_WORKSPACE_IMPORT`、`status=QUEUED`、`queryUrl`、`eventsUrl`），目录遍历、登记及空间提取进度均在后台推进。

```json
{"workspaceCode":"DEFAULT","relativePath":"survey/2026","includeSubdirectories":true,"vectorPlacement":"AUTO"}
```

服务端自动识别文件或目录。文件必须符合数据集分类支持的空间解析格式，否则直接失败且不登记。影像和地形当前支持 `.tif/.tiff`，矢量支持 `.geojson/.json`；扩展名通过后仍须解析真实内容。`vectorPlacement` 仅用于矢量数据集，可省略，默认AUTO。

目录只登记匹配格式的普通文件；`includeSubdirectories` 默认false，仅扫描当前层，true递归扫描所有层级。不支持的文件跳过，同一数据集内已登记的工作区相对路径按重复跳过；无符合条件文件时任务失败。扫描不跟随链接或Windows目录联接，禁止绝对路径、上级目录和盘符路径。`gis.processing.workspace-max-scan-entries` 默认10000，计入目录和文件条目；后台边扫描边登记，超限明确停止，保留已登记文件，等待这些文件解析结束后报告失败。执行复用现有有界 `taskExecutor`，最多保留100个活动接入任务，繁忙时返回503。

前端用提交响应的 `progress.progressId` 匹配 `/api/gis/events/stream` 消息的 `data.progressId`。`SCANNING` 阶段 `overall.percent=null`，通过 `metrics` 读取扫描条目、登记、重复、不支持及失败计数。扫描结束进入 `EXTRACTING_METADATA`，按确定的解析对象计算整体百分比。只有新登记文件全部进入空间提取终态后才结束；重复和不支持文件记录为 `summary.skipped`，不计入解析百分比分母。

`GET /api/gis/progress/{progressId}` 返回统一完整快照，用于提交竞态、刷新及断线补查。工作区终态保留30分钟，服务重启或过期后返回404（PROGRESS_EXPIRED）。逐文件明细使用文件分页及 `sourceType=UPLOAD/WORKSPACE` 筛选。已入库元数据待办仍按数据库队列恢复；快照恢复不等于恢复目录扫描。

文件模型和查询统一使用 `fileStatus`、`createdBy`，替代原 `uploadStatus`、`uploadedBy`；传输明细的文件快照同步使用新名称。工作区文件返回 `sourceType=WORKSPACE`、`workspaceCode`、`relativePath` 和Unix毫秒单位的 `sourceModifiedAt`，托管存储字段为空。读取时校验源大小和修改时间；发现变化或不可用时空间元数据标记失败。重试会刷新源文件快照，已生成的矢量图层仍按现有规则保护，避免覆盖编辑内容。源文件变化不会主动推送，须在读取或重新接入时验证。

删除工作区文件记录或整个数据集都保留工作区源文件。下载和从已管理文件提交处理任务统一按来源解析路径；实际下载仍按既有规则记录导出传输记录。

### 任务标识的关系

`ProgressSnapshot.progressId` 是字符串进度键：上传使用 `recordNo=upload_...`，数据处理使用 `taskNo`，工作区接入使用 `workspace_...`，归档使用 `archiveNo`。工作区进度通过 `resource` 指向对应数据集，归档的 `summary` 统计一个最终归档文件，打包源文件数放在 `metrics`。数据处理统一 `type=GIS_PROCESSING`，`category=TERRAIN/IMAGERY/VECTOR`。

现有切片处理提交响应中的 `taskId` 是 `gis_processing_task.id`，用于 `GET /api/gis/task/{taskId}`；同一响应中的 `taskNo` 才对应事件消息 `data.progressId`。文件的 `fileMetaId` 是独立的数据身份，一个接入进度可以产生多个文件，这些文件以后可被多个独立的切片处理任务引用。工作区进度编号不能传给处理任务详情接口。

## 统一实时事件与进度协议

平台与GIS的SSE接口分别为 `GET /api/platform/monitor/metrics`、`GET /api/gis/events/stream`（`text/event-stream`），分别投递 `platform.*`、`gis.*` 事件。事件名固定为 `app.event`，data字段承载下面的完整JSON。事件信封与发布契约位于 `ocean-kernel`，该模块不依赖Web；平台SSE控制器与发布器位于 `ocean-platform-core` 的 `org.ocean.admin.platform.workbench` 包，GIS对应实现位于 `ocean-business-geo` 的 `org.ocean.admin.gis.realtime` 包；两侧分别实现发布契约，bootstrap只负责SSE相关的MVC注册和文档装配。消息体由各事件类型定义；新增通知或资源变更事件不需要修改进度模型。

```json
{
  "schemaVersion": 1,
  "messageId": "f42e0af3-56c9-4d32-87ea-789ea5f66284",
  "type": "gis.progress.updated",
  "source": "ocean.gis",
  "subject": "progress/upload_20261027153000_abcdef123456",
  "occurredAt": "2026-10-27T07:30:00Z",
  "correlationId": "client-request-001",
  "data": {
    "progressId": "upload_20261027153000_abcdef123456",
    "correlationId": "client-request-001",
    "type": "GIS_UPLOAD",
    "category": null,
    "title": "GIS文件接入",
    "status": "RUNNING",
    "overall": {"mode": "INDETERMINATE", "percent": null},
    "stage": {"code": "UPLOADING", "message": "正在上传", "completed": 1024, "total": 4096, "unit": "BYTE"},
    "metrics": [
      {"code": "RECEIVED_BYTES", "name": "已接收字节", "value": 1024, "total": 4096, "unit": "BYTE"},
      {"code": "CONFIRMED_BYTES", "name": "确认落盘字节", "value": 0, "total": 4096, "unit": "BYTE"}
    ],
    "summary": {"total": 2, "succeeded": 0, "failed": 0, "skipped": 0, "unit": "FILE"},
    "resource": {"type": "FILE_TRANSFER_RECORD", "id": "upload_20261027153000_abcdef123456", "detailUrl": "/api/gis/file-transfer-records/upload_20261027153000_abcdef123456"},
    "error": null,
    "startedAt": "2026-10-27T15:30:00",
    "finishedAt": null,
    "revision": {"epoch": 1, "sequence": 3}
  }
}
```

进度事件为 `gis.progress.created/updated/finished`，始终携带完整快照。finished 表示已结束，成功与失败由 `data.status` 判断；其他事件可使用各自的强类型消息体。未知事件类型可忽略。messageId 用于消息去重，时间不作为排序依据。

前端按 `progressId` 保存最新快照，再按 `(revision.epoch, revision.sequence)` 比较新旧；HTTP补查也走相同合并规则。持久化流程重试或快照重新建立时原子递增业务表的 `progress_epoch`，新代次可以从 sequence=0 开始，旧执行句柄不能覆盖新代次。普通查询命中内存快照时不修改版本。持久化进度缺失时先核对有效的Redis终态缓存；运行态或缓存缺失时从业务表恢复，只返回能够确认的工作量。补查发现数据库已结束而内存仍在运行时，会修复并补发终态。

上传、工作区接入、数据处理、归档的提交及重试响应提供统一 `progress` 引用；分块响应保留 nextOffset 等传输确认字段。上传记录的字节比例字段为 `transferPercent`，归档记录的字节比例字段为 `packingPercent`，两者均不等于完整业务流程的整体百分比。

数值更新每500毫秒最多推送一次，并补发窗口末尾的最新快照；创建、阶段切换和终态立即推送。Redis使用 `ocean-admin:progress:v1:` 前缀，终态缓存30分钟，运行态6小时无活动后清理。业务状态仍以数据库为准；服务重启后的精细计量可能丢失，重建快照不会恢复执行器。当前工作区运行态和连接路由仍为单实例能力。

本轮按需求暂不调整鉴权，事件按主题广播，主题筛选不代表权限校验。原WebSocket端点与依赖已移除。

### SSE连接、主题与重连

平台与GIS分别维护连接，按需建立，每侧不按任务创建连接。省略topics只订阅当前接口所属事件域；也可传入最多16个逗号分隔的域内主题（总长不超过512字符），跨域或混合域订阅返回400。主题按完整名称或点分隔前缀匹配，例如 `gis.progress` 匹配 `gis.progress.updated`，但不匹配 `gis.progressive.updated`。

GIS提交响应中的 `progress.eventsUrl` 为 `/api/gis/events/stream?topics=gis.progress`。系统指标单独连接 `/api/platform/monitor/metrics?topics=platform.metrics`，事件为 `platform.metrics.updated`，source为 `ocean.platform`，subject为 `system/metrics`，data为SystemMetricsDTO；每2秒采集一次，仅有匹配的平台订阅者时采集。两个接口分别维护连接池、发送线程池、心跳与资源上限，互不投递对方事件；原 `/events/stream` 入口已移除。

```javascript
function subscribeEvents(url, onOpen) {
  const stream = new EventSource(url);
  stream.addEventListener("app.event", event => {
    // 进度事件和HTTP补查共用同一版本合并函数。
    dispatchEventMessage(JSON.parse(event.data));
  });
  stream.onopen = onOpen;
  stream.onerror = () => {
    if (stream.readyState === EventSource.CLOSED) {
      // 连接管理器按URL限次退避重建，并更新所维护的连接引用。
      scheduleReconnectWithBackoff(url, onOpen);
    }
    // CONNECTING时保留浏览器内建重连，避免额外创建重复连接。
  };
  return stream;
}

// GIS每次连接成功后补查关注的任务，按epoch和sequence合并快照。
const gisStream = subscribeEvents(
  "/api/gis/events/stream?topics=gis.progress", refreshWatchedProgress);
const platformStream = subscribeEvents(
  "/api/platform/monitor/metrics?topics=platform.metrics", () => {});

// 应用退出时关闭各侧连接，不影响后台任务继续执行。
// gisStream.close();
// platformStream.close();
```

连接建立后立即刷新注释帧及 `retry:3000`，空闲时约每15秒发送注释心跳。EventSource对网络断开的自动重连不等于对所有HTTP错误都会重试；连接被503拒绝等情况可能进入CLOSED，前端应限次退避重新建立连接，避免无条件在onerror中创建重复连接。连接默认最长30分钟，浏览器在正常断开后重新建立连接；不会恢复或取消后台执行。本期不发送SSE id、不提供事件历史重放，也不依赖Last-Event-ID；重连只恢复后续实时事件，丢失进度通过HTTP补查恢复。其他未来需要逐条不遗漏的业务事件须自行提供持久化查询。

平台与GIS分别使用专用有界线程池；单连接串行，每侧默认最多256条连接、每连接64条待发消息和512KiB待发UTF-8字节（含正在发送项）。数据在MVC异步响应初始化前留在应用有界队列中。发送超过10秒、队列超限或执行器饱和时回收连接并让客户端重连；不会把网络写入回退给业务线程。连接回收也由独立执行器完成，防止完成操作等待发送锁而阻塞业务。达到所属域的连接上限时返回503，不占用另一侧的连接额度。

平台参数位于 `ocean.platform.realtime.sse`，GIS参数位于 `ocean.gis.realtime.sse`，各自包含max-connections、max-pending-messages、max-pending-bytes、send-threads、send-queue-capacity、heartbeat-interval-ms、send-timeout-ms、connection-timeout-ms。环境变量分别使用 `PLATFORM_SSE_*` 与 `GIS_SSE_*` 前缀，例如 `PLATFORM_SSE_MAX_CONNECTIONS`、`GIS_SSE_MAX_CONNECTIONS`。原 `ocean.realtime.sse` 与 `SSE_*` 配置已移除。实际超时回收由每秒维护任务检查，网络写入最终退出还取决于Servlet容器。

部署代理必须保留 `/api/platform/monitor/metrics` 和 `/api/gis/events/stream` 路由并即时转发，关闭响应缓存、缓冲和SSE压缩。响应包含 `Cache-Control: no-store, no-transform` 与 `X-Accel-Buffering: no`；网关空闲超时应大于心跳间隔，浏览器入口推荐HTTP/2。Nginx可参考：

```nginx
location ~ ^/api/(platform/monitor/metrics|gis/events/stream)$ {
    proxy_pass http://127.0.0.1:8090;
    proxy_http_version 1.1;
    proxy_set_header Connection "";
    proxy_buffering off;
    proxy_cache off;
    proxy_read_timeout 60s;
    gzip off;
}
```

## 可编辑矢量图层与要素

向矢量数据集（`categoryId=2`）调用现有 `POST /api/gis/uploads/chunks` 上传 `.geojson/.json`，空间元数据完成后会自动生成可编辑图层，无需从已管理文件再次提交导入。默认每个文件一个 SINGLE 图层，名称取原文件名去扩展名并截取前100个字符。

原上传批次持续展示 `UPLOADING → EXTRACTING_METADATA → COMPLETED/FAILED/PARTIAL_FAILED`；多个文件可并行提取。矢量文件在 `EXTRACTING_METADATA` 阶段内流式写入图层和要素，仅图层 READY 后批次成功数量才增加。

通过 `GET /api/gis/file-transfer-records/{taskId}` 查询原上传批次；`files[]` 中的 `vectorLayerIds` 返回由该原始文件提取的图层 ID。矢量提取共用原批次的 Redis/SSE 进度，不创建独立任务。

`POST /api/gis/file-transfer-records/{taskId}/retry` 重试该批次中已完整上传但提取失败的文件。未完成矢量图层及要素会先清理后重新提取；成功文件及其编辑成果不会重新提取。传输失败的文件需重新上传。

首期每个 GeoJSON 文件生成一个 `layerKey=default` 的图层，所有 Feature 进入该图层；MultiPolygon、GeometryCollection 不拆成多条要素。文件、图层与要素通过 `gis_vector_layer.source_file_id` 和 `gis_vector_feature.layer_id` 直接关联。

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

后台默认每5秒扫描，使用独立的两个矢量执行线程；可通过 `gis.vector.poll-interval` 配置扫描间隔。持久化任务为权威状态，Redis/SSE仅用于实时进度。

## 矢量图层样式方案

SINGLE 导入模式保持不变。同一数据图层可以包含点、线、面，并关联多份样式方案。新建图层在同一事务内生成一份包含面、线、点基础规则的默认方案。样式保存在 `gis_vector_style.definition` JSONB 中，修改样式不改写几何、要素版本或图层 dataVersion，已有要素分页游标仍可继续使用。

前端调用 `GET /api/gis/vector/layers/{layerId}/default-style` 获取默认方案，使用动态 MVT 浏览并本地应用规则；原始 GeoJSON 用于精确查询及编辑。也可从方案列表选择其他方案。后端不把样式混入 Feature.properties，样式层级不改变原始要素。点、线、面可以使用不同渲染层共享同一份数据。三维地图的相机高度或比例尺须由前端换算为双方约定的 zoom，服务端不把地形瓦片层级自动当作样式层级。

```text
GET    /api/gis/vector/layers/{layerId}/styles
POST   /api/gis/vector/layers/{layerId}/styles
GET    /api/gis/vector/layers/{layerId}/styles/{styleId}
PUT    /api/gis/vector/layers/{layerId}/styles/{styleId}
DELETE /api/gis/vector/layers/{layerId}/styles/{styleId}
GET    /api/gis/vector/layers/{layerId}/default-style
PUT    /api/gis/vector/layers/{layerId}/styles/{styleId}/default
POST   /api/gis/vector/layers/{layerId}/styles/{styleId}/match
```

样式接口全部沿用 `{code,message,data}` 包装。列表返回摘要数组，包含 styleId、layerId、name、isDefault、version 和时间；单方案及默认方案详情另含 definition。创建返回 HTTP201，名称在同一图层内唯一、最多100字符；每层最多100份方案。新增方案不自动成为默认方案。

创建、修改的完整请求示例：

```json
{
  "name": "港口分级显示",
  "definition": {
    "schemaVersion": 1,
    "rules": [
      {
        "id": "ports-medium",
        "geometryType": "POINT",
        "minZoom": 8,
        "maxZoom": 12,
        "filter": {"op": "eq", "field": "kind", "value": "port"},
        "symbol": {"color": "#3388ff", "radius": 6}
      },
      {
        "id": "ports-detail",
        "geometryType": "POINT",
        "minZoom": 12,
        "filter": {"op": "eq", "field": "kind", "value": "port"},
        "symbol": {"color": "#ff3333", "radius": 9}
      }
    ]
  }
}
```

该示例中，kind=port 的点在8级以下隐藏，8≤zoom<12显示蓝色，zoom≥12显示红色；其他要素无匹配规则时隐藏。定义最多128KiB、128条规则，空 rules 表示全部隐藏。协议只接受整数 schemaVersion=1，未知字段、运算符或符号字段返回400。规则 id 在方案内唯一，仅允许1至64个字母、数字、下划线或连字符。

规则按数组顺序执行：几何、属性及层级同时满足时使用第一条匹配项。minZoom、maxZoom 可省略或为null；zoom及非空边界须为非负有限数值，可含小数，同时指定两端时须满足 minZoom<maxZoom，区间为 `[minZoom,maxZoom)`。visible 默认为true；visible=false 的匹配规则显式隐藏要素并终止匹配，其 symbol 可省略。

geometryType 使用 POINT、LINE、POLYGON，分别包含对应的单部件及 Multi 几何。GeometryCollection 递归按子几何匹配，不拆成多条数据库要素。匹配结果保留原 featureId，并通过 `/geometry/geometries/0` 等路径定位子几何；Multi 几何作为整体匹配。

filter 省略或为null时匹配全部属性。叶子条件为 `{"op":"eq","field":"kind","value":"port"}`；field 按 properties 的直接键名读取，含点的名称也按字面处理，不解释成路径或脚本。支持 eq、ne、gt、gte、lt、lte、in、exists；eq/ne/in 比较 JSON 类型，数字1和1.0相等，数字1与字符串"1"不同。数值大小比较只接受数值，不转换字符串。缺失字段在比较中不匹配，包括ne；显式null可用eq/ne比较。exists默认判断字段存在，显式null也算存在；`value:false` 判断字段缺失。in.value为1至100个JSON标量的数组。and/or使用 `conditions` 数组组合，每组1至32项，最多嵌套8层，整份方案最多512个条件节点。

symbol 使用以下字段，保存时会补齐可选字段默认值，前端可直接使用返回定义：

- POINT：color必填，radius默认6，opacity默认1，strokeColor默认同color，strokeWidth默认0。
- LINE：color必填，width默认2，opacity默认1。
- POLYGON：fillColor必填，fillOpacity默认0.25，strokeColor默认同fillColor，strokeWidth默认1，strokeOpacity默认1。

颜色使用 `#RRGGBB`；透明度范围0至1；radius、width单位为屏幕像素，范围大于0且不超过1024；strokeWidth范围0至1024。不同几何类别的符号字段不混用。

单方案详情、默认方案、创建及修改响应返回独立 `ETag`（例如 `"style-2110000000000000001-v1"`）。PUT、DELETE和切换默认方案必须携带相应方案最新的 If-Match；缺失返回428、版本过期返回412。切换默认时使用目标方案的ETag，原默认与新默认方案的版本同时递增；重复选择当前默认方案不会增加版本。默认方案不能直接删除，应先切换到其他方案。方案管理可在图层导入期间进行，匹配预览要求图层READY；已删除图层的样式接口返回404。

匹配预览请求为 `{"featureId":"要素ID","zoom":9.5}`，返回 styleId、layerId、featureId、styleVersion、featureVersion、zoom 及 matches。matches 每项包含 geometryPath、geometryType、ruleId、visible、symbol；未匹配时 ruleId和symbol为空、visible为false。该接口用于样式编辑器校验和预览，地图交互时应在前端本地执行相同协议，避免每次缩放逐要素请求服务端。前端保存或切换样式后重新读取方案即可，无需重新导入文件。

## 动态矢量瓦片与原始要素编辑

制图浏览使用 EPSG:3857 的 XYZ MVT，保留 SINGLE 数据图层与混合几何。地图不再截取视窗中的前2000个要素。点默认按全球对齐的64像素网格聚合，可关闭聚合；线使用半像素容差简化，面使用一致的瓦片网格量化及裁剪，不逐面独立简化，避免破坏共享边界。原始 EPSG:4326 的 XY/XYZ 几何及属性不随瓦片生成改变。极区显示裁剪到 Web Mercator 纬度范围，原始要素仍可精确查询。

调用顺序：

1. 查询图层目录及默认样式，取得 layerId、dataVersion 和渲染规则。
2. `POST /api/gis/vector/layers/{layerId}/render-contexts`，请求示例 `{"fields":["kind","rank"],"aggregatePoints":true,"bufferPixels":64}`。fields 必须包含所有规则引用的属性名（按字面取值，最多512项）；不需要完整属性。bufferPixels 为最大符号半径或半线宽加余量，范围0至2048。
3. 从统一包装的 data 中取得 contextId、dataVersion、fields、aggregatePoints、gridPixels、bufferPixels、maxZoom 和 expiresAt。按地图视窗请求 `GET /api/gis/vector/layers/{layerId}/tiles/{z}/{x}/{y}.mvt?contextId=...`。响应为原始二进制 `application/vnd.mapbox-vector-tile`，空瓦片为200及零字节；瓦片尺寸固定256像素，MVT extent为4096。
4. 点击普通瓦片要素，读取字符串 `_vector_feature_id` 后调用原有 `GET .../features/{featureId}` 获取完整 GeoJSON 和 ETag。点击聚合标记，调用 `GET .../clusters/{clusterId}/features?contextId=...&limit=50&cursor=...`；返回统一包装，data 含 members、hasMore、nextCursor、dataVersion。成员按原始要素ID去重，limit为1至100；游标绑定上下文与聚合。聚合计数是点位数量，featureCount 为不同原始要素数量。
5. 编辑使用独立选中覆盖层，保留完整属性、集合结构和坐标维度。`PUT .../features/{featureId}` 或 `DELETE .../features/{featureId}` 使用详情的 If-Match。成功后刷新图层版本并重建渲染上下文；412保留草稿。XYZ 顶点可拖动，插入新顶点须显式提供高程。

MVT 数据层名为 `features`。元数据包含 `_vector_kind`（feature/cluster）、`_vector_feature_id`、`_vector_geometry_path`、`_vector_part_id`、`_vector_cluster_id`、`_vector_category`（POINT/LINE/POLYGON）、`_vector_properties`、`_vector_count`、`_vector_feature_count`。ID和计数均为字符串，属性投影以JSON字符串编码，保留数字、布尔、null、缺失及嵌套值。GeometryCollection仅在显示通道递归拆分，原始要素ID及 `/geometry/geometries/...` 路径保留。客户端不得把瓦片片段当作可编辑原始几何或统计已加载要素总数。

聚合按网格及所投影属性的完整JSON分组，确保同一组在任意连续zoom下使用相同规则；属性类别多时可能产生更多标记。颜色、透明度、规则值或层级变化可本地重绘；新增过滤字段、扩大符号缓冲、切换聚合方式或更改数据版本需要重建上下文。相邻瓦片缓冲中的同一聚合使用相同ID及完整成员。

Redis保存上下文及有界瓦片缓存。缓存键绑定数据版本、字段清单、网格、缓冲及聚合选项；即使命中缓存，也先检查图层状态和版本。默认上下文存活1800秒，瓦片缓存120秒、最多64份，命中时更新最近访问顺序；单瓦片未压缩载荷上限2MiB。每个服务实例最多4个并发空间查询，另有32个有界排队位置，最长等待10秒，单次SQL超时10秒。同一实例内相同瓦片的并发请求合并为一次生成；排队不保留数据库连接，取得执行槽位后才建立只读事务，并重新校验图层版本。

配置位于 `gis.vector.tiles`，支持 `GIS_VECTOR_TILE_GRID_PIXELS`（须整除256）、`GIS_VECTOR_TILE_MAX_ZOOM`、`GIS_VECTOR_TILE_MAX_BYTES`、`GIS_VECTOR_TILE_MAX_CONCURRENT`、`GIS_VECTOR_TILE_MAX_QUEUED`、`GIS_VECTOR_TILE_QUEUE_WAIT_MS`、`GIS_VECTOR_TILE_QUERY_TIMEOUT_MS`、`GIS_VECTOR_CONTEXT_TTL_SECONDS`、`GIS_VECTOR_TILE_CACHE_TTL_SECONDS` 和 `GIS_VECTOR_TILE_MAX_CACHED`。调大并发不能替代查询优化，队列等待及SQL预算调整后须同步核对前端瓦片HTTP超时（当前35秒）。

前端以整个地理信息应用为范围限制最多2个真实瓦片HTTP在途请求，合并相同瓦片订阅，关闭瓦片GET的隐藏自动重试。取消尚未发送的请求会移出队列；已经发送的请求完成前继续占位，避免取消XHR后数据库仍计算、浏览器却继续补发。取消的订阅不会更新地图。图层范围之外及当前没有可见样式规则的图层不发瓦片请求。OpenLayers的渲染任务数仍独立受控，不将其直接当成HTTP并发数。

SQL使用已维护的几何类型元数据跳过不需要的点聚合或集合分支，仅对GeometryCollection递归。空间包围盒使用现有GiST索引，常量查询范围不形成阻止索引估算的物化屏障；属性只读取规则需要的字段。原始大几何不进入全局递归物化结果，MVT裁剪每个片段只执行一次，点聚合以坐标均值计算中心，事务内关闭该类复杂SQL的JIT编译。原始要素、属性、集合路径及共享面边界语义保持不变。

动态MVT本身不保证任意密度都能快速完整显示。2026-10-21本地真实数据抽样中，约37万条道路的一个Z10瓦片生成约286毫秒、755368字节，约16万个建筑轮廓的对应瓦片约106毫秒、175214字节；同一区域Z7道路瓦片约2.9秒但载荷达到11034143字节，会按预算明确返回422。这些是单次只读测量，并非吞吐量保证。密集道路和建筑应配置适合的最小显示层级；若未来对小比例尺访问有确定的性能需求，可以另行生成静态MVT成果。查询预算超限不会静默截断，也不应单纯提高字节或并发上限。

错误处理：400为无效坐标或参数，409为数据版本变化或图层未就绪，410为上下文过期，422为瓦片内容过密，503为查询繁忙、超时或缓存服务不可用。前端409刷新图层和上下文，410重建上下文，422提示放大或开启聚合，其他错误提供重试。瓦片响应为 `Cache-Control: no-store`，由服务端版本缓存及当前前端source管理；网关应保留二进制内容、状态码与ETag。该能力需要 PostgreSQL/PostGIS 和 Redis，无需新增数据库表。

