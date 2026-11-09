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

`GET /api/gis/dataSet/page` 仅接受 `current`、`size`、`categoryId`、`dataSetName`，返回数据集基本信息及文件数、文件组数、总大小，不查询或返回 `filePoints`，也不加载文件解析状态汇总。卡片按分类显示 `GET /api/gis/dataSet/thumbnails/{imagery|terrain|vector}.png` 提供的类型示意图，原图位于 GIS 模块的 `src/main/resources/static/dataset-thumbnails/`，浏览器缓存一天。示意图不代表数据集实际覆盖范围；详情按需调用 `GET /api/gis/dataSet/{id}/footprints?simplifyTolerance=0.001`，返回合并后的概略范围。前后端需同步更新。

范围接口仅合并未删除、源状态 READY 且元数据 COMPLETED 的文件图层。有范围时返回一个数据集级 GeoJSON Feature，属性为 `dataSetId`、`sourceFileCount`、`sourceLayerCount`、`extentKind`、`simplified` 和 `simplifyTolerance`；无范围时返回空 FeatureCollection。矢量仍表示包围范围，影像和地形表示有效像元范围，点线混合结果允许 GeometryCollection。详情同时提示待解析、失败文件数量。

默认预览精度为 0.001 度：先拓扑保持简化、再按同等网格对齐、合并并再次简化，消除文件内部边界、重叠填色及细小拼接缝。该精度是各处理步骤的参数，不是最终边界偏差上限；细小孔洞或分块可能省略，单个文件图层不能因精度对齐而整体消失。源空间元数据保持完整；显式传 0 时执行完整精度并集，复杂轮廓可能耗时很长。未引入缓存，文件删除或元数据更新在下一次查询直接生效。

2026-11-06 开发库实测（PostGIS 3.5.7 / GEOS 3.14.1，最终 Mapper SQL，每组首次执行后重复三次取中位数；大小仅统计未压缩几何 JSON，不包含属性或网络耗时）：

| 数据 | 可用图层数 | 原几何 JSON | 合并预览 JSON | 合并预览查询中位耗时 |
| --- | ---: | ---: | ---: | ---: |
| 安徽 POI 矢量 | 665 | 122,519 B | 1,708 B | 93 ms |
| 全球 90 米 DEM | 2,592 | 47,652,985 B | 87 B | 927 ms |
| 国内部分省份影像 | 4 | 43,440,641 B | 260,344 B | 2,542 ms |
| 全球 500 米影像 | 8 | 123,334 B | 86 B | 6.5 ms |

省份影像完整精度直接合并触发 60 秒语句超时，GEOS 运算的中断存在延迟，不适合作为详情默认请求。验证覆盖 10 个真实数据集，以及两种容差下的相邻/重叠范围、孔洞、分离区域、跨经线、全球覆盖、点线混合、微小栅格与不可用文件过滤。另验证 0、0.001、0.13、0.7 度网格的世界边缘数据，精度对齐结果始终裁切到合法经纬度范围。

上传或工作区扫描整批收口后，后台自动识别文件组，再进入 `EXTRACTING_METADATA`。文件组是业务管理和解析单位，单个独立文件也建立组；同一数据集可包含多个组，每个物理文件最多属于一个组。矢量组在同一次提取中生成可编辑图层与要素，所有图层 READY 后才计为解析成功。接入仍使用现有数据集、文件或工作区路径参数，不要求客户端提供分组清单。

支持的矢量入口为 GeoJSON、SHP、DXF、MIF、TAB、CSV、GPX、DWG；栅格入口为 GeoTIFF、IMG、JPG/JPEG、PNG、GIF、GRIB2。GeoJSON 与 GeoTIFF 保留既有 Java 解析链路，其他格式通过 GDAL/OGR 读取真实图层或波段。GeoJSON 继续要求 RFC 7946 的 WGS84 经度、纬度顺序，接受下文列出的顶层旧式 WGS84 命名 `crs` 声明；读取配置不能将任意投影的 JSON 当作标准 GeoJSON 导入。

业务列表使用 `GET /api/gis/file-groups/page`，默认排除重复输入追溯记录，传 `includeDuplicates=true` 可查看。详情 `GET /api/gis/file-groups/{id}` 返回组、成员与 `effectiveGroupId`；重复输入指向原规范组。`assemblyStatus` 表示完整、缺件、未识别、能力阻塞或重复，`parseStatus` 表示等待、排队、执行、完成、失败或 `REIMPORT_REQUIRED`。文件组与矢量业务分类树 `gis_vector_group` 是两个独立概念。

SHP 必须有 `.shx/.dbf`，矢量 TAB 必须有 `.dat/.map/.id`；MIF 含属性列时需要 MID，`Columns 0` 可无 MID。IMG 外部像素文件、world file、PRJ 等按真实格式关系识别；`Type RASTER` 的 TAB 可作为图像配套。缺件、归属歧义、缺 CRS 或驱动缺失会保留文件并进入等待或阻塞状态，配套文件不单独领取解析任务。上传只有原始文件名和批内索引，服务端不按上传顺序猜配件，也不按同名跨批拼接；工作区仅在同一可靠来源目录、格式或内容证据能唯一匹配时补件。

完整组按格式、规范化成员清单及内容 SHA-256 去重，不把 UUID、批次、绝对路径或读取参数计入指纹。重复输入保留独占的追溯组及物理文件，记录 `duplicateOfGroupId/matchedRevision`，不会重试或改变规范组既有状态。未完成组补齐后若命中规范组，同样记录重复关系，不迁移成员或自动删源。`inputRevision` 仅随有效成员或读取参数变化递增；已成功成果发生输入变化时进入 `REIMPORT_REQUIRED`，不会自动覆盖 READY 图层或编辑结果。

`PUT /api/gis/file-groups/{id}/options` 保存读取配置，未提供的字段保留，显式空白字符串清除对应字段。CSV 必须明确配置 `sourceCrs`、`xField`、`yField`，X/Y 字段须不同，不能默认猜测 WGS84；DXF/DWG 也需明确源 CRS。CRS 接受 EPSG 编码、`OGC:CRS84` 或明确 WKT，拒绝文件或 URL 引用。例如：

```json
{"sourceCrs":"EPSG:4326","xField":"longitude","yField":"latitude"}
```

首次有效输入就绪后自动解析；补齐读取配置可解除相应阻塞。显式重新提取使用 `POST /api/gis/file-groups/{id}/retry`，已有矢量成果引用时须先处理相关成果，避免覆盖。`DELETE /api/gis/file-groups/{id}` 检查全组任务及成果引用后删除成员登记；上传来源清理托管文件，工作区原文件保留。源文件变化或不可用时须保留源文件、删除失效登记后重新接入，重试不会默默刷新来源快照。

