# 数据库设计
ocean platform 模式
sys_user        用户信息表
sys_role        角色信息表
sys_permission  权限资源表
sys_platform    平台信息表

sys_user_platform       用户的平台准入授权
sys_user_role           用户角色关系
sys_role_permission     角色权限关系

sys_security_policy     密码、锁定、会话安全策略
sys_user_lock_record    锁定记录
sys_login_log   系统登录日志
sys_operation_log   系统操作日志
sys_exception_log   系统异常日志

ocean gis 模式
gis_data_set    数据集表
gis_file_group  逻辑文件组、装配与解析状态、输入修订及规范内容指纹
gis_file_meta   物理文件元数据及独占组成员关系
gis_file_spatial_metadata  文件各图层空间元数据（源 CRS、范围、分辨率、推荐层级）
gis_vector_dataset_style 数据集创建时的不可变默认样式快照及数据、显示修订号
gis_vector_group        数据集私有业务分类树、显隐开关和默认样式业务键
gis_vector_layer        可编辑矢量图层及当前要素数量、范围、数据版本
gis_vector_feature      逐要素真实几何、JSONB属性与编辑版本
gis_vector_style        图层样式方案、默认选择及独立版本，规则定义使用JSONB
gis_processing_task     静态瓦片处理任务及完整参数快照
gis_processing_input    处理输入快照（上传、受控工作空间、已管理文件）
gis_tile_set            一次处理任务唯一生成的静态瓦片集


gis_publication             静态瓦片集统一发布记录（地形、影像、矢量）

GIS 数据库依赖 public Schema 中的 PostGIS 扩展。`gis_file_spatial_metadata.geographic_extent` 使用 SRID 4326 的 geometry，并建立 GiST 索引；同一文件与图层键唯一，物理删除文件时级联删除。原始栅格及矢量文件不存入 PostGIS。

`gis_file_group` 是业务管理和解析单位，单个独立文件也建立组；数据集包含多个组，`gis_file_meta.file_group_id` 是物理文件的唯一归属，尚不能可靠识别时允许为空。`member_path` 保留可信组内相对路径，`(file_group_id,lower(member_path))` 对有效成员唯一，复合外键约束成员与组属于同一数据集，主入口必须是本组成员。`gis_vector_group` 仍是矢量业务分类树，不承担文件组职责。

组的 `assembly_status` 为 COMPLETE、INCOMPLETE、UNKNOWN、BLOCKED 或 DUPLICATE；`parse_status` 保存 PARKED、QUEUED、RUNNING、COMPLETED、FAILED、NOT_APPLICABLE 或 REIMPORT_REQUIRED。`entry_file_id` 标识唯一解析入口，只有完整组可排队或执行。`read_options_json` 使用 TEXT 保存 sourceCrs、xField、yField 等读取配置。`input_revision` 仅随有效成员或读取参数变化递增，和入口文件的领取版本共同阻止过期提交；已成功成果发生输入变化时等待显式重导，不自动覆盖 READY 图层。

`fingerprint` 由格式、规范化成员清单和成员 SHA-256 计算，同一数据集的有效非空指纹由部分唯一索引保护；不包含 UUID、接入批次、绝对路径或读取参数。重复输入保留在独占追溯组，记录同数据集内 `duplicate_of_group_id` 及匹配时的 `matched_revision`，自身不占规范指纹；不会把新成员迁入规范组，也不删除源文件。补件后发生指纹碰撞同样标记 DUPLICATE，规范组既有成功或失败状态保持不变。源不可用或变化时清除失效指纹，不能继续以旧摘要接受去重命中。

矢量内容提取是文件组元数据处理的一部分。原始主文件及配件保留在文件存储或工作区，`gis_vector_feature.geom` 保存 SRID 4326 的真实几何并保留 XY/XYZ，建立 GiST 索引；properties 使用 JSONB 保留属性数据类型。来源图层和空间元数据均关联组主入口，图层与要素以 layer_id 关联；编辑不回写来源文件，不改变文件覆盖范围和文件统计口径。矢量瓦片直接从 `gis_vector_feature` 按业务与像素尺寸保形筛选、简化和裁剪，数据库不保存派生概化几何或其构建任务。

