package org.ocean.admin.gis.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;
import java.util.List;

/** 矢量管理请求契约。 */
public final class GisVectorRequests {
    private GisVectorRequests() { }

    public record Import(
            @NotNull @Positive Long fileMetaId,
            @NotBlank @Size(max = 100) String layerName,
            @NotBlank @Size(max = 128) String requestId,
            @Schema(description = "分层方式；省略时为 SINGLE，首期仅支持 SINGLE") String splitMode) { }

    public record Style(@NotBlank @Size(max = 100) String name, @NotNull JsonNode definition) { }

    public record StyleMatch(@NotNull @Positive Long featureId, @NotNull Double zoom) { }

    public record Generalization(@NotNull JsonNode policy) { }

    public record RenderContext(@NotNull @Size(max = 512) List<@NotBlank @Size(max = 255) String> fields,
            Boolean aggregatePoints, Integer bufferPixels) { }
}