`GET /api/gis/filemeta/page` 继续提供物理文件列表，支持 `fileGroupId`、来源和元数据状态筛选；物理文件详情、下载及 `DELETE /api/gis/filemeta/{id}` 保留原有语义。删除单成员会使组修订失效并重新等待完整输入，活动解析、处理任务或矢量成果引用会阻止删除。物理文件的 `PARKED` 表示已保存但暂不能独立解析，应结合所属组状态展示。

```text
GET /api/gis/filemeta/page?west=110&north=30&east=115&south=25&spatialRelation=INTERSECTS
GET /api/gis/filemeta/page?west=110&north=30&east=115&south=25&spatialRelation=COVERED_BY&requiredZoom=12&tileMatrixSetId=WebMercatorQuad
```

四个框选坐标必须同时提供；`west > east` 表示跨日期变更线。`INTERSECTS` 包含部分相交及边界接触，`COVERED_BY` 表示文件范围完全位于框内（含边界）。查询按文件去重，空间与层级条件必须由同一图层满足。

分页和详情返回提取状态、错误码及 `spatialReferences`。列表默认省略完整范围几何，`includeGeometry=true` 可返回 EPSG:4326 WKT；详情默认返回完整范围。GeoTIFF 范围沿每条边采样后转换并切分跨经线几何；GeoJSON 使用坐标包围范围，不保证框内存在实际要素，栅格范围也不扣除 NoData 区域。

`recommendedMaxZoom` 按文件所属数据集的默认处理类型估算，并限制为 0～22。影像使用 `WebMercatorQuad`、256×256 像素口径，以 3×3 位置采样的 EPSG:3857 像素间距最大值估算；向下取整前对距整数层级不超过 1e-7 的浮点误差归整，避免原生第 14 层被误判为第 13 层。地形使用 `WorldCRS84Quad`，按当前固定版本 mago-3d-terrainer 的源 X 像素间距及末级细分规则计算默认可用深度。查询 `requiredZoom` 时须同时选择对应的 `tileMatrixSetId`，两种矩阵不混比。矢量或无法估算的文件该值为空；未知层级不命中 `requiredZoom`。该值不代表显式修改处理参数后或多文件组合生成的成果实际层级。

提取沿用主入口文件的数据库待办、版本校验和 15 分钟租约，并同时核对组的 `inputRevision`，过期执行不能提交。服务重启后恢复可领取的解析待办，`PARKED` 不属于执行租约。扫描间隔由 `gis.metadata.poll-interval` 配置，默认 5000 毫秒；使用独立的两个执行线程。

需 GDAL 处理的格式统一通过 GDAL 3.13.0 Java API 读取和转换，由主服务启动独立 Java 工作进程加载 JNI，业务不调用 GDAL 命令行程序。父 POM 固定 `org.gdal:gdal` 版本；该 JAR 只提供 Java API，运行环境还须具备同版本的 `gdalalljni` 桥接库、GDAL 核心库、格式驱动与 PROJ 数据。部署镜像从固定版本源码和 SHA256 构建核心库及 JNI，构建时检查目标驱动、GeoJSON/GeoTIFF 写入能力和 EPSG 解析。直接在 Windows 启动 Java 时，配置 `GIS_GDAL_NATIVE_LIBRARY_PATH` 为同版本 `gdalalljni.dll` 所在目录，并把 GDAL 及其依赖 DLL 的目录加入 `PATH`，正确配置 `GDAL_DATA`、`PROJ_DATA`。工作进程使用主服务同一 JRE；`GIS_GDAL_WORKER_MAX_HEAP` 默认 `1g`，只约束 Java 堆，原生缓存与驱动内存另计。`GET /api/gis/file-groups/formats` 返回格式及配件规则，实际驱动能力在读取时检查。DWG 依赖 GDAL 构建中可用的 CAD/DWG 驱动及其支持的文件版本，普通安装不保证具备该能力；缺驱动明确阻塞。无 CRS 或无有效定位的栅格不会生成虚构范围，world file 也不能代替 CRS 声明。

从已管理文件提交影像或地形处理任务时，`fileMetaIds` 必须选择已完整解析栅格组的主入口。任务保存整组成员、来源、SHA、读取配置及输入修订的不可变快照，活动任务保护全组成员；执行时按该快照核验来源并准备临时 GeoTIFF，继续交给既有切片引擎。当前 `MANAGED_FILE` 不承接矢量组切片，矢量接入直接生成可编辑图层。

数据库须安装 PostGIS；部署镜像使用 `postgis/postgis:16-3.5-alpine`。业务表与索引统一维护在 `db/migration/V2__init_ocean_gis.sql`。已有开发库需先补齐最终结构，再依次执行 `mvn flyway:repair`、`mvn flyway:validate`；repair 本身不会修改业务表结构。

矢量文件逐要素流式导入，与在线编辑采用独立限额：导入单要素最多100万个顶点、400万个 JSON 节点，几何和属性合计不超过32MiB；在线编辑仍限制4MiB请求体。GDAL 矢量转换统一执行 MakeValid 并输出 RFC 7946，规范化后的几何继续通过 PostGIS 拓扑校验，不忽略转换失败的要素。全球面的长经差边界按平面线段解释，不仅凭经差超过180度拒绝导入。空几何和无位置记录保留在不可变原始快照中，工作图层不生成虚构几何，并通过 `vectorImport.emptyGeometryFeatures` 返回数量；未声明或未登记的 CRS 仍须补齐，不默认猜测坐标系。

栅格与 GDAL 输入在隔离的临时目录中重建，只暴露已登记组成员，避免自动借用源目录中未登记的辅助文件；原文件只读。GeoTIFF 显式配置 `sourceCrs` 时也进入 Java GDAL 工作进程，生成带投影的临时 GeoTIFF 后读取。主服务监控整个工作进程的租约、超时、日志、元数据及临时输出限额；失败或取消后终止该进程及其后代，释放 Windows 文件句柄后清理临时目录。工作进程在读取前检查原生版本和实际驱动，缺库、版本不匹配、缺驱动或不支持文件版本时明确阻塞，不切换其他读取路径。

## 从受控服务器工作区接入数据

`POST /api/gis/filemeta/workspace-files?dataSetId={id}` 原位接入服务器文件，必填查询参数 `dataSetId` 指定所属数据集。不转存或移动源文件，也不创建文件传输记录或切片处理任务；格式读取所需临时副本随读取上下文清理。工作区沿用 `gis.processing.workspaces` 配置。接口只校验入口，立即以HTTP 202返回 `dataSetId` 和统一 `progress` 引用（`progressId`、`type=GIS_WORKSPACE_IMPORT`、`status=QUEUED`、`queryUrl`、`eventsUrl`），目录遍历、登记、组装及空间提取进度均在后台推进。

```json
{"workspaceCode":"DEFAULT","relativePath":"survey/2026","includeSubdirectories":true,"vectorPlacement":"AUTO"}
```

服务端自动识别文件或目录，按数据集分类接收上述入口及允许的配套文件；扩展名通过后仍须解析真实内容，不能可靠归组的文件先保留。`vectorPlacement` 仅用于矢量数据集，可省略，默认AUTO。

