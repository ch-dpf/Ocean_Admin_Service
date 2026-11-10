package org.ocean.admin.gis.ogc;

import org.ocean.admin.gis.entity.GisTileSet;
import org.ocean.admin.gis.entity.GisVectorResource;
import tools.jackson.databind.JsonNode;

import java.util.List;

/** 统一发布目录向四个 OGC 运行时提供的图层契约。 */
public final class PublicationLayer {

    private PublicationLayer() {
    }

    /** 静态影像、地形或矢量配图瓦片图层。 */
    public record RasterLayer(
            String serviceCode,
            String serviceName,
            String layerName,
            String title,
            String abstractText,
            String keywords,
            TileMatrixSetDefinition matrixSet,
            String tileProfile,
            String extension,
            String mediaType,
            int minZoom,
            int maxZoom,
            double[] wgs84Bounds,
            GisTileSet tileSet,
            String taskGpkgPath,
            JsonNode taskDefinition,
            JsonNode taskScheme) {
    }

    /** 动态矢量发布记录对应的冻结 GPKG 图层。 */
    public record VectorLayer(
            String serviceCode,
            String serviceName,
            String layerName,
            String title,
            String abstractText,
            String keywords,
            String releaseVersion,
            GisVectorResource resource,
            double[] wgs84Bounds,
            JsonNode binding,
            JsonNode contract,
            JsonNode scheme,
            JsonNode cartography,
            List<StyleOption> styles,
            String styleId) {

        public JsonNode layers() {
            return contract.path("layers");
        }

        public String groupKey(long groupId) {
            for (JsonNode group : contract.path("groups")) {
                if (group.path("id").asLong() == groupId) {
                    return group.path("groupKey").asText();
                }
            }
            return "";
        }

        public JsonNode baseStyle(String styleKey) {
            for (JsonNode style : cartography.path("defaults").path("styles")) {
                if (styleKey.equals(style.path("id").asText())) {
                    return style;
                }
            }
            return null;
        }

        public String defaultStyleKey(long groupId) {
            for (JsonNode group : contract.path("groups")) {
                if (group.path("id").asLong() == groupId) {
                    return group.path("defaultStyleKey").asText("");
                }
            }
            return "";
        }
    }

    /** 一个动态矢量服务在某个矩阵集下的能力文档视图。 */
    public record VectorLayerView(
            String serviceCode,
            String serviceName,
            String layerName,
            String title,
            String abstractText,
            String keywords,
            double[] wgs84Bounds,
            int minZoom,
            int maxZoom,
            String extension,
            String mediaType,
            TileMatrixSetDefinition matrixSet,
            List<StyleOption> styles,
            String defaultStyleId,
            String releaseVersion) {
    }

    /** 能力文档列出的一套可切换样式。 */
    public record StyleOption(String id, String name) {
    }
}
