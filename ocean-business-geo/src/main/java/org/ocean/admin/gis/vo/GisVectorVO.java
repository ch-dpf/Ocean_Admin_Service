package org.ocean.admin.gis.vo;

import java.time.LocalDateTime;
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
}
