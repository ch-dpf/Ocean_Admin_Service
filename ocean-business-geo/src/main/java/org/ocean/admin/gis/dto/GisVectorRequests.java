package org.ocean.admin.gis.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;
import java.util.List;

/** 矢量管理请求契约。 */
public final class GisVectorRequests {
    public record TileBuild(@NotBlank @Size(max=200) String taskName,
            @NotNull @Positive Long schemeId, @NotNull @Positive Long schemeVersion,
            @NotNull Long dataRevision, @NotNull Integer minZoom, @NotNull Integer maxZoom,
            @NotNull @Size(min=4,max=4) List<@NotNull Double> bounds) { }
    public record Scheme(@NotBlank @Size(max=100) String name, JsonNode definition,
            JsonNode groupOverrides, JsonNode groupVisibility, Long styleVersion) { }
    public record Publish(@NotNull @Positive Long dataSetId, @NotBlank @Size(max=200) String serviceName,
            @NotNull @Size(min=1,max=100) List<@NotNull @Positive Long> styleSchemeIds, @NotNull @Positive Long defaultStyleSchemeId) { }
    private GisVectorRequests() { }

    public record Style(@NotBlank @Size(max = 100) String name, @NotNull JsonNode definition) { }

    public record StyleMatch(@NotNull @Positive Long featureId, @NotNull Double zoom) { }

    public record GroupVisibility(@NotNull Boolean enabled) { }

    public record CustomStyle(@NotNull Double minZoom, @NotNull Double maxZoom, @NotNull JsonNode symbol) { }

    public record Presentation(@NotNull @Positive Long groupId, @NotBlank String styleMode,
            @NotNull Boolean enabled, CustomStyle customStyle) { }

    public record FeatureCartographyQuery(@NotNull @Size(min = 1, max = 256) List<@NotNull @Positive Long> featureIds) { }
}
