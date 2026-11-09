package org.ocean.admin.gis.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 独立 OGC 服务发布请求；层级留空时使用切片产物层级。 */
public record OgcServicePublishRequest(

        @NotNull(message = "源任务 ID 不能为空")
        Long sourceTaskId,

        @NotBlank(message = "服务类型不能为空")
        String protocol,

        @NotBlank(message = "服务名称不能为空")
        @Size(max = 200, message = "服务名称最多 200 个字符")
        String serviceName,

        @NotBlank(message = "图层名称不能为空")
        @Size(max = 200, message = "图层名称最多 200 个字符")
        String layerName,

        @Size(max = 500, message = "服务说明最多 500 个字符")
        String description,

        @Min(value = 0, message = "最小层级不能小于 0")
        @Max(value = 22, message = "最小层级不能大于 22")
        Integer minZoom,

        @Min(value = 0, message = "最大层级不能小于 0")
        @Max(value = 22, message = "最大层级不能大于 22")
        Integer maxZoom) {

    public OgcServicePublishRequest {
        serviceName = serviceName == null ? null : serviceName.strip();
        layerName = layerName == null ? null : layerName.strip();
        description = description == null ? null : description.strip();
    }
}