目录只登记匹配格式的普通文件；`includeSubdirectories` 默认false，仅扫描当前层，true递归扫描所有层级。不支持的文件跳过，同一数据集内已登记的工作区相对路径按重复跳过；无符合条件文件时任务失败。扫描不跟随链接或Windows目录联接，禁止绝对路径、上级目录和盘符路径。`gis.processing.workspace-max-scan-entries` 默认10000，计入目录和文件条目；后台边扫描边登记，超限明确停止，保留已登记文件，等待这些文件解析结束后报告失败。执行复用现有有界 `taskExecutor`，最多保留100个活动接入任务，繁忙时返回503。

前端用提交响应的 `progress.progressId` 匹配 `/api/gis/events/stream` 消息的 `data.progressId`。`SCANNING` 阶段 `overall.percent=null`，通过 `metrics` 读取扫描条目、登记、重复、不支持及失败计数。扫描结束进入 `EXTRACTING_METADATA`。`summary.unit=FILE` 统计本批物理文件的接收结果，成员随所属组结果归集；重复和已接收未解析文件计入 `summary.skipped`，允许本次接收收尾，不代表空间解析成功。`FILE_GROUPS`、`COMPLETED_FILE_GROUPS`、`FAILED_FILE_GROUPS`、`DUPLICATE_FILE_GROUPS`、`PARKED_FILE_GROUPS` 指标使用 `unit=FILE_GROUP`，按组去重；`RECEIVED_UNPARSED_FILES` 明确展示缺件、未知或能力阻塞的文件数。

`GET /api/gis/progress/{progressId}` 返回统一完整快照，用于提交竞态、刷新及断线补查。工作区终态保留30分钟，服务重启或过期后返回404（PROGRESS_EXPIRED）。逐文件明细使用文件分页及 `sourceType=UPLOAD/WORKSPACE` 筛选。已入库元数据待办仍按数据库队列恢复；快照恢复不等于恢复目录扫描。

文件模型和查询使用 `fileStatus`、`createdBy`。工作区文件返回 `sourceType=WORKSPACE`、`workspaceCode`、`relativePath` 和Unix毫秒单位的 `sourceModifiedAt`，托管存储字段为空。读取时校验源大小和修改时间；摘要与格式头在组装事务外读取，锁内重验属性和归属。发现变化或不可用时失效相关解析资格及组指纹，已有编辑成果受保护。源文件变化不会主动推送，须在读取或重新接入时验证。

删除工作区文件记录、文件组或整个数据集都保留工作区源文件。下载和从已管理文件提交处理任务统一按来源解析路径；上传、下载操作由统一操作日志留痕。

### 任务标识的关系

`ProgressSnapshot.progressId` 是字符串进度键：上传使用 `upload_...`，数据处理使用 `taskNo`，工作区接入使用 `workspace_...`，归档使用 `archiveNo`。上传和工作区进度通过 `resource` 指向对应数据集，归档的 `summary` 统计一个最终归档文件，打包源文件数放在 `metrics`。数据处理统一 `type=GIS_PROCESSING`，`category=TERRAIN/IMAGERY/VECTOR`。

现有切片处理提交响应中的 `taskId` 是 `gis_processing_task.id`，用于 `GET /api/gis/task/{taskId}`；同一响应中的 `taskNo` 才对应事件消息 `data.progressId`。文件的 `fileMetaId` 是独立的数据身份，一个接入进度可以产生多个文件，这些文件以后可被多个独立的切片处理任务引用。工作区进度编号不能传给处理任务详情接口。

当前处理任务均为静态瓦片生成任务，每个任务对应一个静态瓦片集。`COMPLETED` 表示瓦片生成完成，服务发布状态由发布记录单独管理。

