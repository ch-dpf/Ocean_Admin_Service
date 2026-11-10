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
