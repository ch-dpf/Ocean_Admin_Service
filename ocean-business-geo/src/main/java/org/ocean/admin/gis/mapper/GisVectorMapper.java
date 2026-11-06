package org.ocean.admin.gis.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.ocean.admin.gis.entity.GisVectorFeature;
import org.ocean.admin.gis.entity.GisVectorLayer;
import org.ocean.admin.gis.entity.GisVectorStyle;
import org.ocean.admin.gis.entity.GisVectorDataSetStyle;
import org.ocean.admin.gis.entity.GisVectorGroup;
import org.ocean.admin.gis.entity.GisVectorResource;
import org.ocean.admin.gis.entity.GisVectorSourceLayer;
import org.ocean.admin.gis.entity.GisVectorStyleScheme;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Delete;
import org.ocean.admin.gis.vo.GisVectorVO;

import java.util.List;

/** 矢量图层、要素与样式的 PostGIS 持久化操作。 */
@Mapper
public interface GisVectorMapper {
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

    @Update("""
        UPDATE ocean_gis.gis_vector_layer SET feature_count=#{count},
            geographic_extent=ST_SetSRID(ST_GeomFromGeoJSON(#{extent}),4326),geometry_types=CAST(#{types} AS jsonb),
            data_version=data_version+1,update_time=CURRENT_TIMESTAMP WHERE id=#{id} AND deleted=0
        """)
    int updatePackageLayer(@Param("id") Long id,@Param("count") long count,@Param("extent") String extent,@Param("types") String types);

    @Update("UPDATE ocean_gis.gis_vector_resource SET data_version=data_version+1,checksum=NULL,size_bytes=#{size} WHERE id=#{id} AND resource_kind='WORKING'")
    int touchWorkingResource(@Param("id") Long id,@Param("size") long size);
    @Update("""
        UPDATE ocean_gis.gis_vector_dataset_style SET definition=CAST(#{definitionJson} AS jsonb),
            cartography=CAST(#{cartographyJson} AS jsonb),template_checksum=#{templateChecksum},
            template_id=#{templateId},template_version=#{templateVersion},
            data_revision=data_revision+1,display_revision=display_revision+1,style_version=style_version+1,
            update_time=CURRENT_TIMESTAMP WHERE id=#{id}
        """)
    int replaceDataSetRules(GisVectorDataSetStyle snapshot);

    @Select("""
        SELECT EXISTS(SELECT 1 FROM ocean_gis.gis_file_meta WHERE data_set_id=#{dataSetId} AND vector_dataset_style_id IS NOT NULL)
            OR EXISTS(SELECT 1 FROM ocean_gis.gis_vector_layer WHERE data_set_id=#{dataSetId})
            OR EXISTS(SELECT 1 FROM ocean_gis.gis_vector_style s JOIN ocean_gis.gis_vector_group g ON g.id=s.group_id WHERE g.data_set_id=#{dataSetId})
        """)
    boolean hasPlacementReferences(Long dataSetId);

    @Delete("DELETE FROM ocean_gis.gis_vector_group WHERE data_set_id=#{dataSetId}")
    int deleteDataSetGroups(Long dataSetId);
    @Select("SELECT *,definition::text AS definition_json FROM ocean_gis.gis_vector_resource WHERE id=#{id} AND status='READY'")
    GisVectorResource resource(Long id);

    @Select("SELECT *,definition::text AS definition_json FROM ocean_gis.gis_vector_resource WHERE source_file_id=#{fileId} AND resource_kind='SOURCE' AND status='READY'")
    GisVectorResource sourceResource(Long fileId);

    @Select("SELECT *,definition::text AS definition_json FROM ocean_gis.gis_vector_resource WHERE data_set_id=#{dataSetId} AND status='READY' ORDER BY id")
    List<GisVectorResource> resources(Long dataSetId);

    @Insert("""
        INSERT INTO ocean_gis.gis_vector_resource(id,data_set_id,source_file_id,resource_kind,storage_type,storage_key,status,data_version,size_bytes,checksum,definition)
        VALUES(#{id},#{dataSetId},#{sourceFileId},#{resourceKind},#{storageType},#{storageKey},'READY',#{dataVersion},#{sizeBytes},#{checksum},CAST(#{definitionJson} AS jsonb))
        """)
    int insertResource(GisVectorResource resource);

    @Update("UPDATE ocean_gis.gis_vector_resource SET status='RETIRED' WHERE id=#{id}")
    int retireResource(Long id);

    @Update("""
        UPDATE ocean_gis.gis_vector_resource r SET status='RETIRED'
        WHERE source_file_id=#{fileId} AND resource_kind='WORKING' AND status='READY'
          AND NOT EXISTS(SELECT 1 FROM ocean_gis.gis_vector_layer l WHERE l.resource_id=r.id AND l.deleted=0)
          AND NOT EXISTS(SELECT 1 FROM ocean_gis.gis_publication p WHERE p.vector_resource_id=r.id AND p.deleted=0)
        """)
    int retireUnusedWorkingResources(Long fileId);

