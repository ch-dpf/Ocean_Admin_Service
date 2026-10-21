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
            JsonNode geometryTypes, long dataVersion, LocalDateTime createTime, LocalDateTime updateTime) { }

    public record ImportTask(String taskId, String requestId, String status, long processedFeatureCount,
            Long errorFeatureIndex, String errorMessage, List<Layer> layers,
            LocalDateTime createTime, LocalDateTime finishTime) { }

    public record Feature(String type, String id, JsonNode geometry, JsonNode properties,
            long version, String layerId) { }

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

    public record RenderContext(String contextId, String layerId, String dataVersion, List<String> fields,
            boolean aggregatePoints, int gridPixels, int bufferPixels, int maxZoom, Instant expiresAt,
            String generalizationBuildId, String policyVersion, int overviewMaxZoom,
            boolean overviewAvailable, String overviewStatus, String overviewMessage) { }

    public record GeneralizationBuild(String buildId, String dataVersion, String policyVersion, String status,
            int completedZoom, long maxTileBytes, String errorMessage, LocalDateTime createTime, LocalDateTime finishTime) { }
    public record Generalization(String layerId, JsonNode policy, String policyVersion, String dataVersion,
            GeneralizationBuild latestBuild, GeneralizationBuild publishedBuild) { }
    public record GeneralizationMembers(String representationId, List<ClusterMember> members, boolean hasMore,
            String nextCursor, String dataVersion) { }
    /** 以下记录仅供概化持久化及服务内部协作。 */
    public record GeneralizationConfig(Long layerId, String policyJson, long policyVersion, Long publishedBuildId) { }
    public record GeneralizationExtent(double west, double south, double east, double north) { }

    public record ClusterMember(String featureId, JsonNode properties, long version) { }

    public record ClusterMembers(String clusterId, List<ClusterMember> members, boolean hasMore,
            String nextCursor, String dataVersion) { }

    /** 仅供数据库与瓦片服务协作，不直接作为HTTP JSON响应。 */
    public record TilePayload(byte[] body, long byteSize) { }
}
