# 统一多协议 GIS 服务发布 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 删除独立 OGC 服务菜单与 `gis_ogc_service` 体系，把发布入口收拢到数据处理任务行，用一条 `gis_publication` 记录声明多个协议，并按原始数据类别进入影像/矢量发布列表。

**Architecture:** 后端保留 `gis_publication` 现有字段及类型，显式新增多协议和 OGC 元数据字段；统一发布目录查询该表并按 `protocols` 向 WMS/WMTS/TMS/WFS/XYZ 暴露图层。前端删除独立 OGC 页面引用，数据处理任务行打开两步发布向导，发布列表展示支持协议和各协议地址。

**Tech Stack:** Java 21 / Spring Boot 4 / MyBatis-Plus / PostgreSQL / Flyway / Jackson 3 / React 19 / TypeScript / Ant Design / Vite / oxlint / Prettier / Maven。

---

## 已确认约束

- 不做旧数据迁移；旧 `gis_ogc_service`、旧 `gis_publication` 发布记录全部无效。
- 不保留旧 `/api/gis/ogc-services/...` 接口。
- 不保留运行时双读/兼容分支。
- 不修改 `gis_publication` 现有字段类型、名称、CHECK 语义；旧 `service_protocol` 仍存主协议。
- 显式新增字段，不把所有元数据塞进一个大 JSON 字段。
- 一条发布记录可声明多个协议：
  - 影像：`WMS / WMTS / TMS / XYZ`
  - 矢量：`WMS / WMTS / TMS / WFS / XYZ`
  - 地形：保留现有地形链路，不进入本次 OGC 多协议协议集合。
- `MVT` 不进入统一发布协议列表。
- 一条发布记录绑定到已完成任务产物。
- 原始数据是影像 → 影像服务；原始数据是矢量 → 矢量服务。
- 发布按钮放在数据处理任务行操作列。
- 发布交互为两步向导：Step 1 选择协议集合，Step 2 填写参数。
- 方案 1：一次性全量替换；允许必要新增文件；接受中途不可编译，最终以全链路验证为准。
- 测试类只作为临时验证代码，验证后必须删除；不提交测试类。

## File Structure

### 后端新增

- `ocean-business-geo/src/main/java/org/ocean/admin/gis/dto/UnifiedPublicationRequest.java`
  - 统一发布请求 DTO。
- `ocean-business-geo/src/main/java/org/ocean/admin/gis/ogc/PublicationCatalog.java`
  - 统一发布目录；按 `gis_publication.protocols` 提供 WMS/WMTS/TMS/WFS/XYZ 图层元数据。
- `ocean-business-geo/src/main/java/org/ocean/admin/gis/ogc/PublicationLayer.java`
  - 统一发布目录的图层记录、瓦片视图、矢量图层视图契约。

### 后端修改

- `db/migration/V2__init_ocean_gis.sql`
- `ocean-business-geo/src/main/java/org/ocean/admin/gis/entity/GisPublication.java`
- `ocean-business-geo/src/main/java/org/ocean/admin/gis/vo/GisPublicationVO.java`
- `ocean-business-geo/src/main/java/org/ocean/admin/gis/ogc/OgcProtocol.java`
- `ocean-business-geo/src/main/java/org/ocean/admin/gis/controller/PublicationController.java`
- `ocean-business-geo/src/main/java/org/ocean/admin/gis/service/PublicationService.java`
- `ocean-business-geo/src/main/java/org/ocean/admin/gis/service/PublicationRecordService.java`
- `ocean-business-geo/src/main/java/org/ocean/admin/gis/service/ProcessingTaskRecordService.java`
- `ocean-business-geo/src/main/java/org/ocean/admin/gis/mapper/GisPublicationMapper.java`
- `ocean-business-geo/src/main/java/org/ocean/admin/gis/ogc/WmsService.java`
- `ocean-business-geo/src/main/java/org/ocean/admin/gis/ogc/WmtsService.java`
- `ocean-business-geo/src/main/java/org/ocean/admin/gis/ogc/TmsService.java`
- `ocean-business-geo/src/main/java/org/ocean/admin/gis/ogc/WfsService.java`
- `ocean-business-geo/src/main/java/org/ocean/admin/gis/ogc/WmsXmlWriter.java`
- `ocean-business-geo/src/main/java/org/ocean/admin/gis/ogc/WmtsXmlWriter.java`
- `ocean-business-geo/src/main/java/org/ocean/admin/gis/ogc/TmsXmlWriter.java`
- `ocean-business-geo/src/main/java/org/ocean/admin/gis/ogc/WfsXmlWriter.java`
- `ocean-business-geo/src/main/java/org/ocean/admin/gis/ogc/VectorMapRenderer.java`
- `ocean-business-geo/src/main/java/org/ocean/admin/gis/ogc/VectorTileRenderer.java`
- `ocean-business-geo/src/main/java/org/ocean/admin/gis/ogc/VectorStyleResolver.java`
- `ocean-business-geo/src/main/java/org/ocean/admin/gis/ogc/VectorTileCache.java`
- `ocean-business-geo/src/main/java/org/ocean/admin/gis/ogc/WmsSourceResolver.java`
- `README.md`
- `docs/云海平台--地理信息基础数据处理子平台 v0.0.1 使用手册.md`

### 后端删除

- `ocean-business-geo/src/main/java/org/ocean/admin/gis/controller/OgcServiceController.java`
- `ocean-business-geo/src/main/java/org/ocean/admin/gis/controller/PublishedResourceController.java`
- `ocean-business-geo/src/main/java/org/ocean/admin/gis/dto/OgcServicePublishRequest.java`
- `ocean-business-geo/src/main/java/org/ocean/admin/gis/entity/GisOgcService.java`
- `ocean-business-geo/src/main/java/org/ocean/admin/gis/mapper/GisOgcServiceMapper.java`
- `ocean-business-geo/src/main/java/org/ocean/admin/gis/service/OgcServicePublicationService.java`
- `ocean-business-geo/src/main/java/org/ocean/admin/gis/service/PublicationProtocol.java`
- `ocean-business-geo/src/main/java/org/ocean/admin/gis/service/ImageryPublicationService.java`
- `ocean-business-geo/src/main/java/org/ocean/admin/gis/service/TerrainPublicationService.java`
- `ocean-business-geo/src/main/java/org/ocean/admin/gis/service/VectorPublicationService.java`
- `ocean-business-geo/src/main/java/org/ocean/admin/gis/service/VectorRasterPublicationService.java`
- `ocean-business-geo/src/main/java/org/ocean/admin/gis/service/VectorWmsPublicationService.java`
- `ocean-business-geo/src/main/java/org/ocean/admin/gis/ogc/PublishedImageryCatalog.java`
- `ocean-business-geo/src/main/java/org/ocean/admin/gis/ogc/PublishedVectorCatalog.java`
- `ocean-business-geo/src/main/java/org/ocean/admin/gis/vo/GisOgcServiceVO.java`

