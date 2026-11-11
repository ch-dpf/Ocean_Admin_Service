package org.ocean.admin.gis.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.ocean.admin.gis.entity.GisVectorFeature;
import org.ocean.admin.gis.entity.GisVectorLayer;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Delete;
import org.ocean.admin.gis.vo.GisVectorVO;

import java.util.List;

/** 矢量工作图层与要素的空间持久化。 */
@Mapper
public interface GisVectorFeatureMapper {

    @Update("""
        UPDATE ocean_gis.gis_vector_layer SET feature_count=#{count},
            geographic_extent=ST_SetSRID(ST_GeomFromGeoJSON(#{extent}),4326),geometry_types=CAST(#{types} AS jsonb),
            data_version=data_version+1,update_time=CURRENT_TIMESTAMP WHERE id=#{id} AND deleted=0
        """)
    int updatePackageLayer(@Param("id") Long id,@Param("count") long count,@Param("extent") String extent,@Param("types") String types);

    @Update("UPDATE ocean_gis.gis_vector_layer SET resource_id=#{resourceId},data_version=data_version+1 WHERE id=#{layerId} AND deleted=0")
    int assignResource(@Param("layerId") Long layerId, @Param("resourceId") Long resourceId);

    @Delete("DELETE FROM ocean_gis.gis_vector_feature WHERE layer_id=#{layerId}")
    int purgeLayerFeatures(Long layerId);

    @Select("SELECT id,data_set_id,source_file_id,resource_id,source_layer_id,layer_key,layer_name,group_id,original_group_id,geometry_type,assignment_mode,enabled,style_mode,presentation_version,layer_status,feature_count,data_version FROM ocean_gis.gis_vector_layer WHERE data_set_id=#{id} AND deleted=0 AND layer_status='READY' ORDER BY id")
    List<GisVectorLayer> dataSetLayers(Long id);

    @Select("SELECT id,layer_id,source_layer_id,ST_AsGeoJSON(geom,15,0) AS geometry_json,properties::text AS properties_json,source_feature_id::text AS source_feature_id,source_feature_index,source_geometry_path,classification_rule_key,version,create_time,update_time FROM ocean_gis.gis_vector_feature WHERE layer_id=#{layerId} AND deleted=0 AND id>#{after} ORDER BY id LIMIT #{limit}")
    List<GisVectorFeature> exportFeatures(@Param("layerId") Long layerId,@Param("after") long after,@Param("limit") int limit);

    @Select("SELECT EXISTS(SELECT 1 FROM ocean_gis.gis_vector_layer l JOIN ocean_gis.gis_vector_resource r ON r.id=l.resource_id WHERE l.data_set_id=#{id} AND l.deleted=0 AND r.storage_type='GPKG')")
    boolean hasPackageLayers(Long id);

    int insertLayer(@Param("l") GisVectorLayer layer);
    int deleteExtractingLayersBySource(@Param("fileId") Long fileId);
    List<GisVectorLayer> layersBySource(@Param("fileId") Long fileId);
    int setLayerStatus(@Param("id") Long id, @Param("status") String status);
    Page<GisVectorLayer> pageLayers(Page<GisVectorLayer> page, @Param("dataSetId") Long dataSetId,
            @Param("groupId") Long groupId, @Param("sourceFileId") Long sourceFileId);
    int updatePresentation(@Param("l") GisVectorLayer layer, @Param("expected") long expected);
    GisVectorLayer layer(@Param("id") Long id);
    GisVectorLayer lockLayer(@Param("id") Long id);
    int refreshLayer(@Param("id") Long id);
    int deleteLayer(@Param("id") Long id);
    GisVectorVO.GeometryError firstGeometryError(@Param("items") List<GisVectorFeature> items);
    List<GisVectorVO.GeometryIssue> geometryIssues(@Param("items") List<GisVectorFeature> items);
    int insertFeatures(@Param("items") List<GisVectorFeature> items);
    List<GisVectorFeature> features(@Param("layerId") Long layerId, @Param("afterId") long afterId,
            @Param("limit") int limit, @Param("west") Double west, @Param("south") Double south,
            @Param("east") Double east, @Param("north") Double north);
    GisVectorFeature feature(@Param("layerId") Long layerId, @Param("id") Long id);
    int updateFeature(@Param("f") GisVectorFeature feature, @Param("expected") long expected);
    int reclassifyFeature(@Param("id") Long id, @Param("oldLayerId") Long oldLayerId,
            @Param("targetLayerId") Long targetLayerId, @Param("ruleKey") String ruleKey);
    int deleteFeature(@Param("layerId") Long layerId, @Param("id") Long id, @Param("expected") long expected);
    String dataSetExtent(@Param("dataSetId") Long dataSetId);
}