- `PATCH /api/gis/task/{taskId}/name`：请求体为 `{"taskName":"新名称"}`，去除首尾空白后须为 1～200 个字符；仅修改名称和更新时间。
- `DELETE /api/gis/task/{taskId}`：仅允许删除终态任务，存在已发布服务时须先停用，存在排队中或运行中的归档时须等待结束。删除任务、输入快照、瓦片集、已停用发布记录和归档记录，同时清理生成的瓦片、日志及归档包，保留来源文件。文件清理失败时返回错误并保留数据库记录，可再次删除；已清理的文件无法随数据库事务回滚。

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
    "resource": {"type": "DATA_SET", "id": "1", "detailUrl": "/api/gis/dataSet/1"},
    "error": null,
    "startedAt": "2026-10-27T15:30:00",
    "finishedAt": null,
    "revision": {"epoch": 1, "sequence": 3}
  }
}
```

进度事件为 `gis.progress.created/updated/finished`，始终携带完整快照。finished 表示已结束，成功与失败由 `data.status` 判断；其他事件可使用各自的强类型消息体。未知事件类型可忽略。messageId 用于消息去重，时间不作为排序依据。

前端按 `progressId` 保存最新快照，再按 `(revision.epoch, revision.sequence)` 比较新旧；HTTP补查也走相同合并规则。持久化流程重试或快照重新建立时原子递增业务表的 `progress_epoch`，新代次可以从 sequence=0 开始，旧执行句柄不能覆盖新代次。普通查询命中内存快照时不修改版本。持久化进度缺失时先核对有效的Redis终态缓存；运行态或缓存缺失时从业务表恢复，只返回能够确认的工作量。补查发现数据库已结束而内存仍在运行时，会修复并补发终态。

上传、工作区接入、数据处理、归档的提交响应提供统一 `progress` 引用；分块响应保留 nextOffset 等传输确认字段。上传阶段的字节指标与归档记录的 `packingPercent` 均不等于完整业务流程的整体百分比。组配置及重试结果通过组详情和现有进度事件查看，不建立上传批次历史接口。

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

向矢量数据集（`categoryId=2`）调用现有 `POST /api/gis/uploads/chunks` 或工作区接入接口，提交支持的矢量主文件及其配件。完整文件组在空间元数据提取中自动生成可编辑图层，无需再从已管理文件提交矢量导入。多图层格式保留来源图层标识，再按数据集分类规则和几何类别形成业务图层。

原接入进度持续展示 `UPLOADING/SCANNING → EXTRACTING_METADATA → COMPLETED/FAILED/PARTIAL_FAILED`；不同组可并行提取。矢量组在 `EXTRACTING_METADATA` 阶段内流式写入图层和要素，仅所有图层 READY 后关联成员才计入解析成功；缺件和阻塞输入作为已接收未解析项收尾。

通过 `GET /api/gis/progress/{progressId}` 查询本次接入进度，通过文件组详情和现有图层列表查询持久结果。矢量提取共用接入的 Redis/SSE 进度，不创建独立导入任务或上传批次历史表。

完整且具备读取条件的失败组通过文件组重试接口重新提取；未完成图层及要素清理后重建，已有成功图层及其编辑成果不会自动覆盖。传输失败文件需重新上传。

图层通过 `gis_vector_layer.source_file_id` 关联文件组主入口，要素通过 `gis_vector_feature.layer_id` 关联图层。来源图层、Feature 序号和几何路径用于追溯；GeometryCollection 按分类需要拆分递归分量，Multi 几何保持整体。原始文件和配件不因要素编辑而改写。

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

系统分配稳定字符串ID，保留属性 JSON 类型及 XY/XYZ 坐标；properties 可以为对象或null，geometry 必须非空有效。编辑须保留原要素的坐标维度。在线新增、修改及对外输出使用 RFC 7946 经度/纬度坐标，新增修改请求仍拒绝 crs。空几何、混合维度和未切分的跨日期变更线线段会被拒绝。几何拓扑由 PostGIS 校验，不自动修复。单要素最多200000个顶点，几何与属性合计不超过4MiB；HTTP新增修改请求体也不得超过4MiB。来源文件不会随编辑回写。

文件导入长期兼容顶层旧式 WGS84 命名 CRS，格式为 `{"crs":{"type":"name","properties":{"name":"..."}}}`。允许的 name 为 `CRS84`、`OGC:CRS84`、`urn:ogc:def:crs:OGC:1.3:CRS84`、`urn:ogc:def:crs:OGC::CRS84`、`EPSG:4326`、`urn:ogc:def:crs:EPSG::4326`。crs 可以出现在 features 之前或之后；仅校验声明并保持原坐标，不交换经纬度、不执行坐标转换。空间元数据与要素导入共用校验，原声明保存在空间元数据的 `extraMetadata.declaredCrs`，内部按 OGC:CRS84/SRID4326 处理，原文件不变。

`crs:null`、链接型声明、未知或其他坐标系（如 EPSG:3857）以及 FeatureCollection 内的子级声明均不支持；元数据提取阶段返回 UNSUPPORTED_CRS。顶层 Feature、Geometry 或 GeometryCollection 文件同样支持上述声明，子级声明一律拒绝。仅在业务 properties 内名为 crs 的普通属性不受影响。没有 crs 的标准文件继续按原规则导入；失败输入可在满足读取条件后显式重试文件组。

要素详情及新增修改响应提供 ETag。PUT、DELETE 要素时必须把该值原样放入 `If-Match`；删除图层使用图层详情的 ETag。缺失返回428，版本过期返回412。编辑在同一事务内更新要素版本、图层数据版本、数量及范围；图层范围与原文件的空间元数据独立维护。被活动图层或待执行导入引用的来源文件，不能删除或变更来源信息；删除图层会解除其来源引用。

范围查询使用真实要素 `ST_Intersects`，`bbox=west,south,east,north`，west>east 表示跨日期变更线。limit 为1至1000，默认200；每页另有约8MiB载荷预算。响应包含 hasMore、nextCursor、dataVersion；后续页保留相同bbox并传cursor。图层编辑后旧cursor返回409，客户端应重新加载第一页。省略bbox可用于有界的属性列表遍历。

矢量提取共用 `gis.metadata.poll-interval` 和空间元数据执行线程，不另建矢量任务队列。文件组状态、输入修订与主入口解析租约共同约束提交，Redis/SSE仅用于实时进度。

## 矢量图层样式方案

接入图层按来源图层、分类与几何类别生成。已识别图层继承数据集分类样式；未知图层默认关闭，须显式配置。自定义样式方案保存在 `gis_vector_style.definition` JSONB 中，修改样式不改写几何、要素版本或图层 dataVersion，已有要素分页游标仍可继续使用。

接入弹窗的“图层业务分类”可直接选择“x院业务分类－亮色”或“x院业务分类－暗色”。两种入口均复用现有参数：本地上传使用 `Upload-Vector-Placement: X_LIGHT/X_DARK`，服务器工作区使用 `vectorPlacement: "X_LIGHT"/"X_DARK"`；不增加主题参数。`AUTO` 和 `UNKNOWN` 保持现有含义。

两份规则维护在 `ocean-business-geo/src/main/resources/vector/x-institute-light.json` 和 `x-institute-dark.json`，各包含35条业务归类规则，对应41个原始图层。分组为背景与行政区、水系与绿地、建筑、行政边界、道路、地名与标注，按照来源集合名称和几何类别匹配；源属性保持不变。首次接入时在文件登记事务中用所选完整模板替换空数据集的初始目录；数据集只固定一套业务分类，文件保存所选值。重复选择不重复建组；已有接入文件的数据集不能混入其他模板，其他规则应接入独立数据集。工作区按 x 院模板的业务目录原样展示，未匹配图层收纳在该模板内；后续接入默认沿用当前模板。重复文件仍遵循现有去重规则。

配图参数来自《04-当前亮色与暗色主题样式清单》及 `ocean_basemap` 已生成的SLD。规则内使用比例尺分母，最小值包含、最大值不包含；道路中间整数层级按既定端点线性递增，z12+保持最终值。`renderRules` 累计所有命中项，支持多层填充/描边、桥隧虚线、首都五角星、纯文字、字号/字体/光晕与名称回退。数据瓦片按 `tilePolicy.propertyFields` 输出声明的标量属性，PostGIS和GPKG采用同一字段契约。

补定条件在配置的 `provenance.assumptions` 中保留，包括大型水体 `mj >= 500000`（源字段单位未提供）、重点城市名单及边界类别映射。清单未覆盖的铁路/地铁，以及未匹配来源和几何的图层归入未知组；解析失败的数据仍需单独处理，选择分类规则不会修复源几何或补造要素。

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

## 原始集合、工作存储与动态矢量发布

原始文件、原始要素集合、工作图层和发布版本分别管理。新导入的矢量文件先生成不可变 GPKG 原始快照，再进行坐标转换、分类和工作图层提取；空集合及不能参与制图的原始记录仍可在快照中浏览。原始 GeoJSON 保留 Feature ID、完整属性 JSON、空几何及坐标维度；其他已支持格式保存 GDAL 读取到的集合、字段类型、属性和源坐标。快照是要素级保存，不是原文件字节备份，也不保证保留 CAD 布局等非要素信息。

| 功能 | 接口 | 关键行为 |
| --- | --- | --- |
| 生成原始快照 | `POST /api/gis/filemeta/{fileId}/source-snapshot` | 为已有文件显式生成快照；不重新导入工作图层；重复调用返回现有快照 |
| 原始集合目录 | `GET /api/gis/filemeta/{fileId}/source-layers` | 返回原集合名、数量、源 CRS、字段结构，包含空集合；配件ID定位所属组入口 |
| 原始要素分页/详情 | `GET /api/gis/vector/source-layers/{id}/features?after=0&limit=100&featureId=…` | `featureId` 可省略；返回 sourceRowId、完整 Feature、hasMore、nextAfter；坐标保持源坐标系 |
| 释放影响预览 | `GET /api/gis/filemeta/{fileId}/release-preview` | 完整文件组、可释放大小、阻塞任务及保留说明 |
| 释放原始文件 | `POST /api/gis/filemeta/{fileId}/release` | 整组释放；保留来源记录、原始快照、工作数据和发布版本 |
| 资源目录 | `GET /api/gis/vector/datasets/{id}/resources` | 原始快照、工作存储、发布资源及转换所需 ETag |
| 切换工作存储 | `POST /api/gis/vector/datasets/{id}/storage?target=GPKG` | target 为 GPKG 或 POSTGIS；携带资源目录返回的 If-Match |
| 回收资源 | `DELETE /api/gis/vector/resources/{id}` | 仅允许无工作图层、无发布引用的非原始资源 |

原文件释放前验证快照 SHA-256、文件组解析状态及活动处理任务。正在解析或被处理任务实际使用时拒绝释放；已导入图层的来源关系不再阻止物理文件释放。状态为 `RETAINED → RELEASE_PENDING → RELEASED`，删除失败由定时任务重试；工作区文件直接解除接入，外部文件不会被删除。释放后原文件下载及再次以原文件为输入的处理不可用，但原集合浏览和已导入数据不受影响。既有 DELETE 文件接口仍用于删除管理记录；界面上的“释放原文件”应调用新的 release 接口。

### 按用途选择存储

- 经常编辑、更新或空间分析：保留 POSTGIS 工作存储，沿用要素编辑、空间索引和事务。
- 只读浏览、归档和发布：显式转换到 GPKG。每个工作资源文件内按图层建表并建立 RTree；转换成功后删除相应 PostGIS 工作要素，不双写。
- 需要再次编辑：显式转回 POSTGIS，保留要素 ID、版本和来源集合关系。图层返回 `storageType`、`readOnly`、`resourceId`、`sourceLayerId`；GPKG 几何/属性写入返回409。

当前存储选择作用于整个数据集的已就绪工作图层。接入仍先完成 PostGIS 工作导入，可在导入完成后转为 GPKG；大规模导入需要预留转换期间的数据库和文件空间。工作区存在逐要素配图覆盖时，先保存独立样式方案并移除工作区覆盖再转换，避免要素表外键级联造成样式丢失。转换使用数据集锁与 ETag，失败回滚数据库切换并清理新文件；可再次提交原操作。

GPKG 适合分散只读存储，不保证任意数据量和并发下都优于 PostGIS。动态瓦片采用 RTree 范围查询、级别抽析、有限进程并发和有界缓存；密集数据仍可能触发体积/时间限额。文件位于 `gis.processing.basePath/vector-resources`，多实例必须能访问相同托管目录；备份应同时包括数据库目录元数据和 GPKG 文件。

### 一份动态瓦片，多套独立样式

工作地图定义：`GET /api/gis/vector/datasets/{id}/map-definition`；工作数据瓦片：`GET /api/gis/vector/datasets/{id}/tiles/{tileVersion}/{z}/{x}/{y}.mvt`。请以控制器实际返回的地图定义 URL 为准。瓦片版本由数据和抽析规则确定；颜色、显隐及显示缩放配置不改变瓦片数据。

独立样式方案使用 `/api/gis/vector/datasets/{id}/style-schemes`：GET列表、POST创建，`/{schemeId}` GET详情、PUT修改、DELETE删除。创建时传 `{"name":"标准图"}` 可复制当前完整制图配置；也可提供 `definition`。修改和删除携带详情返回的 `"scheme-{id}-{version}"` If-Match。方案结构为 `{"schemaVersion":1,"kind":"VECTOR_STYLE_SCHEME","cartography":{…},"featureOverrides":{…}}`，其中 cartography 包含 defaults、groups、layers；只描述显示，不能修改瓦片生成策略。

发布接口：`POST /api/gis/publications/VECTOR/publish`：

```json
{
  "dataSetId": "数据集ID",
  "serviceName": "城市矢量服务",
  "styleSchemeIds": ["标准样式ID", "夜间样式ID"],
  "defaultStyleSchemeId": "标准样式ID"
}
```

发布至少选择一套样式，默认样式必须属于所选方案。服务固定当时的数据版本及每套样式的内容；同一工作数据版本的多个发布共用同一个不可变 GPKG 资源。之后编辑工作要素、修改/删除草稿样式或释放原文件，都不改变已经发布的内容。

| 公开资源 | 路径 |
| --- | --- |
| 服务清单（默认样式、各套样式地址、数据版本及瓦片地址） | `/gis/publications/VECTOR/{serviceCode}/manifest.json` |
| 固定数据瓦片 | `/gis/publications/VECTOR/{serviceCode}/tiles/{resourceId}/{z}/{x}/{y}.mvt` |
| 固定版本样式规则 | `/gis/publications/VECTOR/{serviceCode}/styles/{releaseVersion}/{schemeId}.json` |

`PUT /api/gis/publications/VECTOR/{serviceCode}/styles` 使用与发布相同的请求结构，携带 `"release-{releaseVersion}"` If-Match；仅更新发布的样式组合和默认项，继续使用固定瓦片版本。旧样式 URL 可继续读取。需要更新几何数据时重新发布。已有服务重命名和停用接口同样适用于 VECTOR；停用后清单、样式和瓦片均返回404，缓存命中也不能绕过停用状态。

样式 JSON 使用本项目的声明式规则协议，**不是 MapLibre Style Specification**；客户端须按现有规则解释器执行。MVT 只携带数据，不绑定默认样式。数据层名为 `features`，包含字符串 `_vector_feature_id`、`_vector_layer_id`、`_vector_group_id`、`_vector_group_key`、`_vector_representation_id`、`_vector_render_role`、`_vector_category`，以及 fclass/code/id/name/ref 标量属性；`_vector_null_fields` 标记显式 null。面填充、真实边界线和标注锚点分别输出，几何不可用作原始编辑。EPSG:3857 XYZ，extent=8192，逻辑 tileSize=512，实际级别和抽析限制以数据契约为准。

发布瓦片缓存按 resourceId/z/x/y 共享，样式切换不重新生成瓦片。默认压缩上限2MiB、解码上限16MiB、本地缓存最多64块/64MiB；超限不静默截断要素。文件瓦片在受限 GDAL Java Worker 中执行，单次查询有30秒进程预算。工作数据集同时包含新导入PostGIS层和GPKG层时支持联合读取，统一转为GPKG后可一次打开包完成瓦片查询。

配套前端位于同项目第二个源文件夹 `../Ocean_Cloud`。地理信息应用的“原始文件”页已接入原始集合、来源要素分页与释放预览；“存储与发布”页提供工作存储转换、独立样式草稿、多样式发布及默认样式选择，服务详情支持仅更新发布样式。原始要素分页中的 `featureJson` 是供浏览器无损展示的 JSON 文本，避免大整数属性被 JavaScript 数值精度截断。

### 制图方案保存与静态矢量成果

工作区顶部通栏左侧用卡片选择样式方案，默认展示四个（包含默认配图），其余通过“更多”查看；从“更多”选中的方案会替换第四张卡片，保持当前方案可见。卡片展示名称与三个主要面填充色构成的配色示意，蓝色边框表示当前方案。保存修改、另存方案、生成静态瓦片及静态成果入口位于方案栏容器外侧的右边，作为独立操作区与方案栏并排；下方为分类侧栏、地图及样式编辑器。列表请求 `GET /api/gis/vector/datasets/{id}/style-schemes?summary=true` 仅返回ID、名称、版本和 `previewColors`，点击后才读取完整方案，不为卡片请求地图瓦片。

制图工作区的样式配置通过控件操作直接更新地图预览，无需额外点击“应用”。层级范围或分段配置不合法时保留最近一次有效预览，并在编辑器内提示修正。保存操作统一位于顶部右侧：默认配图不可覆盖，调整草稿后仅可“保存为新方案”；自建方案支持“保存修改”更新原方案，也可“另存为新方案”生成独立副本。保存包含整套分类配置及最新修改。切换分类保留本次编辑；切换方案提示放弃未保存修改。保存后的方案可重新选择、回显并继续编辑。分类覆盖支持整体或连续层级分段、填充纹理、边界、标注字体与颜色、显隐；纹理在客户端绘制，字体使用所配置的系统字体族。

`POST /api/gis/vector/datasets/{id}/style-schemes` 可附带 `groupOverrides`（业务分类键到配置）、`groupVisibility`（业务分类键到布尔值）和 `styleVersion`。未提供 `definition` 时，服务端复制完整当前配图及要素覆盖并校验当前样式版本；提供 `definition` 时，以该独立方案为基础合并修改。完整分类覆盖包含 `symbol/mode/minZoom/maxZoom/segments/labels`；结束层级为不包含的上界，空 `dashArray` 表示实线。

自建方案更新复用 `PUT /api/gis/vector/datasets/{id}/style-schemes/{schemeId}`，携带完整定义及 `If-Match: "scheme-{schemeId}-{version}"`。更新保留方案ID并递增版本；版本冲突返回412，前端保留编辑草稿并提示另存或重新加载。工作区默认配图没有独立方案ID，不会走更新接口。静态任务提交时固定方案快照，后续更新方案不改变已生成或已提交任务的样式。

选择已保存方案后可生成静态矢量瓦片：`POST /api/gis/vector/datasets/{id}/static-tiles`，请求包含 `taskName`、`schemeId`、`schemeVersion`、`dataRevision`、`minZoom`、`maxZoom`、`bounds`（西、南、东、北，经纬度）。切片采用 EPSG:3857 / XYZ / MVT，实际生成层级为0至18级。提交时固定样式内容及工作数据修订；后台在数据集锁内校验修订并复制工作数据。排队期间数据修订改变时明确失败，要求重新生成，不悄悄使用不同的数据。

静态任务采用 `VECTOR_RESOURCE` 来源、独立单线程队列和任务专属 GPKG 快照；批量切片在一个隔离 GDAL 进程内复用数据源。任务数量、单片压缩/解码体积、总产物大小和进程时间均受限；默认每任务最多50000块（`gis.vector.tiles.max-static-tile-count`）。成果为 `{z}/{x}/{y}.mvt`、`style.json` 和 `manifest.json`，完成后可沿用任务归档下载流程。失败任务不会作为可用成果展示。

制图工作区“静态成果”读取已完成任务，并通过 `/api/gis/task/{taskId}/vector/manifest.json` 和同目录瓦片、样式接口预览；渲染使用任务保存的完整方案，后续工作区修改不影响既有成果。成果使用项目当前声明式样式协议，需要配套渲染器解释。

后续性能专项：制图工作区动态矢量瓦片存在用户反馈的卡顿、响应慢和间歇性报错。应采集真实范围、层级、数据量、失败状态与服务日志，分别测量请求排队、数据库空间查询、GPKG读取、MVT编码、传输解码及前端绘制耗时，再确定优化措施并对比优化前后的延迟与错误率。本次静态切片隔离不代表这些动态性能问题已解决。

#### 动态瓦片排查记录（2026-11-04，第一处修复）

实测数据：测试SHP矢量数据（ID `2117822266198102017`，数据修订47，46个工作图层、1006647个要素）；测试SHP矢量数据2（ID `2117848443952447489`，数据修订45，45个工作图层、1006638个要素）。两套工作图层的 resource_id 均为空，实际走 PostGIS；资源列表中的 GPKG 是原始快照。

有限HTTP基线使用本机 `127.0.0.1:8090`，最大并发4，不清理共享Redis、数据库或操作系统缓存。应用瓦片缓存未命中的7块：`3/4/2`、`6/51/25`、`6/52/25`、`0/0/0`、`3/6/3`、`10/843/388`、`12/3372/1552`，分别耗时9625、9629、9639、6182、6198、6164、6146 ms。P50=6198 ms，P95=9639 ms（最近秩法，小样本），错误率0/7。`2/2/1` 首次探测已命中缓存，49 ms，不计入未命中基线。重复读取首批4块为37、23、19、30 ms，P50=23 ms、P95=37 ms，错误率0/4。所有响应200，非空MVT使用gzip。此处未命中仅指应用瓦片缓存，不代表数据库磁盘冷缓存。

两套数据的分类树、样式、地图定义、配图和方案列表单次顺序探测约6～22 ms，不能代表浏览器首屏P95。该轮方案列表探测未带 `summary=true`，完整definition是该接口的正常行为，不能据此判断摘要改动是否已加载。

执行计划确认使用了 `idx_gis_vector_feature_display` 空间索引。慢点之一是包围盒误命中后，仍对实际不相交的复杂几何执行简化和锚点计算。例如 `6/51/25` 包围盒命中的两条海洋要素合计520645个顶点；原查询约3817 ms，其中shapes阶段约3555 ms。精确相交预筛实验约827 ms，最终仍输出1327条MVT表示。

正式修复在 `GisVectorMapper.xml#dataSetTile`：z4及以上增加精确相交预筛，检查范围保留原缓冲、简化容差及量化余量；随后沿用原简化、真实边界、锚点、属性和编码流程。z0～z3保持原筛选逻辑。没有截断要素、修改分类规则、改变数据版本或放宽超时/体积限制。简化距离定义参见 [PostGIS官方说明](https://postgis.net/docs/ST_SimplifyPreserveTopology.html)。