### 前端新增

- `apps/geographic-information/src/features/dataPublishing/components/UnifiedPublishWizard/UnifiedPublishWizard.tsx`
- `apps/geographic-information/src/features/dataPublishing/components/UnifiedPublishWizard/UnifiedPublishWizard.module.css`

### 前端修改

- `apps/geographic-information/src/config/navigation.ts`
- `apps/geographic-information/src/routes/geographicRoutes.tsx`
- `apps/geographic-information/src/features/dataManagement/components/CategorySubNav/CategorySubNav.tsx`
- `apps/geographic-information/src/pages/DataProcessingPage/DataProcessingPage.tsx`
- `apps/geographic-information/src/features/dataPublishing/services/publicationService.ts`
- `apps/geographic-information/src/features/dataPublishing/types/publication.ts`
- `apps/geographic-information/src/features/dataPublishing/hooks/usePublicationPage.ts`
- `apps/geographic-information/src/features/dataPublishing/components/PublicationTable/PublicationTable.tsx`
- `apps/geographic-information/src/features/dataPublishing/components/PublicationDetailDialog/PublicationDetailDialog.tsx`
- `apps/geographic-information/src/features/dataPublishing/constants/publication.ts`

### 前端删除

- `apps/geographic-information/src/pages/OgcServicesPage/OgcServicesPage.tsx`
- `apps/geographic-information/src/pages/OgcServicesPage/OgcServicesPage.module.css`
- `apps/geographic-information/src/features/ogcServices/` 整个目录

---

## Task 1: 数据库最终结构与 Flyway 基线

**Files:**
- Modify: `db/migration/V2__init_ocean_gis.sql:894-979`

- [ ] **Step 1: 修改 `gis_publication` 建表语句，保留旧字段并显式新增字段**

在 `CREATE TABLE ocean_gis.gis_publication` 中保留原有列及类型，在 `service_protocol` 后插入：

```sql
    protocols           TEXT         NOT NULL DEFAULT '[]',
    source_task_id      BIGINT       REFERENCES ocean_gis.gis_processing_task(id) ON DELETE CASCADE,
    processing_type     VARCHAR(20)  NOT NULL,
    layer_name          VARCHAR(200),
    title               VARCHAR(200),
    abstract            VARCHAR(1000),
    keywords            VARCHAR(500),
    default_crs         VARCHAR(32),
    supported_crs       TEXT,
    tile_matrix_sets    TEXT,
    min_zoom            INTEGER,
    max_zoom            INTEGER,
    target_crs          VARCHAR(32),
    tile_matrix_set     VARCHAR(64),
    output_format       VARCHAR(32),
    tile_profile        VARCHAR(32),
    bounds_wgs84        TEXT,
    style_id            VARCHAR(64),
    style_name          VARCHAR(200),
    public_base_url     VARCHAR(500),
    capabilities_urls   TEXT,
    service_urls        TEXT,
    tile_url_templates  TEXT,
```

保留原约束：

```sql
    CONSTRAINT uk_gis_publication_code UNIQUE (service_code),
    CONSTRAINT uk_gis_publication_tile_set UNIQUE (tile_set_id),
    CONSTRAINT fk_gis_publication_tile_set
        FOREIGN KEY (tile_set_id) REFERENCES ocean_gis.gis_tile_set (id),
    CONSTRAINT fk_gis_publication_data_set
        FOREIGN KEY (data_set_id) REFERENCES ocean_gis.gis_data_set (id) ON DELETE SET NULL,
    CONSTRAINT ck_gis_publication_status CHECK (status IN ('PUBLISHED', 'DISABLED')),
    CONSTRAINT ck_gis_publication_source CHECK ((tile_set_id IS NOT NULL) <> (vector_resource_id IS NOT NULL)),
    CONSTRAINT ck_gis_publication_protocol CHECK (
        (vector_resource_id IS NOT NULL AND service_protocol IN ('MVT', 'WMS')) OR
        (tile_set_id IS NOT NULL AND service_protocol IN ('TMS_WMTS', 'XYZ', 'TMS', 'TERRAIN'))),
    CONSTRAINT ck_gis_publication_deleted CHECK (deleted IN (0, 1))
```

新增索引与注释：

```sql
CREATE UNIQUE INDEX uk_gis_publication_task
    ON ocean_gis.gis_publication (source_task_id)
    WHERE deleted = 0 AND source_task_id IS NOT NULL;
CREATE INDEX idx_gis_publication_processing_status
    ON ocean_gis.gis_publication (processing_type, status, publish_time DESC)
    WHERE deleted = 0;
COMMENT ON COLUMN ocean_gis.gis_publication.protocols IS '本次发布支持的协议集合：WMS、WMTS、TMS、WFS、XYZ';
COMMENT ON COLUMN ocean_gis.gis_publication.source_task_id IS '发起发布的数据处理任务 ID';
COMMENT ON COLUMN ocean_gis.gis_publication.processing_type IS '原始数据类别；决定记录进入影像、地形或矢量服务列表';
```

- [ ] **Step 2: 删除整段 `gis_ogc_service` 定义**

从：

```sql
-- =========================================================
-- WMTS/WMS/TMS 独立服务发布
-- =========================================================
```

到 `COMMENT ON COLUMN ocean_gis.gis_ogc_service.min_zoom ...` 全部删除。

- [ ] **Step 3: 在开发库执行一次性过渡 SQL，不提交脚本**

执行以下 SQL，只用于本机开发库，不写入仓库：

```sql
DROP TABLE IF EXISTS ocean_gis.gis_ogc_service CASCADE;
DROP TABLE IF EXISTS ocean_gis.gis_vector_publication_revision CASCADE;
DROP TABLE ocean_gis.gis_publication CASCADE;
```

