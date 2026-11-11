package org.ocean.admin.gis.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 数据处理任务行统一发布请求；一条记录只声明一个协议。 */
public record UnifiedPublicationRequest(

        @NotBlank String protocol,

        @NotBlank @Size(max = 200) String serviceName,
        @Min(0) @Max(22) Integer minZoom,
        @Min(0) @Max(22) Integer maxZoom) {

    public UnifiedPublicationRequest {
        serviceName = serviceName == null ? null : serviceName.strip();
        protocol = protocol == null ? null : protocol.strip();
    }
}