以下是通过只读JDBC执行正式生产SQL的对照，绕过应用瓦片缓存；不是修复后的HTTP端到端结果：

| XYZ | 修改前 ms | 修改后 ms | MVT字节数 | 全字节一致 |
| --- | ---: | ---: | ---: | --- |
| 2/2/1 | 3104 | 9771 | 455623 | 是 |
| 3/4/2 | 2447 | 1609 | 268577 | 是 |
| 6/51/25 | 3877 | 1378 | 248658 | 是 |
| 6/52/25 | 5326 | 1714 | 482694 | 是 |

环境存在明显长尾波动，低层级未改执行逻辑仍出现9771 ms，不能将所有时间差归因于修复。无层级门槛的候选SQL在上述全部8块瓦片上完成全字节及SHA-256一致性校验；候选SQL的 `10/843/388` 为2810→359 ms、`12/3372/1552` 为1786→561 ms，不能代替正式版本复测。正式版本第二批验证因工具会话超时未取得结果，不计为通过。

已通过 `mvn -pl ocean-bootstrap -am compile -DskipTests`。测试使用内存脚本和只读JDBC，未新增测试类；未修改数据库结构，不涉及Flyway修复。保留两库原有修改，未提交代码。

未完成：IDE启动的8090进程尚未重启加载修复；正式版本受控并发HTTP的P50/P95和错误率对照；间歇性瓦片报错的失败状态及根因；平移缩放、分类/方案切换、纹理和标注预览的请求与绘制验收；GPKG真实工作存储负载验证。近期有限日志未捕获瓦片失败，不能宣布报错已解决。浏览器工具读取过一次页面状态，后续因无法可靠确认Windows浏览器URL而停止，本轮没有通过完整浏览器验收。本专项仍未完成。

