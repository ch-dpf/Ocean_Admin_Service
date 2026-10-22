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
gis_file_meta   文件元数据表
gis_file_spatial_metadata  文件各图层空间元数据（源 CRS、范围、分辨率、推荐层级）
gis_vector_layer        可编辑矢量图层及当前要素数量、范围、数据版本
gis_vector_feature      逐要素真实几何、JSONB属性与编辑版本
gis_vector_style        图层样式方案、默认选择及独立版本，规则定义使用JSONB
gis_processing_task     静态瓦片处理任务及完整参数快照
gis_processing_input    处理输入快照（上传、受控工作空间、已管理文件）
gis_tile_set            一次处理任务唯一生成的静态瓦片集


gis_publication             静态瓦片集统一发布记录（地形、影像、矢量）

GIS 数据库依赖 public Schema 中的 PostGIS 扩展。`gis_file_spatial_metadata.geographic_extent` 使用 SRID 4326 的 geometry，并建立 GiST 索引；同一文件与图层键唯一，物理删除文件时级联删除。原始栅格及矢量文件不存入 PostGIS。

矢量内容提取是文件元数据处理的一部分。原始 GeoJSON 保留在文件存储，`gis_vector_feature.geom` 保存 SRID 4326 的真实几何并保留 XY/XYZ，建立 GiST 索引；properties 使用 JSONB 保留属性数据类型。文件与图层以 source_file_id 关联，图层与要素以 layer_id 关联；编辑不回写来源文件，不改变文件覆盖范围和文件统计口径。矢量瓦片直接从 `gis_vector_feature` 动态裁剪、简化和聚合，数据库不保存派生概化几何或其构建任务。

GeoJSON 文件顶层受支持的旧式 WGS84 命名 CRS 通过共用规则校验，原始名称记录在 `gis_file_spatial_metadata.extra_metadata.declaredCrs`；source_crs_code 统一为 OGC:CRS84。坐标数值与经纬度顺序保持不变，真实要素继续以 SRID4326 存储，无需修改表结构。在线编辑及空间响应不携带旧式 crs 声明。

`gis_file_meta.metadata_version` 与15分钟租约阻止过期执行提交。一个源文件允许生成多个图层，首期只生成 `layer_key=default` 的一个图层，`(source_file_id,layer_key)` 唯一。分批写入的数据仅在文件提取整体完成、图层切为 READY 后可查询和编辑；失败时清理未完成图层及其要素。成功图层不会被元数据重试覆盖。

要素更新通过 version 校验，服务层在图层行锁内执行修改，同事务重算数量、范围和几何类型并递增 data_version。最后一个要素删除后范围为空。图层逻辑删除后所有要素退出查询并解除来源文件引用；未删除图层会阻止来源文件删除或变更。

`gis_vector_style.layer_id` 外键关联矢量图层，物理删除图层时级联删除方案，(layer_id,name) 唯一。is_default 的部分唯一索引保证每层至多一个默认方案；业务在新图层事务内创建默认方案并禁止直接删除默认方案，切换时原方案与目标方案在同一事务更新。definition须为含 schemaVersion=1 和rules数组的JSONB对象，详细条件和符号约束由 `VectorStyleRules` 校验。样式 version 独立于 feature.version 和 layer.data_version，修改定义或默认选择均不影响要素数据、统计范围及游标有效性。

`gis_file_meta.metadata_status` 为 QUEUED、RUNNING、COMPLETED、FAILED 或 NOT_APPLICABLE。metadata_version 和 metadata_lease_expires_at 用于领取与过期执行隔离。空间元数据、矢量图层 READY 与文件 COMPLETED 状态在同一事务提交；原上传批次的成功计数也在此后更新。数据集文件数不随提取状态变化；图层可用性以 READY 状态为准。

`gis_data_set.file_count` 与 `total_size_bytes` 采用相同口径：分别统计未删除且 `upload_status = 'READY'` 的管理文件数量与大小总和（字节），包含配套文件及空间元数据提取失败的文件，排除回收站、分片、成果和缓存。两者由文件入库、新增、修改、逻辑删除和恢复事务原子维护；元数据重试及回收站彻底删除不重复增减。大小不代表磁盘实际占用量。

数据集分页自动返回 `filePoints`，批量读取 `gis_file_meta.representative_longitude`、`representative_latitude`、`representative_layer_key`、`representative_extent_kind`，每个可定位文件一个点，不查询或计算覆盖几何。代表点与 COMPLETED 状态在同一元数据提交事务内保存；数据库约束要求 COMPLETED 文件具有完整、合法的点位和来源信息，其他状态的点位必须为空。失效、分类变化与任务重新领取会清空点位；逻辑删除保留点位但查询过滤，恢复后按元数据状态展示。

代表点从文件所有图层的覆盖分块中选择：优先取最大面积面的内部点，否则取最长线的中点，否则使用点本身。面积、长度通过 geography 按平方米、米比较；同值按图层键（C排序）及分块序号选择。多区域、孔洞及跨日期变更线的面分块保持独立，不对经纬度直接平均。GeoJSON 当前只有包围范围，来源类型保留为 VECTOR_ENVELOPE，不能承诺点在真实要素内。

单数据集地图接口 `GET /api/gis/dataSet/{id}/footprints` 一次返回该数据集所有已有可用轮廓及文件摘要。范围为 EPSG:4326 GeoJSON，保留 RASTER_FOOTPRINT 与 VECTOR_ENVELOPE 的区别。`simplifyTolerance` 默认0.001度，允许0至1的有限数值，0返回完整几何，大于0应用拓扑保持简化。该接口的 `filePoints` 为 null；普通数据集详情仍只返回摘要。列表与详情查询使用可重复读快照。
