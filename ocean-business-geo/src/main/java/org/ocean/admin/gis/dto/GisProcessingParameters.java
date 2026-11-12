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

    default RasterSelection raster() { return null; }

    /** 按资源内容选择处理输入；层键来自文件空间元数据，波段编号从1开始。 */
    record RasterSelection(java.util.List<String> layerKeys, java.util.List<Integer> bands,
                           java.util.List<Double> displayRange, Double elevationScale, Double elevationOffset) {
        public RasterSelection {
            if (layerKeys != null) {
                if (layerKeys.isEmpty() || layerKeys.stream().anyMatch(key -> key == null || key.isBlank())
                        || new java.util.HashSet<>(layerKeys).size() != layerKeys.size())
                    throw new IllegalArgumentException("栅格集合键不能为空或重复");
                layerKeys = java.util.List.copyOf(layerKeys);
            }
            if (bands != null) {
                if (bands.isEmpty() || bands.size() > 4 || bands.stream().anyMatch(band -> band == null || band < 1)
                        || new java.util.HashSet<>(bands).size() != bands.size())
                    throw new IllegalArgumentException("处理波段必须为1到4个不同的正整数编号");
                bands = java.util.List.copyOf(bands);
            }
            if (displayRange != null) {
                if (displayRange.size() != 2 || displayRange.stream().anyMatch(value -> value == null || !Double.isFinite(value))
                        || displayRange.get(0) >= displayRange.get(1))
                    throw new IllegalArgumentException("显示数值范围必须为有限且递增的最小值、最大值");
                displayRange = java.util.List.copyOf(displayRange);
            }
            if (elevationScale != null && (!Double.isFinite(elevationScale) || elevationScale <= 0)
                    || elevationOffset != null && !Double.isFinite(elevationOffset))
                throw new IllegalArgumentException("高程比例须为有限正数，偏移须为有限数值");
        }
    }

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
