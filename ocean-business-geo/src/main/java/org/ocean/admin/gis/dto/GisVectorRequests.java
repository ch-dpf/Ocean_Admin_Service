package org.ocean.admin.gis.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** 矢量管理请求契约。 */
public final class GisVectorRequests {
    private GisVectorRequests() { }

    public record Import(
            @NotNull @Positive Long fileMetaId,
            @NotBlank @Size(max = 100) String layerName,
            @NotBlank @Size(max = 128) String requestId,
            @Schema(description = "分层方式；省略时为 SINGLE，首期仅支持 SINGLE") String splitMode) { }
}