GeoJSON、GeoTIFF 复用既有 Java 解析链路；SHP、DXF、MIF、TAB、CSV、GPX、DWG、IMG、JPG/JPEG、PNG、GIF、GRIB2 通过独立 Java 工作进程中的 GDAL/OGR Java API 读取与转换，数据库保存源 CRS、范围和来源图层信息，不保存 JNI 对象、工作进程状态或临时转换文件。实际驱动或 DWG 文件版本不受支持时明确阻塞。CSV 必须有源 CRS 和 X/Y 字段，CAD 需明确源 CRS；无 CRS、无定位的栅格不写入虚构空间范围。GDAL Java JAR、JNI 桥接库及原生核心库使用配套的 3.13.0 版本并运行于应用工作进程，数据库安装 PostGIS 不能替代应用侧的原生运行库；本次统一读取方式不调整数据库结构。

GeoJSON 文件顶层受支持的旧式 WGS84 命名 CRS 通过共用规则校验，原始名称记录在 `gis_file_spatial_metadata.extra_metadata.declaredCrs`；source_crs_code 统一为 OGC:CRS84。坐标数值与经纬度顺序保持不变，真实要素继续以 SRID4326 存储，无需修改表结构。在线编辑及空间响应不携带旧式 crs 声明。

`gis_file_meta.metadata_version` 与15分钟租约阻止过期执行提交。创建矢量数据集（category_id=2）时，在同一事务将 `vector/style.json` 复制为 `gis_vector_dataset_style.definition`，并生成数据集私有的 `gis_vector_group` 分类树。快照协议为 schemaVersion=2，后台文件后续变化不影响已有数据集。运行时分类 ID 是数据库 ID；group_key、default_style_key 分别对应快照中的 groups[].id、styles[].id，不能相互替代。

文件登记时固定 `vector_placement`（AUTO 或 UNKNOWN）和 `vector_dataset_style_id`，通过复合外键限制快照属于同一数据集。逐要素匹配分类；GeometryCollection 按递归分量拆分，MultiPoint、MultiLineString、MultiPolygon 保持整体。一个文件组按来源图层、初始分类业务键与几何类别生成多个业务图层，`(source_file_id,layer_key)` 唯一。`source_feature_index`、`source_geometry_path`、`source_feature_id`、`classification_rule_key` 保存来源引用；原始 properties 不被归一化结果覆盖。分批写入的数据仅在文件组整体提取完成、所有图层切为 READY 后可查询和编辑；失败时清理未完成图层及其要素。成功图层不重复导入。

要素更新通过 version 校验，服务层在图层行锁内执行修改，同事务重算数量、范围和几何类型并递增 data_version。最后一个要素删除后范围为空。图层逻辑删除后所有要素退出查询并解除来源文件引用；未删除图层会阻止来源文件删除或变更。

`gis_vector_layer.group_id` 和 `original_group_id` 通过复合外键关联同一数据集内的分类；手动移组只改变前者，保持 layer_key、来源引用和要素属性不变，不合并图层或重读文件。assignment_mode 为 AUTO/MANUAL，style_mode 为 UNCONFIGURED/INHERIT_TARGET_GROUP/CUSTOM。未知层默认 enabled=false 且 UNCONFIGURED，显式配置后方可显示；数据库禁止启用未配置图层。手动移组、配色、级别和开关由 presentation 接口原子保存，递增 presentation_version 和数据集 display_revision。

`gis_vector_style.layer_id` 外键关联矢量图层，物理删除图层时级联删除方案，(layer_id,name) 唯一。is_default 的部分唯一索引保证每层至多一个选中方案，禁止直接删除选中方案。新导入的已识别图层直接继承分类样式，不再生成通用蓝色默认方案。自定义图层方案的 definition 仍为 schemaVersion=1 和 rules 数组，由 `VectorStyleRules` 校验；这是独立的图层样式领域，不是对 schemaVersion=2 数据集快照的兼容转换。非选中方案修改不影响显示；CUSTOM 模式下选中方案修改或切换，会更新图层 presentation_version 和数据集 display_revision，不修改原始要素或 layer.data_version。