然后按 `V2__init_ocean_gis.sql` 最终 `gis_publication`、`gis_vector_publication_revision` 建表片段重建本机表。旧发布记录不迁移。

- [ ] **Step 4: 执行 Flyway 校验**

Run:

```bash
cd E:/develop/Ocean_Admin_Service
mvn -q -o flyway:repair -Dflyway.user=postgres -Dflyway.password=123456
mvn -q -o flyway:validate -Dflyway.user=postgres -Dflyway.password=123456
```

Expected: 两条命令退出码均为 0，无 `validate` 失败输出。

- [ ] **Step 5: Commit**

```bash
git add db/migration/V2__init_ocean_gis.sql
git commit -m "feat(gis): 统一发布表结构并移除独立 OGC 服务表"
```

---

## Task 2: 后端实体、VO、DTO、协议枚举

**Files:**
- Modify: `ocean-business-geo/src/main/java/org/ocean/admin/gis/entity/GisPublication.java`
- Modify: `ocean-business-geo/src/main/java/org/ocean/admin/gis/vo/GisPublicationVO.java`
- Create: `ocean-business-geo/src/main/java/org/ocean/admin/gis/dto/UnifiedPublicationRequest.java`
- Modify: `ocean-business-geo/src/main/java/org/ocean/admin/gis/ogc/OgcProtocol.java`

- [ ] **Step 1: 给 `GisPublication` 显式增加字段，保留旧字段**

在 `serviceProtocol` 后新增字符串/整数/长整型字段；JSON 列用 `@TableField` 显式映射：

```java
@TableField("protocols")
private String protocolsJson;
private Long sourceTaskId;
private String processingType;
private String layerName;
private String title;
@TableField("abstract")
private String abstractText;
private String keywords;
private String defaultCrs;
@TableField("supported_crs")
private String supportedCrsJson;
@TableField("tile_matrix_sets")
private String tileMatrixSetsJson;
private Integer minZoom;
private Integer maxZoom;
private String targetCrs;
private String tileMatrixSet;
private String outputFormat;
private String tileProfile;
@TableField("bounds_wgs84")
private String boundsWgs84Json;
private String styleId;
private String styleName;
private String publicBaseUrl;
@TableField("capabilities_urls")
private String capabilitiesUrlsJson;
@TableField("service_urls")
private String serviceUrlsJson;
@TableField("tile_url_templates")
private String tileUrlTemplatesJson;
```

导入 `com.baomidou.mybatisplus.annotation.TableField`。

- [ ] **Step 2: 给 `GisPublicationVO` 增加视图字段**

保留所有旧字段，新增：

```java
private java.util.List<String> protocols;
private String layerName;
private String title;
private String abstractText;
private String keywords;
private String defaultCrs;
private java.util.List<String> supportedCrs;
private java.util.List<String> tileMatrixSets;
private java.util.List<Double> boundsWgs84;
private String styleId;
private String styleName;
private java.util.Map<String, String> capabilitiesUrls;
private java.util.Map<String, String> serviceUrls;
private java.util.Map<String, String> tileUrlTemplates;
```

- [ ] **Step 3: 创建 `UnifiedPublicationRequest`**

完整内容：

```java
package org.ocean.admin.gis.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 数据处理任务行统一发布请求；一条记录可声明多个协议。 */
public record UnifiedPublicationRequest(

        @NotEmpty(message = "至少需要选择一个服务协议")
        List<@NotBlank String> protocols,

        @NotBlank @Size(max = 200) String serviceName,
        @NotBlank @Size(max = 200) String layerName,
        @Size(max = 200) String title,
        @Size(max = 1000) String abstractText,
        @Size(max = 500) String keywords,
        String defaultCrs,
        List<String> supportedCrs,
        List<String> tileMatrixSets,
        @Min(0) @Max(22) Integer minZoom,
        @Min(0) @Max(22) Integer maxZoom,
        @Positive Long defaultStyleSchemeId,
        @Size(max = 100) List<@NotNull @Positive Long> styleSchemeIds) {

    public UnifiedPublicationRequest {
        protocols = protocols == null ? null : List.copyOf(protocols);
        supportedCrs = supportedCrs == null ? null : List.copyOf(supportedCrs);
        tileMatrixSets = tileMatrixSets == null ? null : List.copyOf(tileMatrixSets);
        styleSchemeIds = styleSchemeIds == null ? null : List.copyOf(styleSchemeIds);
        serviceName = serviceName == null ? null : serviceName.strip();
        layerName = layerName == null ? null : layerName.strip();
        title = title == null ? null : title.strip();
        abstractText = abstractText == null ? null : abstractText.strip();
        keywords = keywords == null ? null : keywords.strip();
    }
}
```

- [ ] **Step 4: 扩展 `OgcProtocol`**

枚举值改为：

```java
WMS, WMTS, TMS, WFS, XYZ;
```

增加：

```java
public static final Set<OgcProtocol> IMAGERY = Set.of(WMS, WMTS, TMS, XYZ);
public static final Set<OgcProtocol> VECTOR = Set.of(WMS, WMTS, TMS, WFS, XYZ);

public static Set<OgcProtocol> validate(String processingType, List<String> values) {
    if (values == null || values.isEmpty()) {
        throw new IllegalArgumentException("至少需要选择一个服务协议");
    }
    Set<OgcProtocol> allowed = "VECTOR".equalsIgnoreCase(processingType)
            ? VECTOR : IMAGERY;
    Set<OgcProtocol> protocols = new LinkedHashSet<>();
    for (String value : values) {
        OgcProtocol protocol = parse(value);
        if (!allowed.contains(protocol)) {
            throw new IllegalArgumentException(
                    processingType + " 不支持服务协议: " + protocol);
        }
        protocols.add(protocol);
    }
    return protocols;
}
```

`parse()` 错误提示改为「不支持的服务协议」。

- [ ] **Step 5: Compile intermediate (expected to fail until Task 5)**

Run:

```bash
cd E:/develop/Ocean_Admin_Service
mvn -q -o -pl ocean-business-geo -am compile -DskipTests
```

Expected: 可以出现 `OgcServicePublicationService` 中 switch 不穷尽、`PublicationService` 签名不匹配等错误；记录错误即可，继续执行 Task 3–6，不要回滚。

---

## Task 3: 删除旧 OGC 管理端与私有发布入口

**Files:**
- Delete: 后端删除清单中全部 15 个 Java 文件

