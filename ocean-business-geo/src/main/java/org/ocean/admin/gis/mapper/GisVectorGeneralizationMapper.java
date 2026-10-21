package org.ocean.admin.gis.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.ocean.admin.gis.entity.GisVectorGeneralizationBuild;
import org.ocean.admin.gis.entity.GisVectorFeature;
import org.ocean.admin.gis.vo.GisVectorVO;
import java.util.List;

/** 概化策略、持久待办、显示几何与成员关系。 */
@Mapper
public interface GisVectorGeneralizationMapper {
    GisVectorVO.GeneralizationConfig config(@Param("layerId") Long layerId);
    int insertConfig(@Param("layerId") Long layerId, @Param("policy") String policy);
    int updateConfig(@Param("layerId") Long layerId, @Param("policy") String policy, @Param("expected") long expected);
    GisVectorGeneralizationBuild build(@Param("id") Long id);
    GisVectorGeneralizationBuild latest(@Param("layerId") Long layerId);
    int enqueue(@Param("id") Long id, @Param("layerId") Long layerId);
    List<Long> undiscovered(@Param("limit") int limit);
    List<Long> candidates(@Param("limit") int limit);
    GisVectorGeneralizationBuild claim(@Param("id") Long id);
    boolean advisoryLock(@Param("id") Long id);
    int progress(@Param("id") Long id, @Param("attempt") long attempt, @Param("zoom") int zoom);
    int finish(@Param("id") Long id, @Param("attempt") long attempt, @Param("status") String status,
            @Param("maxBytes") long maxBytes, @Param("error") String error);
    int publish(@Param("id") Long id, @Param("layerId") Long layerId);
    int removeUnpublished(@Param("id") Long id);
    int removeOldGeometry(@Param("layerId") Long layerId);
    int deleteZoom(@Param("id") Long id, @Param("z") int z);
    int buildZoom(@Param("id") Long id, @Param("layerId") Long layerId, @Param("z") int z,
            @Param("policy") String policy, @Param("factor") int factor, @Param("polygonMode") String polygonMode);
    boolean validCoverage(@Param("layerId") Long layerId);
    boolean boundedProperties(@Param("layerId") Long layerId, @Param("fieldsJson") String fieldsJson);
    GisVectorVO.GeneralizationExtent extent(@Param("layerId") Long layerId);
    GisVectorVO.TilePayload tile(@Param("buildId") Long buildId, @Param("layerId") Long layerId,
            @Param("z") int z, @Param("x") int x, @Param("y") int y, @Param("fieldsJson") String fieldsJson,
            @Param("gridPixels") int gridPixels, @Param("bufferPixels") int bufferPixels,
            @Param("aggregate") boolean aggregate, @Param("maxBytes") int maxBytes);
    List<GisVectorFeature> members(@Param("buildId") Long buildId, @Param("z") int z,
            @Param("representationId") String representationId, @Param("layerId") Long layerId,
            @Param("afterId") long afterId, @Param("limit") int limit);
}
