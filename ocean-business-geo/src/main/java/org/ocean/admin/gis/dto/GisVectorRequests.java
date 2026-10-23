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

    public record GroupVisibility(@NotNull Boolean enabled) { }

    public record CustomStyle(@NotNull Double minZoom, @NotNull Double maxZoom, @NotNull JsonNode symbol) { }

    public record Presentation(@NotNull @Positive Long groupId, @NotBlank String styleMode,
            @NotNull Boolean enabled, CustomStyle customStyle) { }

    public record RenderContext(@NotNull @Size(min = 4, max = 4) List<@NotNull Double> bbox,
            @NotNull Double displayZoom, @NotNull Integer widthPixels, @NotNull Integer heightPixels,
            @Size(max = 1024) List<@Positive Long> enabledGroupIds,
            @Size(max = 1024) List<@Positive Long> enabledLayerIds) { }
}