- [ ] **Step 1: 删除旧类**

```bash
cd E:/develop/Ocean_Admin_Service
rm -f \
  ocean-business-geo/src/main/java/org/ocean/admin/gis/controller/OgcServiceController.java \
  ocean-business-geo/src/main/java/org/ocean/admin/gis/controller/PublishedResourceController.java \
  ocean-business-geo/src/main/java/org/ocean/admin/gis/dto/OgcServicePublishRequest.java \
  ocean-business-geo/src/main/java/org/ocean/admin/gis/entity/GisOgcService.java \
  ocean-business-geo/src/main/java/org/ocean/admin/gis/mapper/GisOgcServiceMapper.java \
  ocean-business-geo/src/main/java/org/ocean/admin/gis/service/OgcServicePublicationService.java \
  ocean-business-geo/src/main/java/org/ocean/admin/gis/service/PublicationProtocol.java \
  ocean-business-geo/src/main/java/org/ocean/admin/gis/service/ImageryPublicationService.java \
  ocean-business-geo/src/main/java/org/ocean/admin/gis/service/TerrainPublicationService.java \
  ocean-business-geo/src/main/java/org/ocean/admin/gis/service/VectorPublicationService.java \
  ocean-business-geo/src/main/java/org/ocean/admin/gis/service/VectorRasterPublicationService.java \
  ocean-business-geo/src/main/java/org/ocean/admin/gis/service/VectorWmsPublicationService.java \
  ocean-business-geo/src/main/java/org/ocean/admin/gis/ogc/PublishedImageryCatalog.java \
  ocean-business-geo/src/main/java/org/ocean/admin/gis/ogc/PublishedVectorCatalog.java \
  ocean-business-geo/src/main/java/org/ocean/admin/gis/vo/GisOgcServiceVO.java
```

- [ ] **Step 2: 检查没有残留引用**

Run:

```bash
grep -rn "OgcServicePublicationService\|GisOgcServiceMapper\|PublishedImageryCatalog\|PublishedVectorCatalog\|VectorPublicationService\|VectorRasterPublicationService\|VectorWmsPublicationService\|PublicationProtocol" ocean-business-geo/src/main/java
```

Expected: 本步骤允许 `PublicationService` 和运行时服务仍有引用；这些将在 Task 5/6 处理。除此之外不应有其它管理类引用。

- [ ] **Step 3: Commit deletions together with Task 2 files**

```bash
git add ocean-business-geo/src/main/java
git commit -m "refactor(gis): 移除旧独立 OGC 发布服务"
```

---

## Task 4: 新增统一发布目录契约

**Files:**
- Create: `ocean-business-geo/src/main/java/org/ocean/admin/gis/ogc/PublicationLayer.java`
- Create: `ocean-business-geo/src/main/java/org/ocean/admin/gis/ogc/PublicationCatalog.java`

- [ ] **Step 1: 创建 `PublicationLayer.java`**

定义以下 records，字段名必须与后续运行时 Service 一致：

```java
package org.ocean.admin.gis.ogc;

import org.ocean.admin.gis.entity.GisTileSet;
import org.ocean.admin.gis.entity.GisVectorResource;
import tools.jackson.databind.JsonNode;

import java.util.List;

public final class PublicationLayer {
    private PublicationLayer() {}

    public record RasterLayer(
            String serviceCode,
            String serviceName,
            String layerName,
            TileMatrixSetDefinition matrixSet,
            String tileProfile,
            String extension,
            String mediaType,
            int minZoom,
            int maxZoom,
            double[] wgs84Bounds,
            GisTileSet tileSet) {}

    public record VectorLayer(
            String serviceCode,
            String serviceName,
            String layerName,
            String releaseVersion,
            GisVectorResource resource,
            double[] wgs84Bounds,
            JsonNode binding,
            JsonNode contract,
            JsonNode scheme,
            JsonNode cartography,
            List<StyleOption> styles,
            String styleId) {
        public JsonNode layers() {
            return contract.path("layers");
        }

        public String groupKey(long groupId) {
            for (JsonNode group : contract.path("groups")) {
                if (group.path("id").asLong() == groupId) {
                    return group.path("groupKey").asText();
                }
            }
            return "";
        }

        public JsonNode baseStyle(String styleKey) {
            for (JsonNode style : cartography.path("defaults").path("styles")) {
                if (styleKey.equals(style.path("id").asText())) {
                    return style;
                }
            }
            return null;
        }

        public String defaultStyleKey(long groupId) {
            for (JsonNode group : contract.path("groups")) {
                if (group.path("id").asLong() == groupId) {
                    return group.path("defaultStyleKey").asText("");
                }
            }
            return "";
        }
    }

    public record VectorLayerView(
            String serviceCode,
            String title,
            double[] wgs84Bounds,
            int minZoom,
            int maxZoom,
            String extension,
            String mediaType,
            TileMatrixSetDefinition matrixSet,
            List<StyleOption> styles,
            String defaultStyleId,
            String releaseVersion) {}

    public record StyleOption(String id, String name) {}
}
```

完整实现三个 helper 方法，逻辑从旧 `PublishedVectorCatalog.PublishedVectorLayer` 复制。

- [ ] **Step 2: 创建 `PublicationCatalog.java`**

类必须提供以下方法：

```java
public List<PublicationLayer.RasterLayer> listRaster(OgcProtocol protocol)
public PublicationLayer.RasterLayer loadRaster(String serviceCode, OgcProtocol protocol)
public List<PublicationLayer.VectorLayer> listVector(OgcProtocol protocol)
public PublicationLayer.VectorLayer loadVector(String serviceCode, String styleId)
public List<PublicationLayer.VectorLayerView> listVectorViews(OgcProtocol protocol)
public PublicationLayer.VectorLayerView findView(String layerId)
public PublicationLayer.VectorLayerView findView(String serviceCode, String matrixSetId)
public java.nio.file.Path resolveTileFile(String serviceCode, OgcProtocol protocol, String relativePath)
public GisTileSet publishedTileSet(String serviceCode, OgcProtocol protocol)
```

查询条件统一为：

```java
status = "PUBLISHED"
AND deleted = 0
AND protocolsJson LIKE '%"PROTOCOL"%'
```

`protocolsJson` 使用 Jackson 3 `ObjectMapper.readTree(...)` 解析为 `List<String>`；不要依赖数据库 JSON 函数。

