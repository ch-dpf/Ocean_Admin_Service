package org.ocean.admin.gis.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.ocean.admin.gis.entity.GisFileMeta;
import org.ocean.admin.gis.entity.GisVectorFeature;
import org.ocean.admin.gis.entity.GisVectorImportTask;
import org.ocean.admin.gis.entity.GisVectorLayer;
import org.ocean.admin.gis.vo.GisVectorVO;

import java.util.List;

/** 矢量任务、图层与要素的 PostGIS 持久化操作。 */
@Mapper
public interface GisVectorMapper {
    GisFileMeta lockSource(@Param("id") Long id);
    GisVectorImportTask findTaskByRequest(@Param("requestId") String requestId);
    GisVectorImportTask findTask(@Param("taskNo") String taskNo);
    GisVectorImportTask findTaskById(@Param("id") Long id);
    int insertTask(@Param("t") GisVectorImportTask task);
    int insertLayer(@Param("l") GisVectorLayer layer);
    List<Long> candidates(@Param("limit") int limit);
    GisVectorImportTask claim(@Param("id") Long id);
    GisVectorImportTask lockClaim(@Param("id") Long id, @Param("version") Long version);
    int renewLease(@Param("id") Long id, @Param("version") Long version);
    int updateProgress(@Param("id") Long id, @Param("version") Long version, @Param("count") long count);
    int finishTask(@Param("id") Long id, @Param("version") Long version,
            @Param("status") String status, @Param("count") long count,
            @Param("errorIndex") Long errorIndex, @Param("error") String error);
    int retry(@Param("id") Long id);
    List<GisVectorLayer> taskLayers(@Param("taskId") Long taskId);
    int resetLayers(@Param("taskId") Long taskId);
    int deleteImportedFeatures(@Param("taskId") Long taskId);
    int setLayersStatus(@Param("taskId") Long taskId, @Param("status") String status);
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
}
