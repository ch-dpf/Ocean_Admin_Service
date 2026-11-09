package org.ocean.admin.gis.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 独立 OGC 服务发布请求；resourceType 决定本次发布栅格还是动态矢量。
 *
 * <p>栅格字段中的层级留空时使用切片产物层级；矢量字段只在 VECTOR 模式下使用。</p>
 */
public record OgcServicePublishRequest(

        @NotBlank(message = "发布资源类型不能为空")
        String resourceType,

        Long sourceTaskId,

        String protocol,

        @NotBlank(message = "服务名称不能为空")
        @Size(max = 200, message = "服务名称最多 200 个字符")
        String serviceName,

        String layerName,

        @Size(max = 500, message = "服务说明最多 500 个字符")
        String description,

        @Min(value = 0, message = "最小层级不能小于 0")
        @Max(value = 22, message = "最小层级不能大于 22")
        Integer minZoom,

        @Min(value = 0, message = "最大层级不能小于 0")
        @Max(value = 22, message = "最大层级不能大于 22")
        Integer maxZoom,

        @Positive(message = "矢量数据集 ID 必须为正数")
        Long dataSetId,

        @Size(max = 100, message = "矢量样式方案最多 100 套")
        List<@NotNull @Positive Long> styleSchemeIds,

        @Positive(message = "默认样式方案 ID 必须为正数")
        Long defaultStyleSchemeId) {

    public OgcServicePublishRequest {
        resourceType = resourceType == null ? null : resourceType.strip();
        protocol = protocol == null ? null : protocol.strip();
        serviceName = serviceName == null ? null : serviceName.strip();
        layerName = layerName == null ? null : layerName.strip();
        description = description == null ? null : description.strip();
        styleSchemeIds = styleSchemeIds == null ? null : List.copyOf(styleSchemeIds);
    }

    /** 把矢量字段转换为现有动态矢量发布请求。 */
    public GisVectorRequests.Publish vectorRequest() {
        return new GisVectorRequests.Publish(dataSetId, serviceName,
                styleSchemeIds, defaultStyleSchemeId);
    }
}