`listVectorViews` 对 `vector_resource_id != null` 且协议命中的记录生成 `WebMercatorQuad` 与 `WorldCRS84Quad` 两个视图，逻辑从旧 `PublishedVectorCatalog.listViews()` 复制。

`resolveTileFile` 从 `tileSetId` 查 `GisTileSet.outputKey`，调用 `GisProcessingStorageService.resolveTiles()` 并校验路径不越界、文件存在。

- [ ] **Step 3: Commit**

```bash
git add ocean-business-geo/src/main/java/org/ocean/admin/gis/ogc/PublicationLayer.java ocean-business-geo/src/main/java/org/ocean/admin/gis/ogc/PublicationCatalog.java
git commit -m "feat(gis): 增加统一多协议发布目录"
```

---

## Task 5: 重写发布 Service、Controller 与 VO 映射

**Files:**
- Modify: `ocean-business-geo/src/main/java/org/ocean/admin/gis/controller/PublicationController.java`
- Modify: `ocean-business-geo/src/main/java/org/ocean/admin/gis/service/PublicationService.java`
- Modify: `ocean-business-geo/src/main/java/org/ocean/admin/gis/service/PublicationRecordService.java`
- Modify: `ocean-business-geo/src/main/java/org/ocean/admin/gis/service/ProcessingTaskRecordService.java`
- Modify: `ocean-business-geo/src/main/java/org/ocean/admin/gis/mapper/GisPublicationMapper.java`

- [ ] **Step 1: `PublicationController` 发布接口改请求体**

把：

```java
@Valid @RequestBody ServiceNameRequest request
```

改为：

```java
@Valid @RequestBody UnifiedPublicationRequest request
```

调用：

```java
publicationService.publish(processingType, sourceTaskId, request)
```

删除：

```java
@PostMapping("/VECTOR/publish")
@PostMapping("/VECTOR/wms/publish")
@PutMapping("/VECTOR/{serviceCode}/styles")
```

以及 `private final VectorPublicationService vectors;` 注入。

- [ ] **Step 2: 重写 `PublicationService.publish(...)`**

签名：

```java
@Transactional(rollbackFor = Exception.class)
public GisPublicationVO publish(GisProcessingType processingType, Long sourceTaskId,
        UnifiedPublicationRequest request) throws java.io.IOException
```

实现步骤：

1. `taskService.getRequiredForUpdate(sourceTaskId)`。
2. 校验 `task.getProcessingType() == processingType` 且 `task.getTaskStatus() == COMPLETED`。
3. `OgcProtocol.validate(processingType.name(), request.protocols())`。
4. 按 `processingType` 找 `GisTileSet` 或冻结矢量资源：
   - `IMAGERY / TERRAIN / VECTOR + tileSet`：调用 `recordService.getRequiredTileSet(taskId, processingType)`；
   - `VECTOR` 且选择动态 OGC 协议：调用 `vectorResourceService.freeze(dataSetId, "PUBLICATION")`，样式绑定逻辑从旧 `VectorPublicationService.bind()` 复制，并写入 `vector_definition`。
5. 读取产物元数据：
   - 影像 `tilejson.json`；
   - 静态矢量 `manifest.json`；
   - 动态矢量 `GisVectorResource.definitionJson`。
6. 校验 `minZoom/maxZoom` 不超出产物范围。
7. 生成 `serviceCode`：
   - 影像 `IMG_...`
   - 矢量 `VEC_...`
   - 地形 `TRN_...`
8. 生成旧 `service_protocol`：
   - 影像/静态矢量：`TMS_WMTS`；
   - 动态矢量：`WMS`；
   - 地形：`TERRAIN`。
9. 生成 `capabilitiesUrls / serviceUrls / tileUrlTemplates`：
   - `WMS → /gis/wms`、`/gis/wms?SERVICE=WMS&REQUEST=GetCapabilities`
   - `WMTS → /gis/wmts`、`/gis/wmts?SERVICE=WMTS&REQUEST=GetCapabilities`、`/gis/wmts/rest/{code}/{Style}/WebMercatorQuad/{TileMatrix}/{TileRow}/{TileCol}.png`
   - `TMS → /gis/tms/1.0.0`、`/gis/tms/1.0.0/{code}@WebMercatorQuad@png/{z}/{x}/{y}.png`
   - `WFS → /gis/wfs`、`/gis/wfs?SERVICE=WFS&REQUEST=GetCapabilities`
   - `XYZ → /gis/publications/{processingType}/{code}/{z}/{x}/{y}.png`（静态资源入口路径）
10. 写入一条 `GisPublication`，`source_task_id = taskId`，`processing_type = processingType.name()`，其它字段按请求与产物填充。
11. 返回 `toVO(publication)`。

- [ ] **Step 3: `PublicationRecordService` 只负责统一发布记录持久化**

删除旧 `publish(GisTileSet, String, String, String)` 策略版本，新增：

```java
@Transactional(rollbackFor = Exception.class)
public GisPublication saveUnified(GisPublication publication)
```

按 `source_task_id` 查有效记录；存在则更新全部新字段并置 `PUBLISHED`，不存在则 `insert`。同时把 `getPublicationPage` / `getRequired` 的 `processingType` 过滤改为优先使用 `gis_publication.processing_type` 字段。

- [ ] **Step 4: `ProcessingTaskRecordService` 删除任务检查改用 `source_task_id`**

把：

```java
.eq(GisPublication::getTileSetId, tileSet.getId())
```

改为：

```java
.eq(GisPublication::getSourceTaskId, taskId)
```

`GisPublicationMapper.deleteByTileSetPermanently` 改为：

```java
@Delete("DELETE FROM ocean_gis.gis_publication WHERE source_task_id IN (SELECT id FROM ocean_gis.gis_processing_task WHERE id = #{taskId})")
int deleteByTaskPermanently(@Param("taskId") Long taskId);
```

调用点同步改名。

- [ ] **Step 5: `PublicationService.toVO` 填充新增 VO 字段**

使用 Jackson 3 `ObjectMapper` 把 `protocolsJson / supportedCrsJson / tileMatrixSetsJson / boundsWgs84Json / capabilitiesUrlsJson / serviceUrlsJson / tileUrlTemplatesJson` 反序列化为对应 List/Map；单值 `serviceUrl / descriptorUrl / tileUrlTemplate` 从 Map 中取 WMS 或第一个协议地址。