    @Update("UPDATE ocean_gis.gis_vector_resource r SET status='RETIRED' WHERE data_set_id=#{id} AND NOT EXISTS(SELECT 1 FROM ocean_gis.gis_publication p WHERE p.vector_resource_id=r.id AND p.deleted=0)")
    int retireDataSetResources(Long id);

    @Select("SELECT (SELECT count(*) FROM ocean_gis.gis_vector_layer WHERE resource_id=#{id} AND deleted=0)+(SELECT count(*) FROM ocean_gis.gis_publication WHERE vector_resource_id=#{id} AND deleted=0)")
    long resourceReferences(Long id);

    @Select("SELECT *,definition::text AS definition_json FROM ocean_gis.gis_vector_resource WHERE status='RETIRED' AND storage_key IS NOT NULL LIMIT 100")
    List<GisVectorResource> retiredResources();

    @Update("UPDATE ocean_gis.gis_vector_resource SET storage_key=NULL,size_bytes=0 WHERE id=#{id} AND status='RETIRED'")
    int markResourceCleaned(Long id);

    @Select("SELECT *,schema_definition::text AS schema_json FROM ocean_gis.gis_vector_source_layer WHERE resource_id=#{resourceId} ORDER BY id")
    List<GisVectorSourceLayer> sourceLayers(Long resourceId);

    @Select("SELECT *,schema_definition::text AS schema_json FROM ocean_gis.gis_vector_source_layer WHERE id=#{id}")
    GisVectorSourceLayer sourceLayer(Long id);

    @Insert("""
        INSERT INTO ocean_gis.gis_vector_source_layer(id,resource_id,layer_key,layer_name,table_name,feature_count,source_crs,schema_definition)
        VALUES(#{id},#{resourceId},#{layerKey},#{layerName},#{tableName},#{featureCount},#{sourceCrs},CAST(#{schemaJson} AS jsonb))
        """)
    int insertSourceLayer(GisVectorSourceLayer layer);

    @Update("UPDATE ocean_gis.gis_vector_layer SET resource_id=#{resourceId},data_version=data_version+1 WHERE id=#{layerId} AND deleted=0")
    int assignResource(@Param("layerId") Long layerId, @Param("resourceId") Long resourceId);

    @Delete("DELETE FROM ocean_gis.gis_vector_feature WHERE layer_id=#{layerId}")
    int purgeLayerFeatures(Long layerId);

    @Select("SELECT id,data_set_id,source_file_id,resource_id,source_layer_id,layer_key,layer_name,group_id,original_group_id,geometry_type,assignment_mode,enabled,style_mode,presentation_version,layer_status,feature_count,data_version FROM ocean_gis.gis_vector_layer WHERE data_set_id=#{id} AND deleted=0 AND layer_status='READY' ORDER BY id")
    List<GisVectorLayer> dataSetLayers(Long id);
    List<GisVectorLayer> tileLayers(@Param("id") Long id,@Param("b") double[] bbox);

    @Select("SELECT id,layer_id,source_layer_id,ST_AsGeoJSON(geom,15,0) AS geometry_json,properties::text AS properties_json,source_feature_id::text AS source_feature_id,source_feature_index,source_geometry_path,classification_rule_key,version,create_time,update_time FROM ocean_gis.gis_vector_feature WHERE layer_id=#{layerId} AND deleted=0 AND id>#{after} ORDER BY id LIMIT #{limit}")
    List<GisVectorFeature> exportFeatures(@Param("layerId") Long layerId,@Param("after") long after,@Param("limit") int limit);

    @Select("SELECT id,layer_id,source_layer_id,ST_AsGeoJSON(geom,15,0) AS geometry_json,properties::text AS properties_json,source_feature_id::text AS source_feature_id,source_feature_index,source_geometry_path,classification_rule_key,version FROM ocean_gis.gis_vector_feature WHERE layer_id=#{layerId} AND deleted=0 AND id>#{after} AND ST_Intersects(geom,ST_MakeEnvelope(#{b[0]},#{b[1]},#{b[2]},#{b[3]},4326)) ORDER BY id LIMIT 100")
    List<GisVectorFeature> tileFeatures(@Param("layerId") Long layerId,@Param("after") long after,@Param("b") double[] bbox);

    @Select("SELECT EXISTS(SELECT 1 FROM ocean_gis.gis_vector_layer l JOIN ocean_gis.gis_vector_resource r ON r.id=l.resource_id WHERE l.data_set_id=#{id} AND l.deleted=0 AND r.storage_type='GPKG')")
    boolean hasPackageLayers(Long id);

    @Select("SELECT *,definition::text AS definition_json FROM ocean_gis.gis_vector_style_scheme WHERE data_set_id=#{id} ORDER BY id")
    List<GisVectorStyleScheme> schemes(Long id);

