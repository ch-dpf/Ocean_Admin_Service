package org.ocean.admin.gis.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.ocean.admin.gis.processing.GisProcessingType;

/** 不同 GIS 切片引擎参数的公共契约。 */
@Schema(oneOf = {TerrainProcessingParameters.class, ImageryProcessingParameters.class,
        GisProcessingParameters.VectorProcessingParameters.class})
public sealed interface GisProcessingParameters
        permits TerrainProcessingParameters, ImageryProcessingParameters,
        GisProcessingParameters.VectorProcessingParameters {

    GisProcessingType processingType();

    String targetCrs();

    String tileProfile();

    String outputFormat();

    default Integer minZoom() {
        return null;
    }

    default Integer maxZoom() {
        return null;
    }

    /** 静态矢量成果绑定确定的数据修订及完整样式快照。 */
    record VectorProcessingParameters(
            String targetCrs,
            String tileProfile,
            String outputFormat, Long dataSetId, Long dataRevision,
            Integer minZoom, Integer maxZoom, java.util.List<Double> bounds,
            tools.jackson.databind.JsonNode schemeSnapshot) implements GisProcessingParameters {

        @Override
        public GisProcessingType processingType() {
            return GisProcessingType.VECTOR;
        }
    }

}
