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
gis_processing_task     静态瓦片处理任务及完整参数快照
gis_processing_input    处理输入快照（上传、受控工作空间、已管理文件）
gis_tile_set            一次处理任务唯一生成的静态瓦片集


gis_publication             静态瓦片集统一发布记录（地形、影像、矢量）

GIS 数据库依赖 public Schema 中的 PostGIS 扩展。`gis_file_spatial_metadata.geographic_extent` 使用 SRID 4326 的 geometry，并建立 GiST 索引；同一文件与图层键唯一，物理删除文件时级联删除。原始栅格及矢量文件不存入 PostGIS。

`gis_file_meta.metadata_status` 为 QUEUED、RUNNING、COMPLETED、FAILED 或 NOT_APPLICABLE。metadata_version 和 metadata_lease_expires_at 用于领取与过期执行隔离。文件完整上传并不等于导入成功；空间元数据、文件 COMPLETED 状态、导入成功计数及数据集可用文件数量在同一事务中提交。

`gis_data_set.total_size_bytes` 为未删除且 `upload_status = 'READY'` 的管理文件大小总和（字节），包含配套文件及空间元数据提取失败的文件，排除回收站、分片、成果和缓存。由文件入库、新增、修改、逻辑删除和恢复事务原子维护，元数据重试不改变大小；回收站彻底删除不重复扣减。该字段与仅统计元数据完成文件的 `file_count` 口径不同，不能作为磁盘实际占用量使用。

数据集分页自动返回 `filePoints`，批量读取 `gis_file_meta.representative_longitude`、`representative_latitude`、`representative_layer_key`、`representative_extent_kind`，每个可定位文件一个点，不查询或计算覆盖几何。代表点与 COMPLETED 状态在同一元数据提交事务内保存；数据库约束要求 COMPLETED 文件具有完整、合法的点位和来源信息，其他状态的点位必须为空。失效、分类变化与任务重新领取会清空点位；逻辑删除保留点位但查询过滤，恢复后按元数据状态展示。

代表点从文件所有图层的覆盖分块中选择：优先取最大面积面的内部点，否则取最长线的中点，否则使用点本身。面积、长度通过 geography 按平方米、米比较；同值按图层键（C排序）及分块序号选择。多区域、孔洞及跨日期变更线的面分块保持独立，不对经纬度直接平均。GeoJSON 当前只有包围范围，来源类型保留为 VECTOR_ENVELOPE，不能承诺点在真实要素内。

单数据集地图接口 `GET /api/gis/dataSet/{id}/footprints` 一次返回该数据集所有已有可用轮廓及文件摘要。范围为 EPSG:4326 GeoJSON，保留 RASTER_FOOTPRINT 与 VECTOR_ENVELOPE 的区别。`simplifyTolerance` 默认0.001度，允许0至1的有限数值，0返回完整几何，大于0应用拓扑保持简化。该接口的 `filePoints` 为 null；普通数据集详情仍只返回摘要。列表与详情查询使用可重复读快照。