    @Select("""
        SELECT s.id,s.name,s.version,
          COALESCE((SELECT jsonb_agg(color ORDER BY layer_order,color) FROM (
            SELECT color,MIN(layer_order) AS layer_order FROM (
              SELECT COALESCE(g.config #>> '{override,symbol,fillColor}',style #>> '{symbol,fillColor}') AS color,
                     COALESCE((style->>'renderOrder')::numeric,0) AS layer_order
              FROM jsonb_array_elements(s.definition #> '{cartography,defaults,styles}') AS styles(style)
              LEFT JOIN LATERAL (
                SELECT value AS config FROM jsonb_each(s.definition #> '{cartography,groups}')
                WHERE value->>'styleKey'=style->>'id' ORDER BY key LIMIT 1
              ) g ON true
              WHERE style->>'geometryType'='POLYGON'
            ) fills WHERE color ~ '^#[0-9a-fA-F]{6}$'
            GROUP BY color ORDER BY layer_order,color LIMIT 3
          ) palette),'[]'::jsonb)::text AS definition_json
        FROM ocean_gis.gis_vector_style_scheme s WHERE s.data_set_id=#{id} ORDER BY s.id
        """)
    List<GisVectorStyleScheme> schemeOptions(Long id);

    @Select("SELECT *,definition::text AS definition_json FROM ocean_gis.gis_vector_style_scheme WHERE id=#{id}")
    GisVectorStyleScheme scheme(Long id);

    @Insert("INSERT INTO ocean_gis.gis_vector_style_scheme(id,data_set_id,name,definition) VALUES(#{id},#{dataSetId},#{name},CAST(#{definitionJson} AS jsonb))")
    int insertScheme(GisVectorStyleScheme scheme);

    @Update("UPDATE ocean_gis.gis_vector_style_scheme SET name=#{name},definition=CAST(#{definitionJson} AS jsonb),version=version+1,update_time=CURRENT_TIMESTAMP WHERE id=#{id} AND version=#{version}")
    int updateScheme(GisVectorStyleScheme scheme);

    @Delete("DELETE FROM ocean_gis.gis_vector_style_scheme WHERE id=#{id} AND version=#{version}")
    int deleteScheme(@Param("id") Long id,@Param("version") long version);

    @Select("SELECT COALESCE(jsonb_object_agg(s.feature_id::text,s.definition),'{}'::jsonb)::text FROM ocean_gis.gis_vector_style s JOIN ocean_gis.gis_vector_feature f ON f.id=s.feature_id JOIN ocean_gis.gis_vector_layer l ON l.id=f.layer_id WHERE l.data_set_id=#{id} AND l.deleted=0 AND f.deleted=0 AND s.scope='FEATURE'")
    String allFeatureCartography(Long id);

    int insertLayer(@Param("l") GisVectorLayer layer);
    int deleteExtractingLayersBySource(@Param("fileId") Long fileId);
    List<GisVectorLayer> layersBySource(@Param("fileId") Long fileId);
    int setLayerStatus(@Param("id") Long id, @Param("status") String status);
    Page<GisVectorLayer> pageLayers(Page<GisVectorLayer> page, @Param("dataSetId") Long dataSetId,
            @Param("groupId") Long groupId, @Param("sourceFileId") Long sourceFileId);
    GisVectorDataSetStyle dataSetStyle(@Param("dataSetId") Long dataSetId);
    GisVectorDataSetStyle tileState(@Param("dataSetId") Long dataSetId);
    GisVectorDataSetStyle lockDataSetStyle(@Param("dataSetId") Long dataSetId);
    int insertDataSetStyle(@Param("s") GisVectorDataSetStyle style);
    int insertGroups(@Param("groups") List<GisVectorGroup> groups);
    List<GisVectorGroup> groups(@Param("dataSetId") Long dataSetId);
    GisVectorGroup group(@Param("dataSetId") Long dataSetId, @Param("id") Long id);
    int setGroupEnabled(@Param("id") Long id, @Param("enabled") boolean enabled);
    int bumpDataSetRevision(@Param("dataSetId") Long dataSetId, @Param("data") boolean data, @Param("display") boolean display);
    int updatePresentation(@Param("l") GisVectorLayer layer, @Param("expected") long expected);
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
    int reclassifyFeature(@Param("id") Long id, @Param("oldLayerId") Long oldLayerId,
            @Param("targetLayerId") Long targetLayerId, @Param("ruleKey") String ruleKey);
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
    String dataSetExtent(@Param("dataSetId") Long dataSetId);
    String groupCartography(@Param("dataSetId") Long dataSetId);
    String layerCartography(@Param("dataSetId") Long dataSetId);
    String featureCartography(@Param("dataSetId") Long dataSetId, @Param("ids") List<Long> ids);
    boolean hasFeatureCartography(@Param("dataSetId") Long dataSetId);
    String featureKind(@Param("dataSetId") Long dataSetId, @Param("featureId") Long featureId);
    int saveCartography(@Param("id") Long id, @Param("scope") String scope, @Param("targetId") Long targetId, @Param("definition") String definition);
    int deleteFeatureCartography(@Param("featureId") Long featureId);
    String setTileQueryTimeout(@Param("milliseconds") int milliseconds);
    GisVectorVO.TilePayload dataSetTile(@Param("dataSetId") Long dataSetId,
            @Param("policyJson") String policy, @Param("z") int z, @Param("x") int x,
            @Param("y") int y, @Param("maxBytes") int maxBytes);
}
