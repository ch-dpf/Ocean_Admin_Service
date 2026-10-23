package org.ocean.admin.gis.vo;

import java.time.LocalDateTime;
import java.time.Instant;
import java.util.List;
import tools.jackson.databind.JsonNode;

/** 矢量响应；GeoJSON 属性保持原始 JSON 类型，系统 ID 显式以字符串输出。 */
public final class GisVectorVO {
    private GisVectorVO() { }

    public record Layer(String layerId, String dataSetId, String sourceFileId, String layerKey,
            String layerName, String status, long featureCount, JsonNode extent,
            JsonNode geometryTypes, long dataVersion, LocalDateTime createTime, LocalDateTime updateTime,
            String groupId, String originalGroupId, String geometryType, String assignmentMode,
            boolean enabled, String styleMode, long presentationVersion) { }

    public record Feature(String type, String id, JsonNode geometry, JsonNode properties,
            long version, String layerId, SourceReference source) { }

    public record SourceReference(Long featureIndex, JsonNode featureId, String geometryPath, String classificationRuleKey) { }

    public record FeatureCollection(String type, List<Feature> features, int numberReturned,
            boolean hasMore, String nextCursor, long dataVersion) { }

    public record GeometryError(Long featureIndex, String reason) { }

    public record StyleSummary(String styleId, String layerId, String name, boolean isDefault,
            long version, LocalDateTime createTime, LocalDateTime updateTime) { }

    public record Style(String styleId, String layerId, String name, boolean isDefault,
            JsonNode definition, long version, LocalDateTime createTime, LocalDateTime updateTime) { }

    public record RuleMatch(String geometryPath, String geometryType, String ruleId, boolean visible, JsonNode symbol) { }

    public record StyleMatch(String styleId, String layerId, String featureId, long styleVersion,
            long featureVersion, double zoom, List<RuleMatch> matches) { }

    public record DataSetStyle(String snapshotId, String dataSetId, String templateId, int templateVersion,
            long styleVersion, long dataRevision, long displayRevision, JsonNode definition) { }

    public record Group(String id, String parentId, String groupKey, String name, String nodeType,
            String geometryType, boolean assignable, String defaultStyleKey, boolean enabled,
            long version, long layerCount, long featureCount, List<Group> children) { }
    public record GroupTree(List<Group> groups, String etag) { }

    public record EffectiveStyle(String layerId, String source, String groupId, String styleId,
            long version, long presentationVersion, boolean configured, JsonNode definition) { }

    /** 瓦片版本只由数据与抽析规则决定，连续显示缩放和配图不参与。 */
    public record MapDefinition(String dataSetId, String tileVersion, long dataRevision,
            int minZoom, int nativeMaxZoom, int displayMaxZoom, int tileSize, String tiles, JsonNode extent) { }

    public record Cartography(String dataSetId, long styleVersion, long displayRevision,
            JsonNode defaults, JsonNode groups, JsonNode layers, boolean hasFeatureOverrides) { }

    /** 仅供数据库与瓦片服务协作，不直接作为HTTP JSON响应。 */
    public record TilePayload(byte[] body, long byteSize) { }
}