#### 缩放报错与低层级响应排查（2026-11-05）

用户截图为上述第一套数据的制图工作区，显示层级Z2.3，提示“部分图层显示失败 服务暂时不可用，请稍后重试”。用户确认未保留失败请求。当天运行日志中的SQL已包含上一轮的精确相交预筛，说明该SQL修复已加载；但预筛仅作用于z4及以上，截图所在的世界/洲级视图没有获得这处优化。

**报错定位及修复边界：** 前端 `getVectorTile` 使用 `responseType: 'arraybuffer'`，JSON错误响应也会以ArrayBuffer进入公共异常处理。原 `normalizeRequestError` 只读取对象的 `message`，导致服务端的具体原因被统一替换为通用5xx提示。这是已复现并修复的错误信息丢失问题，不是对历史请求的根因认定。当前按响应Content-Type解析不超过64KiB的JSON二进制错误体，保留原HTTP状态、取消与超时语义。

后端 `VectorTileService#getTile` 增加5xx失败诊断，包含数据集、XYZ、阶段、耗时、状态、业务原因、SQLSTATE和根异常类型，不输出SQL参数或要素内容。阶段覆盖访问校验、缓存读取/复查、同瓦片等待、空间查询排队、版本校验、PostGIS查询编码、GPKG读取编码、体积校验、事务提交及缓存写入；查询编码超过1秒记录耗时。缓存读取和GPKG异常保留原始cause。随后针对下述浙江省瓦片的缓存链路问题，将Redis写入故障改为记录日志并保留有效结果。未调整查询并发上限、超时、体积或数据抽析规则。