- [ ] **Step 6: Commit**

```bash
git add ocean-business-geo/src/main/java/org/ocean/admin/gis
git commit -m "feat(gis): 按任务统一发布多协议服务"
```

---

## Task 6: 运行时 OGC 服务改用统一目录

**Files:**
- Modify: `WmsService.java`, `WmtsService.java`, `TmsService.java`, `WfsService.java`
- Modify: `WmsXmlWriter.java`, `WmtsXmlWriter.java`, `TmsXmlWriter.java`, `WfsXmlWriter.java`
- Modify: `VectorMapRenderer.java`, `VectorTileRenderer.java`, `VectorStyleResolver.java`, `VectorTileCache.java`
- Modify: `WmsSourceResolver.java`

- [ ] **Step 1: 全局替换旧目录类型**

执行语义替换：

```text
PublishedImageryCatalog.PublishedLayer → PublicationLayer.RasterLayer
PublishedVectorCatalog.PublishedVectorLayer → PublicationLayer.VectorLayer
PublishedVectorCatalog.VectorLayerView → PublicationLayer.VectorLayerView
PublishedVectorCatalog.StyleOption → PublicationLayer.StyleOption
PublishedImageryCatalog → PublicationCatalog
PublishedVectorCatalog → PublicationCatalog
OgcServicePublicationService → PublicationCatalog
```

- [ ] **Step 2: 修改构造器依赖**

`WmsService` 构造器改为：

```java
public WmsService(PublicationCatalog catalog,
        PublicationRecordService recordService,
        WmsSourceResolver sourceResolver,
        WmsRasterRenderer rasterRenderer,
        VectorMapRenderer vectorRenderer,
        WmsPixelSampler pixelSampler,
        WmsInfoWriter infoWriter)
```

`WmtsService` / `TmsService` 构造器改为注入 `PublicationCatalog` 与 `PublicationRecordService`，不再注入旧 `OgcServicePublicationService`。

`WfsService` 构造器改为注入 `PublicationCatalog`。

- [ ] **Step 3: 能力文档调用改为按协议查统一目录**

```java
WmsService.capabilitiesXml():
    catalog.listRaster(OgcProtocol.WMS), catalog.listVector(OgcProtocol.WMS)

WmtsService.capabilitiesXml():
    catalog.listRaster(OgcProtocol.WMTS), catalog.listVectorViews(OgcProtocol.WMTS)

TmsService.tileMapServiceXml():
    catalog.listRaster(OgcProtocol.TMS), catalog.listVectorViews(OgcProtocol.TMS)

WfsService.capabilitiesXml():
    catalog.listVector(OgcProtocol.WFS)
```

- [ ] **Step 4: 取图调用改为统一目录**

```java
WmtsService.resolveTile(...):
    catalog.resolveTileFile(layer.serviceCode(), OgcProtocol.WMTS, relativePath)

WmsService.resolveSources(...):
    catalog.publishedTileSet(layerCode, OgcProtocol.WMS)

TmsService.resolveTile(...):
    catalog.resolveTileFile(...)
```

矢量动态渲染、WFS 查询、样式解析全部使用 `PublicationLayer.VectorLayer`，保持原渲染逻辑不变。

- [ ] **Step 5: XML Writer 参数类型替换**

把四个 Writer 中所有旧类型替换成 `PublicationLayer.*`；字段访问保持一致：

```text
layer.serviceCode()
layer.serviceName()
layer.layerName()
layer.wgs84Bounds()
layer.minZoom()
layer.maxZoom()
layer.styles()
layer.styleId()
layer.releaseVersion()
```

新增 `RasterLayer.layerName()` 后，Writer 不再使用 `withLayerName`。

- [ ] **Step 6: 编译后端**

Run:

```bash
cd E:/develop/Ocean_Admin_Service
mvn -q -o -pl ocean-bootstrap -am compile -DskipTests
```

Expected: 退出码 0；不得残留对旧删除类的 import 或类型引用。

- [ ] **Step 7: Commit**

```bash
git add ocean-business-geo/src/main/java/org/ocean/admin/gis/ogc
git commit -m "refactor(gis): OGC 运行时改用统一发布目录"
```

---

## Task 7: 后端文档与配置说明

**Files:**
- Modify: `README.md`
- Modify: `docs/云海平台--地理信息基础数据处理子平台 v0.0.1 使用手册.md`

- [ ] **Step 1: 删除旧 OGC 独立发布接口说明**

删除/替换 `/api/gis/ogc-services/...`、`gis_ogc_service`、旧 `VRT_` 私有入口说明。

- [ ] **Step 2: 增加统一发布说明**

写入：

```text
POST /api/gis/publications/{processingType}/{sourceTaskId}/publish
```

请求体示例：

```json
{
  "serviceName": "矢量OGC服务",
  "layerName": "矢量图层",
  "protocols": ["WMS", "WMTS", "TMS", "WFS", "XYZ"],
  "defaultCrs": "EPSG:3857",
  "supportedCrs": ["EPSG:3857", "CRS:84"],
  "minZoom": 0,
  "maxZoom": 18,
  "styleSchemeIds": ["2119615036015337474"],
  "defaultStyleSchemeId": "2119615036015337474"
}
```

说明 protocol 范围、列表展示、能力文档 URL、旧接口已移除、不发兼容代码。

- [ ] **Step 3: Commit**

```bash
git add README.md "docs/云海平台--地理信息基础数据处理子平台 v0.0.1 使用手册.md"
git commit -m "docs(gis): 更新统一多协议发布说明"
```

---

## Task 8: 前端删除 OGC 菜单和旧页面

**Files:**
- Modify: `apps/geographic-information/src/config/navigation.ts`
- Modify: `apps/geographic-information/src/routes/geographicRoutes.tsx`
- Modify: `apps/geographic-information/src/features/dataManagement/components/CategorySubNav/CategorySubNav.tsx`
- Delete: `apps/geographic-information/src/pages/OgcServicesPage/`
- Delete: `apps/geographic-information/src/features/ogcServices/`

- [ ] **Step 1: 删除导航常量和菜单项**

在 `navigation.ts` 删除：

```ts
export const DATA_PUBLISHING_OGC_PATH = '/data-publishing/ogc-services'
```

