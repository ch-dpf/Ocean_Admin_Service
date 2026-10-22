package org.ocean.admin.gis.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.ocean.admin.gis.entity.GisVectorFeature;
import org.ocean.admin.gis.entity.GisVectorLayer;
import org.ocean.admin.gis.entity.GisVectorStyle;
import org.ocean.admin.gis.vo.GisVectorVO;

import java.util.List;

/** 矢量图层、要素与样式的 PostGIS 持久化操作。 */
@Mapper
public interface GisVectorMapper {
    int insertLayer(@Param("l") GisVectorLayer layer);
    int deleteExtractingLayersBySource(@Param("fileId") Long fileId);
    List<GisVectorLayer> layersBySource(@Param("fileId") Long fileId);
    int setLayerStatus(@Param("id") Long id, @Param("status") String status);
    Page<GisVectorLayer> pageLayers(Page<GisVectorLayer> page, @Param("dataSetId") Long dataSetId);
    GisVectorLayer layer(@Param("id") Long id);
    GisVectorLayer lockLayer(@Param("id") Long id);
    int refreshLayer(@Param("id") Long id);
    int deleteLayer(@Param("id") Long id);
    GisVectorVO.GeometryError firstGeometryError(@Param("items") List<GisVectorFeature> items);
    int insertFeatures(@Param("items") List<GisVectorFeature> items);
    List<GisVectorFeature> features(@Param("layerId") Long layerId, @Param("afterId") long afterId,
            @Param("limit") int limit, @Param("west") Double west, @Param("south") Double south,
            @Param("east") Double east, @Param("north") Double north);
    GisVectorFeature feature(@Param("layerId") Long layerId, @Param("id") Long id);
    int updateFeature(@Param("f") GisVectorFeature feature, @Param("expected") long expected);
    int deleteFeature(@Param("layerId") Long layerId, @Param("id") Long id, @Param("expected") long expected);
    long countSourceReferences(@Param("fileId") Long fileId);
    long countDataSetReferences(@Param("dataSetId") Long dataSetId);
    List<GisVectorStyle> styles(@Param("layerId") Long layerId);
    GisVectorStyle style(@Param("layerId") Long layerId, @Param("id") Long id);
    GisVectorStyle defaultStyle(@Param("layerId") Long layerId);
    int insertStyle(@Param("s") GisVectorStyle style);
    int updateStyle(@Param("s") GisVectorStyle style, @Param("expected") long expected);
    int clearDefaultStyle(@Param("layerId") Long layerId);
    int setDefaultStyle(@Param("layerId") Long layerId, @Param("id") Long id, @Param("expected") long expected);
    int deleteStyle(@Param("layerId") Long layerId, @Param("id") Long id, @Param("expected") long expected);
    String setTileQueryTimeout(@Param("milliseconds") int milliseconds);
    GisVectorVO.TilePayload vectorTile(@Param("layerId") Long layerId, @Param("z") int z,
            @Param("x") int x, @Param("y") int y, @Param("fieldsJson") String fieldsJson,
            @Param("gridPixels") int gridPixels, @Param("bufferPixels") int bufferPixels,
            @Param("aggregate") boolean aggregate, @Param("maxBytes") int maxBytes);
    List<GisVectorFeature> clusterMembers(@Param("layerId") Long layerId, @Param("z") int z,
            @Param("x") int x, @Param("y") int y, @Param("fieldsJson") String fieldsJson,
            @Param("gridPixels") int gridPixels, @Param("bufferPixels") int bufferPixels,
            @Param("clusterX") long clusterX, @Param("clusterY") long clusterY,
            @Param("propertyHash") String propertyHash, @Param("afterId") long afterId, @Param("limit") int limit);
}