数据集 data_revision 用于要素新增、修改、删除以及导入完成；display_revision 用于分类开关、图层挂载、显示样式等变化。地图渲染在 Redis 保存带 bbox、连续 displayZoom、CSS 宽高、版本、规则和预算的视窗上下文，MVT 查询必须引用该上下文。整个视窗的所有启用图层共用 `CLAMP(FLOOR(width*height/800),32,600)` 的逻辑显示对象预算，按属性与级别过滤后进行像素过滤和显示用简化；点、线、面均不网格聚合，点与行政边界受保护，其余线面按命中规则开始级别和可见屏幕尺寸筛选，等值桶不截断。上下文记录候选数、保留数及冻结的筛选阈值。面填充、源真实边界和唯一锚点标注分别编码为显示部件，预算仅计一次源分量。原始几何不回写，不新增概化表或离线概化任务。过期上下文返回410，版本变化返回409；不存在全量原始几何回退。属性分页必须有 bbox 且不返回几何，完整几何仅通过单要素编辑详情读取。

`gis_file_meta.metadata_status` 为 WAITING、PARKED、QUEUED、RUNNING、COMPLETED、FAILED 或 NOT_APPLICABLE。WAITING 等待整批上传或扫描登记结束，持有等待租约；收口组装后只有有效完整组的主入口转为 QUEUED。PARKED 持久保存配套文件、缺件、未识别或能力阻塞输入，不占执行租约，不单独领取解析。等待租约过期标记 INGESTION_INTERRUPTED，保留文件；metadata_version 和解析租约隔离过期执行，最终结果还须核对组 input_revision。ingest_progress_id 与 ingest_file_index 仅关联当前执行和进度，结束后清空，不提供接入历史查询；ingest_outcome 记录 REGISTERED 或 DUPLICATE，独立于组的解析状态。

接入进度查询按物理文件关联组结果归集，FILE 口径的 summary 区分成功、受影响失败及跳过解析；重复与已接收未解析输入允许本次接收结束，不计为解析成功。去重后的组指标使用 FILE_GROUP，分别统计关联组、完成、失败、重复和等待数，不能用配件文件数替代逻辑数据源数量。进度不新增批次历史表。

`gis_data_set.file_count` 与 `total_size_bytes` 采用相同口径：分别统计当前 `file_status = 'READY'` 的管理文件数量与大小总和（字节），包含上传文件、工作区原位引用文件、配套文件、保留的重复输入及空间元数据提取失败的文件，排除分片、成果和缓存。两者由文件登记、新增、修改和永久删除事务原子维护，仍是物理文件口径；默认逻辑组列表排除 DUPLICATE。大小不代表磁盘实际占用量。

`gis_file_meta.source_type` 区分 `UPLOAD/WORKSPACE`。上传来源使用 `storage_type/storage_key/storage_name`，以 UUID 平铺永久存储；工作区来源使用 `workspace_code/relative_path/source_modified_at`，两组字段互斥，工作区不保存服务器绝对路径。`source_modified_at` 为Unix毫秒，与 `size_bytes` 共同验证源快照；必要的内容摘要在组装事务外计算，锁内重验来源属性和归属。工作区定位按 `(data_set_id, workspace_code, relative_path)` 对未删除记录唯一；删除文件、组或数据集时保留工作区原文件。文件登记人和源状态统一命名为 `created_by/file_status`。

两种接入方式均不建立上传下载历史表；操作留痕由平台操作日志保存。处理任务使用工作区管理文件时，gis_processing_input 继续保存工作区定位和源文件快照。

数据集分页自动返回 `filePoints`，批量读取 `gis_file_meta.representative_longitude`、`representative_latitude`、`representative_layer_key`、`representative_extent_kind`，每个可定位文件一个点，不查询或计算覆盖几何。代表点与 COMPLETED 状态在同一元数据提交事务内保存；数据库约束要求 COMPLETED 文件具有完整、合法的点位和来源信息，其他状态的点位必须为空。失效、分类变化与任务重新领取会清空点位；文件永久删除后点位随元数据一并删除。