核查09:22～09:25跨日志轮转的54次瓦片SQL，均有完成记录，单次SQL耗时约850～5551 ms；既有日志没有记录截图对应的受控503原因。当天更早的连接池失效连接告警及一次启动时Flyway校验失败不能直接关联到截图。因此历史失败究竟来自查询超时、排队还是缓存等环节仍未证实。

**有限响应抽样：** 同一数据修订47，直连 `127.0.0.1:8090`，最大并发4，不清空共享Redis、数据库和操作系统缓存。以下“未命中”仅指应用瓦片缓存，日志确认执行了空间查询；百分位按最近秩法计算，样本量不足以代表稳定SLA。

| 样本 | 数量 | 响应范围 | P50 | P95 | 错误率 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 应用缓存命中 | 4 | 30～47 ms | 35 ms | 47 ms | 0/4 |
| 应用缓存未命中 | 8 | 1200～10068 ms | 4839 ms | 10068 ms | 0/8 |

缓存命中XYZ为 `2/1/1`、`2/2/1`、`2/1/2`、`2/2/2`。未命中XYZ及耗时为 `2/3/1` 6286、`2/3/2` 8464、`1/0/0` 9262、`1/1/0` 10068、`0/0/0` 3099、`2/0/1` 2221、`2/0/2` 1200、`3/6/3` 4839 ms。通过前端5173代理重复读取已缓存瓦片也返回200，约13 ms；这不能排除历史代理故障。不同日期、瓦片和缓存条件不可用于计算优化收益。

**低层级执行计划：** 只读JDBC执行正式SQL的 `2/2/1`，`EXPLAIN (ANALYZE,BUFFERS,FORMAT JSON)` 总耗时2022 ms，shapes阶段1815 ms（约90%），符合当前主要成本集中在几何处理及相关属性构造的判断。该层级只有9个工作图层进入查询，shapes输出1510条，并非把全部100万要素传给浏览器。执行计划仍使用空间索引；有临时块读写，但本次没有证据证明它是首要瓶颈。低层级的大面仍逐瓦片执行简化、锚点计算和MVT表示构造，缓存过期或新视窗请求会重复承担此成本。

同一 `2/2/1` 的MVT解码后455623字节，经gzip传输，包含2023条显示表示、1505个不同原始要素ID。使用项目实际OpenLayers MVT解析器在独立Node进程中连续解码5次，为16.3、8.7、5.9、4.6、4.9 ms；该次HTTP为3007 ms。这里只验证解析开销，不能代表浏览器绘制、纹理、文字避让或交互帧率。浏览器自动化读取页面日志超时，本轮未完成交互帧率验收。

**性能判断与后续顺序：** 当前实测瓶颈首先是应用缓存未命中后的PostGIS处理。服务端最多4个并发空间查询，排队和SQL各有10秒预算；前端瓦片HTTP并发4、超时35秒。连续缩放时，已开始的旧视窗请求仍占用执行槽位，视窗变化只重排尚未开始的请求；这可能放大等待，但尚未采集到浏览器时序来量化。HTTP总耗时接近或超过10秒不等于数据库已超时，必须结合SQLSTATE和失败阶段判断。优先研究低层级复杂面重复简化/锚点计算的减少，再测视窗切换时的队列利用率，最后按浏览器性能记录评估绘制开销；单纯增加超时或并发不能视为问题已解决。预计算显示几何或低层级预生成涉及额外设计、存储和失效策略，本轮仅提出方向，未实施。

验证：后端完整模块链编译通过；8项内存回归通过，覆盖缓存读写、SQL超时、队列满、排队超时、GPKG cause、成功缓存命中、慢查询诊断，并校验失败后可重试及查询许可/在途请求释放。前端9项内存回归通过，覆盖JSON二进制错误、带charset和+json类型、普通对象、非JSON、畸形/超大响应、超时和取消；全仓lint及地理信息应用类型检查/构建通过。未新增测试文件、修改数据库结构或提交代码。

本轮新增后端诊断需要重启服务加载；前端需要加载更新后的代码。尚未取得截图那次失败的URL/状态/响应，也未复现新的5xx，不能宣称缩放报错根因已查清或响应性能已全面解决。

#### 浙江省指定503瓦片的追查与缓存修复（2026-11-05）

