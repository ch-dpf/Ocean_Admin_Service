package org.ocean.admin.gis.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.ocean.admin.gis.entity.GisVectorFeature;
import org.ocean.admin.gis.entity.GisVectorLayer;
import org.ocean.admin.gis.entity.GisVectorStyle;
import org.ocean.admin.gis.entity.GisVectorDataSetStyle;
import org.ocean.admin.gis.entity.GisVectorGroup;
import org.ocean.admin.gis.vo.GisVectorVO;

import java.util.List;

/** 矢量图层、要素与样式的 PostGIS 持久化操作。 */
@Mapper
public interface GisVectorMapper {
    int insertLayer(@Param("l") GisVectorLayer layer);
    int deleteExtractingLayersBySource(@Param("fileId") Long fileId);
    List<GisVectorLayer> layersBySource(@Param("fileId") Long fileId);
    int setLayerStatus(@Param("id") Long id, @Param("status") String status);
    Page<GisVectorLayer> pageLayers(Page<GisVectorLayer> page, @Param("dataSetId") Long dataSetId,
            @Param("groupId") Long groupId, @Param("sourceFileId") Long sourceFileId);
    GisVectorDataSetStyle dataSetStyle(@Param("dataSetId") Long dataSetId);
    GisVectorDataSetStyle lockDataSetStyle(@Param("dataSetId") Long dataSetId);
    int insertDataSetStyle(@Param("s") GisVectorDataSetStyle style);
    int insertGroups(@Param("groups") List<GisVectorGroup> groups);
    List<GisVectorGroup> groups(@Param("dataSetId") Long dataSetId);
    GisVectorGroup group(@Param("dataSetId") Long dataSetId, @Param("id") Long id);
    int setGroupEnabled(@Param("id") Long id, @Param("enabled") boolean enabled);
    int bumpDataSetRevision(@Param("dataSetId") Long dataSetId, @Param("data") boolean data, @Param("display") boolean display);
    int updatePresentation(@Param("l") GisVectorLayer layer, @Param("expected") long expected);
    List<GisVectorLayer> renderLayers(@Param("dataSetId") Long dataSetId, @Param("groupIds") List<Long> groupIds,
            @Param("layerIds") List<Long> layerIds);
    List<GisVectorStyle> defaultStyles(@Param("dataSetId") Long dataSetId, @Param("layerIds") List<Long> layerIds);
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
    GisVectorVO.DisplayPlan displayPlan(@Param("ctx") GisVectorVO.RenderContext context, @Param("plansJson") String plans,
            @Param("policyJson") String policy);
    GisVectorVO.TilePayload dataSetTile(@Param("ctx") GisVectorVO.RenderContext context, @Param("plansJson") String plans,
            @Param("policyJson") String policy, @Param("z") int z, @Param("x") int x,
            @Param("y") int y, @Param("maxBytes") int maxBytes);
}
