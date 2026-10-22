package org.ocean.admin.gis.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;
import java.util.List;

/** 矢量管理请求契约。 */
public final class GisVectorRequests {
    private GisVectorRequests() { }

    public record Style(@NotBlank @Size(max = 100) String name, @NotNull JsonNode definition) { }

    public record StyleMatch(@NotNull @Positive Long featureId, @NotNull Double zoom) { }

    public record RenderContext(@NotNull @Size(max = 512) List<@NotBlank @Size(max = 255) String> fields,
            Boolean aggregatePoints, Integer bufferPixels) { }
}