用户随后提供另一数据集“浙江省矢量数据”的失败请求：数据集 `2113675916158918657`，修订9，tileVersion `a3abf36d479217183329b4710ae0fad5ec0112097fa5946c110f5383b4be74e5`，XYZ `10/854/421`。浏览器记录HTTP 503，响应为 `{"code":503,"message":"空间查询超时或存储服务暂不可用，请稍后重试","data":null}`；总耗时11.06秒，其中等待服务端11.05秒，浏览器排队1.56毫秒，下载错误响应2.61毫秒。此请求不同于上面的世界级测试数据样本。

关联同一XYZ和数据版本的证据（时间均为北京时间）：

| 环节 | 实测证据 |
| --- | --- |
| PostGIS | 09:48:44.647开始，09:48:47.415完成，耗时2768 ms，有正常结果完成记录 |
| Redis缓存写入 | SLOWLOG ID 215于09:48:55执行相同完整瓦片缓存键的EVALSHA，Redis内部执行20.597 ms |
| 后续重放 | 通过5173代理返回200，398 ms；直连8090再次返回200，192 ms，均为缓存读取样本 |
| 真实缓存方法复测 | 独立Java进程使用项目实际cache方法、同一MVT和3秒Redis超时写入成功，748 ms；未复现底层异常 |

这说明该次空间查询已完成，长等待集中在SQL完成之后；缓存写入链路是首要故障方向，不能将通用响应文案直接解释为SQL超过10秒。现有代码将Redis写入放在空间查询事务内，写入抛出的DataAccessException同样会被转成这条“空间查询超时或存储服务暂不可用”消息。Redis命令已在服务端执行，也不保证客户端在超时前收到确认。旧进程未加载新增异常阶段日志，因此无法进一步确认当时底层异常是否为Redis命令超时，不能把推断当作已捕获的异常堆栈。

额外核查：故障前后30秒的PostgreSQL和Redis容器日志无异常记录；数据库的 `idle_in_transaction_session_timeout`、`idle_session_timeout` 和默认 `statement_timeout` 均为0，瓦片查询自身仍设置局部10秒超时。Redis未配置maxmemory上限，evicted_keys为0，没有本次内存淘汰证据。这里不能排除瞬时客户端、连接或运行环境延迟。

**已修复的确定性缺陷：** 原代码会在已生成、已验证的瓦片写入Redis失败时返回503，同时缓存等待继续占用数据库连接和空间查询执行名额。现在先完成只读事务并释放查询名额，再将有效瓦片放入有容量/TTL限制的本地缓存并尝试Redis写入；Redis写入的DataAccessException只记录根异常类型和耗时，仍返回有效MVT。事务提交失败则不写入缓存并继续返回失败。共享缓存读取失败仍沿用原有503行为，未扩大为全部缓存异常忽略。Redis写入仍为同步调用，因此故障时仍可能承担一次现有超时等待；本次修复不代表冷请求已达到低延迟目标。

**该瓦片的数据成本：** 未压缩MVT为5237113字节，gzip为1260289字节；Redis现有Base64值为6982820字节。包含42197个不同原始要素、50755条显示表示（4436填充、4436边界、37761普通几何、4122标注）。项目实际OpenLayers解析器在独立Node进程中解析3次为71.5、66.5、50.4 ms，尚不包含浏览器样式计算和绘制。说明中等层级的高密度矢量除服务端生成外，还有大值缓存传输和主线程解析成本；不能沿用前面小瓦片5～16 ms的解析结论。

后端模块链重新编译通过，9项内存回归全部通过。缓存故障用例验证返回字节、本地缓存命中、数据库事务已提交及查询许可已释放；新增事务提交失败用例验证错误仍被传播且不缓存结果。其余覆盖SQL超时、队列满/等待超时、缓存读取失败、GPKG失败、成功缓存命中、慢查询诊断及失败后资源清理。未新增测试文件或修改数据库结构，尚未重启用户IDE中的后端进程，运行中接口的修复后故障注入及浏览器缩放验收仍待加载新代码后完成。

#### 业务层级与亮暗主题规则校正（2026-11-05）

业务显示层级统一以 Web Mercator 256 像素为基准，512 像素仍是实际 XYZ 瓦片大小。业务 z4 通常读取原生 z3；原生最高级保持18，业务显示最高级24。tilePolicy 的 zoomReferenceSize 固定256，groups.minZoom 是业务层级；PostGIS 和 GPKG 编码在入选筛选时转换，几何简化、瓦片编号、extent 和投影保持原定义。最高原生瓦片包含后续放大显示需要的要素，前端按业务规则控制显隐。

x院亮色/暗色模板各有35类业务样式、116条复合规则。每条规则保留SLD比例尺分母并显式给出 [minZoom,maxZoom)；舍入误差在SLD记录精度内的整数层级归一化，9000001等实际非整数边界保留连续缩放值。样式起止层级取复合规则的覆盖范围，瓦片最早业务层级向下取整以覆盖小数边界；编译校验三者一致，防止再次出现省级面z3、绿地z9的错误摘要。省级面z4、绿地z10、建筑z13/z15分段，绿地及自然名称 [15,21)。编辑器直接展示不含结束值的边界，预览和保存均使用复合规则，仅对用户修改字段叠加补丁。

SLD核对来源为 E:/web service/geoserver-3.0.1-bin/configuration/ocean_basemap/styles，与该目录的配置说明、generate.mjs及此前GeoServer配置对话交叉核对。232条规则的比例尺、CSS符号参数及过滤结构已核验；国界69885283.003按清单修正为69885283.004。该SLD本身包含先前按清单补齐的规则，并非另获的完整原始权威版本。保留已记录的道路插值、12个重点城市、dtype/ctype/kind映射及重点城市点描边1px；大型水体mj>=500000的单位仍未提供，未擅自更改。kind字段在源要素中为字符串，因此JSON保留字符串字面量。

本地开发库9份数据集快照与2份自建方案已经事务升级，修改前保存 target/zoom-contract-backup.json，以版本和原JSON校验防止覆盖并发改动。样式版本、显示修订及瓦片编码版本更新，原始数据修订不变；测试SHP仍为46个工作图层、1006647个要素。自建方案颜色、线宽、要素补丁与复合过滤条件保持原值。新模板不自动覆盖既有快照；此次为受控一次性迁移，不加入历史协议双读代码。无现存静态矢量任务/非源文件矢量成果需要迁移。静态任务输入亦使用业务层级0至19，产出目录和TileJSON使用原生XYZ级别。

验证采用stdin内存脚本，未新增测试类。前后端构建通过，前端lint通过；协议编译、连续边界、无效摘要拒绝、GPKG共用编码器和静态编号换算通过；真实GPKG动态读取、静态生成及最高原生级放大保留要素验证通过。默认/预览/保存共795组渲染对照通过。8090已加载新构建，实际PostGIS瓦片在业务z3无省级面、z4开始出现；台湾实际绿地样本在z9为0条显示表示、z10为289条。该验证覆盖规则与瓦片，未代替浏览器文字避让、纹理绘制及交互帧率的视觉验收。