`MANAGED_FILE` 当前接收已完整解析栅格组的主入口，用于影像或地形切片。`gis_processing_input` 同时保存可空的实时 `file_meta_id`、来源摘要及 `source_file_group_id/source_group_revision/source_group_snapshot`；snapshot 为 TEXT 格式的完整组与成员 JSON，包含读取参数、所有成员定位、大小、修改时间和 SHA-256。活动实时引用要求组快照字段齐全；任务终态解除实时外键，完整快照继续保留，不为已解除引用的历史任务伪造组信息。

QUEUED、RUNNING 处理任务保护整个来源组及所有配件，任务提交核对组修订与成员清单未变化。执行只读取保存的快照，核验各源摘要并经统一读取器准备临时 GeoTIFF；不重新查询当前成员代替提交快照。任务进入 COMPLETED、PARTIAL_FAILED、FAILED 后解除实时外键，任务查询继续使用来源快照。应用内删除不会主动破坏活动任务，外部文件丢失或内容变化由任务记录明确失败原因。

文件、文件组与数据集管理接口执行永久删除。整组删除检查全部成员的任务及矢量引用；物理文件接口删除单成员时递增组修订并清除旧指纹，使缺件组退出可解析状态。LOCAL文件先原子移动到上传根目录下的事务待删除区，数据库提交后清理，回滚时恢复原路径；工作区源文件始终保留。数据集删除级联清理文件组、文件元数据、空间元数据以及数据集自有矢量数据；存在未结束接入、空间元数据提取或处理任务时拒绝删除。已发布影像/地形服务只依赖独立瓦片集，删除原始数据集时发布记录的 `data_set_id` 自动置空，服务继续可用。

代表点从文件所有图层的覆盖分块中选择：优先取最大面积面的内部点，否则取最长线的中点，否则使用点本身。面积、长度通过 geography 按平方米、米比较；同值按图层键（C排序）及分块序号选择。多区域、孔洞及跨日期变更线的面分块保持独立，不对经纬度直接平均。GeoJSON 当前只有包围范围，来源类型保留为 VECTOR_ENVELOPE，不能承诺点在真实要素内。

单数据集地图接口 `GET /api/gis/dataSet/{id}/footprints` 一次返回该数据集所有已有可用轮廓及文件摘要。范围为 EPSG:4326 GeoJSON，保留 RASTER_FOOTPRINT 与 VECTOR_ENVELOPE 的区别。`simplifyTolerance` 默认0.001度，允许0至1的有限数值，0返回完整几何，大于0应用拓扑保持简化。该接口的 `filePoints` 为 null；普通数据集详情仍只返回摘要。列表与详情查询使用可重复读快照。

## 2026-10-22 开发库矢量升级

已实际升级本地 `localhost:5432/ocean_admin` 的 `ocean_gis` Schema，与当前 V2 脚本的矢量结构一致；不是仅修复 Flyway 校验和。按本次授权清除了历史矢量数据集1个、图层2个、要素373430条、图层样式2份、文件元数据及空间记录各2个、上传批次和明细各2个，包含逻辑删除数据。未保留旧协议分支或自动迁移历史图层，新数据须重新创建数据集并导入。

升级在单事务中提交；提交前比较了影像、地形及其任务、成果、归档、发布等10类非矢量数据的行数与内容哈希，均保持不变。仅清理数据库记录，历史上传副本和 `E:\data\vector\国内POI数据` 原始采集目录未物理删除。联调用临时数据集、测试上传副本和测试渲染上下文在验证后清理。

清理前 GIS Schema 的 PostgreSQL custom-format 备份存于 `deploy/docker/backups/ocean-gis-before-vector-upgrade-20261022.dump`（被 Git 忽略），可供恢复评估。备份校验 SHA-256 为 `009A41EC386AD0C50147763887C91D8878AC1D6B0E2C734B35CE721A6BE9F6F1`，大小74600515字节；不要将备份当作初始化 SQL 或在当前库直接无条件整库还原。过渡 SQL 仅临时执行，最终结构统一维护于 `db/migration/V2__init_ocean_gis.sql`。
