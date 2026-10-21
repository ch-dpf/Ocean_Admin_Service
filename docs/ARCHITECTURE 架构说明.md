# Ocean Admin Service 架构说明

## 技术基线

- Java 21
- Spring Boot 4
- Spring WebSocket
- MyBatis-Plus
- JWT（登录鉴权）
- PostgreSQL （数据库服务）
- Flyway （数据库迁移）
- Redis （缓存中间件）
- Knief4j （接口文档）

## 当前边界

当前只建设三个平台侧领域：

1. `identity`：用户、平台、角色、权限、认证和会话。
2. `audit`：登录、操作、安全和异常审计。
3. `workbench`：通用平台指标与业务卡片聚合。

三个领域位于同一个 `ocean-platform-core` Maven 模块，使用包边界隔离，避免初期过度拆分。

## 未来业务子平台

新的业务子平台添加为 `ocean-business-<code>` Maven 模块，例如：

```text
ocean-business-vision
ocean-business-analyst
ocean-business-cloud
```

业务模块只能通过 `org.ocean.admin.platform.api` 使用平台能力。禁止直接访问平台模块的实体、Mapper 和数据表。

## 数据所有权

平台业务使用 `ocean_platform` Schema，平台核心表采用 `sys_` 前缀；GIS 业务使用 `ocean_gis` Schema，表采用 `gis_` 前缀。两个 Schema 分别维护唯一的初始化 SQL，业务对象不跨 Schema 混写。

GIS 文件传输与空间元数据提取分阶段执行。原始文件保存在文件存储，PostGIS 保存图层范围及源空间参考；普通文件在元数据完成后计入导入成功，矢量数据集的 GeoJSON 自动继续生成可编辑图层。后台任务使用数据库待办、版本与租约恢复，查询通过空间索引在同一图层上组合矩形与推荐层级条件。

可编辑矢量业务位于 `ocean-business-geo`。`VectorImportService` 从已上传 GeoJSON 流式读取并分批写入真实要素，`VectorImportWorker` 通过独立线程池和数据库租约执行任务；仅整次导入成功后开放图层。导入任务、图层与要素独立建模，首期 SINGLE 模式为一文件生成一个图层，关系允许后续扩展多个图层。现有静态瓦片处理与发布流程继续负责静态成果。

`FileSpatialMetadataService` 在元数据提交事务中调用 `VectorImportService.enqueueUploadedSource` 创建任务并关联原上传明细，提交即具备可恢复的入库待办。矢量任务终态、图层 READY 和原明细成功状态在同一事务提交。`FileTransferRecordService` 汇总批次并在提交后推送统一进度；`FileUploadService.retryImport` 根据失败阶段重试，复用原文件、任务和图层，跳过成功文件。批次详情提供矢量任务号、状态及图层 ID 数组。已管理文件的显式入库继续复用相同矢量执行能力。

`VectorLayerService` 提供真实几何范围查询及要素编辑，以图层行锁维护数量、范围和数据版本，以要素版本及 HTTP If-Match 防止编辑覆盖。来源文件保持为导入时的数据依据；制图查询访问当前要素，编辑不回写原文件。空间响应使用 GeoJSON，图层及任务管理响应使用统一 ResponseResult。

旧式 GeoJSON 的兼容限制在文件入口：`FileSpatialMetadataService` 与文件要素读取共用 `GeoJsonFeatureReader.readSourceCrs`，仅接受顶层明确的 WGS84 命名声明，坐标不转换。子级声明、未知坐标系、空声明和链接型声明拒绝导入；在线编辑继续遵循 RFC 7946。该支持作为长期导入能力保留，停用这些来源并转换存量文件后可移除。

## 安全约束