以及 `DATA_PUBLISHING_SECTIONS` 中 `path: DATA_PUBLISHING_OGC_PATH` 的对象。

- [ ] **Step 2: 删除路由分支**

在 `geographicRoutes.tsx` 删除 `OgcServicesPage` import、`DATA_PUBLISHING_OGC_PATH` import 以及 `if (target === DATA_PUBLISHING_OGC_PATH)` 分支。

- [ ] **Step 3: 删除 `CategorySubNav` 中 OGC 静态项**

删除 `ogcSection` 计算及 `publishingItems` 中追加 `ogcSection` 的逻辑，改为：

```ts
const publishingItems = [DATA_PUBLISHING_SECTIONS[0], ...sections]
```

- [ ] **Step 4: 删除旧页面与 feature 目录**

```bash
cd E:/develop/Ocean_Cloud
rm -rf apps/geographic-information/src/pages/OgcServicesPage
rm -rf apps/geographic-information/src/features/ogcServices
```

- [ ] **Step 5: 检查残留引用**

Run:

```bash
grep -rn "OgcServicesPage\|DATA_PUBLISHING_OGC_PATH\|features/ogcServices\|ogc-services" apps/geographic-information/src
```

Expected: 无输出。

- [ ] **Step 6: Commit**

```bash
git add apps/geographic-information/src
git commit -m "refactor(frontend): 移除独立 OGC 服务页面"
```

---

## Task 9: 前端两步发布向导与数据处理页接入

**Files:**
- Create: `apps/geographic-information/src/features/dataPublishing/components/UnifiedPublishWizard/UnifiedPublishWizard.tsx`
- Create: `apps/geographic-information/src/features/dataPublishing/components/UnifiedPublishWizard/UnifiedPublishWizard.module.css`
- Modify: `apps/geographic-information/src/pages/DataProcessingPage/DataProcessingPage.tsx`
- Modify: `apps/geographic-information/src/features/dataPublishing/services/publicationService.ts`
- Modify: `apps/geographic-information/src/features/dataPublishing/types/publication.ts`

- [ ] **Step 1: 更新发布请求类型**

在 `publication.ts` 增加：

```ts
export type PublicationProtocol = 'WMS' | 'WMTS' | 'TMS' | 'WFS' | 'XYZ'

export interface UnifiedPublishInput {
  readonly serviceName: string
  readonly layerName: string
  readonly title?: string
  readonly abstractText?: string
  readonly keywords?: string
  readonly protocols: readonly PublicationProtocol[]
  readonly defaultCrs?: string
  readonly supportedCrs?: readonly string[]
  readonly tileMatrixSets?: readonly string[]
  readonly minZoom?: number
  readonly maxZoom?: number
  readonly styleSchemeIds?: readonly string[]
  readonly defaultStyleSchemeId?: string
}
```

给 `Publication` 增加：

```ts
readonly protocols: readonly PublicationProtocol[]
readonly layerName: string | null
readonly capabilitiesUrls: Readonly<Record<string, string>>
readonly serviceUrls: Readonly<Record<string, string>>
readonly tileUrlTemplates: Readonly<Record<string, string>>
```

- [ ] **Step 2: 更新 `publishFromTask`**

把签名改为：

```ts
export async function publishFromTask(
  processingType: PublicationProcessingType,
  taskId: string,
  input: UnifiedPublishInput,
): Promise<Publication>
```

请求体使用 `input`，并设置 `{ timeout: 1800000, silentError: true }`。

- [ ] **Step 3: 创建 `UnifiedPublishWizard.tsx`**

组件 props：

```ts
interface UnifiedPublishWizardProps {
  readonly open: boolean
  readonly task: TaskPublishTarget
  readonly onClose: () => void
  readonly onPublished: (record: Publication) => void
}
```

实现两步：

1. Step 0：根据 `task.processingType` 显示复选框：
   - IMAGERY：`WMS / WMTS / TMS / XYZ`
   - VECTOR：`WMS / WMTS / TMS / WFS / XYZ`
2. Step 1：表单字段：
   - `serviceName`、`layerName`、`title`、`abstractText`、`keywords`
   - `defaultCrs`、`supportedCrs`、`tileMatrixSets`
   - `minZoom/maxZoom`
   - 矢量任务显示样式方案多选和默认样式；通过 `getVectorStyleSchemes(dataSetId)` 加载，其中 `dataSetId` 通过 `getTaskDetail(task.id)` 后从 inputs 推导或由后端发布接口从任务自动推导；本计划选择前端不传样式 ID 时由后端使用第一个样式方案，避免新增 task detail 数据结构。
3. 提交调用 `publishFromTask(...)`。
4. 成功后 `onPublished(record)`。

向导使用 Ant Design `Modal`、`Steps`、`Checkbox.Group`、`Form`、`Input`、`InputNumber`、`Select`、`Alert`。

- [ ] **Step 4: 接入 `DataProcessingPage`**

删除旧 `publishForm`、`publishTarget`、`submitPublish({serviceName})` Modal，改为：

```tsx
const [publishTarget, setPublishTarget] = useState<TaskPublishTarget | null>(null)

<TaskListTable onPublish={setPublishTarget} ... />

{publishTarget !== null && (
  <UnifiedPublishWizard
    open
    task={publishTarget}
    onClose={() => setPublishTarget(null)}
    onPublished={(record) => {
      setPublishTarget(null)
      refresh()
      void messageApi.success(
        `服务发布成功：${record.serviceCode}（${record.protocols.join(' / ')}）`,
      )
    }}
  />
)}
```

保留 `publishingId` 状态用于按钮 loading；向导提交前后调用页面传入的 `setPublishingId` 或简化为不显示 loading。

- [ ] **Step 5: Commit**

```bash
git add apps/geographic-information/src
git commit -m "feat(frontend): 数据处理任务行两步发布向导"
```

---

## Task 10: 前端发布列表与详情显示支持协议

**Files:**
- Modify: `apps/geographic-information/src/features/dataPublishing/hooks/usePublicationPage.ts`
- Modify: `apps/geographic-information/src/features/dataPublishing/components/PublicationTable/PublicationTable.tsx`
- Modify: `apps/geographic-information/src/features/dataPublishing/components/PublicationDetailDialog/PublicationDetailDialog.tsx`
- Modify: `apps/geographic-information/src/features/dataPublishing/constants/publication.ts`

- [ ] **Step 1: `PublicationRow` 增加协议字段**

