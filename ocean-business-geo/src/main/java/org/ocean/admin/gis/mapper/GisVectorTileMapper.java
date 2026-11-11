package org.ocean.admin.gis.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.ocean.admin.gis.entity.GisVectorFeature;
import org.ocean.admin.gis.entity.GisVectorLayer;
import org.ocean.admin.gis.entity.GisVectorDataSetStyle;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.ocean.admin.gis.vo.GisVectorVO;

import java.util.List;

/** 预览瓦片查询及影响范围变更日志持久化。 */
@Mapper
public interface GisVectorTileMapper {
    @Select(value = """
        INSERT INTO ocean_gis.gis_vector_change(data_set_id,resource_id,request,applied,bounds)
        SELECT #{dataSetId},#{resourceId},CAST(#{requestJson} AS jsonb),#{applied},
            ST_Envelope(ST_Collect(ST_Transform(ST_Force2D(ST_Intersection(ST_SetSRID(ST_GeomFromGeoJSON(shape),4326),
                ST_MakeEnvelope(-180,-85.0511287798066,180,85.0511287798066,4326))),3857)))
        FROM (VALUES(CAST(#{before} AS text)),(CAST(#{after} AS text))) input(shape)
        WHERE shape IS NOT NULL RETURNING id
        """, affectData = true)
    @org.apache.ibatis.annotations.Options(flushCache=org.apache.ibatis.annotations.Options.FlushCachePolicy.TRUE)
    long recordFeatureChange(@Param("dataSetId") Long dataSetId, @Param("resourceId") Long resourceId,
            @Param("requestJson") String requestJson, @Param("applied") boolean applied,
            @Param("before") String before, @Param("after") String after);

    @Select("SELECT id,resource_id,request::text AS request_json FROM ocean_gis.gis_vector_change WHERE data_set_id=#{id} AND NOT applied ORDER BY id")
    List<GisVectorVO.PendingChange> pendingChanges(Long id);

    @Update("UPDATE ocean_gis.gis_vector_change SET applied=true,request=NULL WHERE id=#{id}")
    int completeChange(long id);

    @Select("SELECT COALESCE(max(id),0) FROM ocean_gis.gis_vector_change WHERE data_set_id=#{id}")
    long latestChange(Long id);

    @Select("""
        SELECT COALESCE(jsonb_agg(jsonb_build_object('id',id::text,'globalChange',global_change,'bounds',
            CASE WHEN bounds IS NULL THEN NULL ELSE jsonb_build_array(ST_XMin(Box3D(bounds)),ST_YMin(Box3D(bounds)),
                ST_XMax(Box3D(bounds)),ST_YMax(Box3D(bounds))) END) ORDER BY id),'[]'::jsonb)::text
        FROM (SELECT * FROM ocean_gis.gis_vector_change WHERE data_set_id=#{id} AND id>#{after} ORDER BY id LIMIT 257) c
        """)
    String changes(@Param("id") Long id,@Param("after") long after);

    @Select("""
        SELECT COALESCE(max(id),0) FROM ocean_gis.gis_vector_change
        WHERE data_set_id=#{id} AND (global_change OR ST_Intersects(bounds,
            ST_Expand(ST_TileEnvelope(#{z},#{x},#{y}),40075016.68557849/power(2,#{z})*#{margin})))
        """)
    long tileRevision(@Param("id") Long id,@Param("z") int z,@Param("x") int x,@Param("y") int y,@Param("margin") double margin);
    List<GisVectorLayer> tileLayers(@Param("id") Long id,@Param("b") double[] bbox);

    @Select("SELECT id,layer_id,source_layer_id,ST_AsGeoJSON(geom,15,0) AS geometry_json,properties::text AS properties_json,source_feature_id::text AS source_feature_id,source_feature_index,source_geometry_path,classification_rule_key,version FROM ocean_gis.gis_vector_feature WHERE layer_id=#{layerId} AND deleted=0 AND id>#{after} AND ST_Intersects(geom,ST_MakeEnvelope(#{b[0]},#{b[1]},#{b[2]},#{b[3]},4326)) ORDER BY id LIMIT 100")
    List<GisVectorFeature> tileFeatures(@Param("layerId") Long layerId,@Param("after") long after,@Param("b") double[] bbox);
    GisVectorDataSetStyle tileState(@Param("dataSetId") Long dataSetId);
    String setTileQueryTimeout(@Param("milliseconds") int milliseconds);
    GisVectorVO.TilePayload dataSetTile(@Param("dataSetId") Long dataSetId,
            @Param("policyJson") String policy, @Param("z") int z, @Param("x") int x,
            @Param("y") int y, @Param("maxBytes") int maxBytes);
}
