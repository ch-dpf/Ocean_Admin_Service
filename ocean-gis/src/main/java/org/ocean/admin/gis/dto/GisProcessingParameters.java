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

    /** 尚未实现的矢量引擎保留原有参数结构，避免影响现有请求模型。 */
    record VectorProcessingParameters(
            String targetCrs,
            String tileProfile,
            String outputFormat) implements GisProcessingParameters {

        @Override
        public GisProcessingType processingType() {
            return GisProcessingType.VECTOR;
        }
    }

}