在 `publication.ts`：

```ts
readonly protocols: readonly PublicationProtocol[]
readonly capabilitiesUrls: Readonly<Record<string, string>>
```

`usePublicationPage.toRow()` 从 `record` 复制这些字段，缺省用 `[]` 和 `{}`。

- [ ] **Step 2: 列表增加「支持协议」列**

在 `PublicationTable.buildColumns()` 中 `服务编号` 后插入：

```tsx
{
  title: '支持协议',
  key: 'protocols',
  width: 180,
  render: (_, record) => record.protocols.join(' / ') || '—',
}
```

同步把 `SCROLL_X_BY_SCOPE` 每个宽度增加 180。

- [ ] **Step 3: 详情展示协议 URL**

在 `PublicationDetailDialog.buildSummaryFields()` 中：

```ts
{ label: '支持协议', value: record.protocols.join(' / ') || '—' },
...Object.entries(record.capabilitiesUrls).map(([protocol, url]) => ({
  label: `${protocol} 能力文档`,
  value: resolvePublicationUrl(url),
  copyable: true,
})),
```

- [ ] **Step 4: 常量增加协议标签**

在 `constants/publication.ts` 增加：

```ts
export const PUBLICATION_PROTOCOL_LABELS: Readonly<Record<string, string>> = {
  WMS: 'WMS', WMTS: 'WMTS', TMS: 'TMS', WFS: 'WFS', XYZ: 'XYZ',
}
```

- [ ] **Step 5: Commit**

```bash
git add apps/geographic-information/src/features/dataPublishing
git commit -m "feat(frontend): 发布列表展示支持协议"
```

---

## Task 11: 全链路验证与临时测试清理

**Files:**
- Test: 临时测试类仅放在 `src/test/`，验证后删除
- Verify: 两个仓库

- [ ] **Step 1: 后端编译与 Flyway**

Run:

```bash
cd E:/develop/Ocean_Admin_Service
mvn -q -o -pl ocean-bootstrap -am compile -DskipTests
mvn -q -o -pl ocean-bootstrap -am package -DskipTests
mvn -q -o flyway:repair -Dflyway.user=postgres -Dflyway.password=123456
mvn -q -o flyway:validate -Dflyway.user=postgres -Dflyway.password=123456
```

Expected: 全部退出码 0。

- [ ] **Step 2: 前端 lint / format / build**

Run:

```bash
cd E:/develop/Ocean_Cloud
npx prettier --check $(git diff --name-only HEAD~10..HEAD -- '*.ts' '*.tsx')
npm run lint
npm run build -w @ocean-cloud/geographic-information
```

Expected: 全部退出码 0；构建可以有 chunk size warning，但不能有编译错误。

- [ ] **Step 3: 后端启动到备用端口做 HTTP 冒烟**

Run:

```bash
cd E:/develop/Ocean_Admin_Service
GIS_GDAL_NATIVE_LIBRARY_PATH='D:\develop\gdal-3.13.0\Library\bin' \
java -jar ocean-bootstrap/target/ocean-bootstrap-1.0.0-SNAPSHOT.jar \
  --server.port=8092 --gis.publication.public-base-url=http://127.0.0.1:8092
```

然后验证：

```bash
curl -s -o /dev/null -w "%{http_code}\n" "http://127.0.0.1:8092/gis/wms?SERVICE=WMS&REQUEST=GetCapabilities"
curl -s -o /dev/null -w "%{http_code}\n" "http://127.0.0.1:8092/gis/wmts?SERVICE=WMTS&REQUEST=GetCapabilities"
curl -s -o /dev/null -w "%{http_code}\n" "http://127.0.0.1:8092/gis/tms/1.0.0"
curl -s -o /dev/null -w "%{http_code}\n" "http://127.0.0.1:8092/gis/wfs?SERVICE=WFS&REQUEST=GetCapabilities"
```

Expected: 全部 200。

- [ ] **Step 4: 发布一条影像任务和一条矢量任务**

使用本机已完成任务的 `taskId`，分别调用：

```text
POST /api/gis/publications/IMAGERY/{taskId}/publish
POST /api/gis/publications/VECTOR/{taskId}/publish
```

请求体包含 `protocols` 字段。发布后调用：

```text
GET /api/gis/publications/page?processingType=IMAGERY
GET /api/gis/publications/page?processingType=VECTOR
```

Expected: 响应 records 中包含 `protocols` 数组，且分别属于影像/矢量列表。

- [ ] **Step 5: 验证能力文档按 protocols 暴露**

检查：

```bash
curl "http://127.0.0.1:8092/gis/wms?SERVICE=WMS&REQUEST=GetCapabilities" | grep "<serviceCode>"
curl "http://127.0.0.1:8092/gis/wmts?SERVICE=WMTS&REQUEST=GetCapabilities" | grep "<serviceCode>"
curl "http://127.0.0.1:8092/gis/tms/1.0.0" | grep "<serviceCode>"
curl "http://127.0.0.1:8092/gis/wfs?SERVICE=WFS&REQUEST=GetCapabilities" | grep "<serviceCode>"
```

Expected: 只有声明对应协议的发布记录出现在对应能力文档中。WFS 只出现矢量记录。

- [ ] **Step 6: 删除临时测试类**

Run:

```bash
cd E:/develop/Ocean_Admin_Service
find . -path '*/src/test/*' -type f -delete
git status --short
```

Expected: `src/test/` 下没有残留测试类；工作区只包含正式改动。

- [ ] **Step 7: 最终 commit**

```bash
git add .
git commit -m "chore(gis): 完成统一多协议发布全链路验证"
```

---

## Self-Review

- Spec coverage：已覆盖删除菜单、任务行两步向导、一条记录多协议、统一表、废弃 `gis_ogc_service`、按类别列表、运行时统一目录、不兼容旧数据、显式新增字段、测试验证。
- Placeholder scan：无 TBD/TODO/未写完代码；`PublicationLayer.VectorLayer` 三个 helper 方法已给出完整实现。
- Type consistency：所有运行时类型替换统一为 `PublicationLayer.RasterLayer / VectorLayer / VectorLayerView / StyleOption`；`PublicationCatalog` 方法名在 Task 4、5、6 保持一致。
- Scope check：这是单一跨系统重构计划，不拆分；以最终全链路编译和 HTTP 冒烟作为可工作边界。
