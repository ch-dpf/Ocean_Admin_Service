package org.ocean.admin.gis.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.ocean.admin.gis.entity.GisVectorStyle;
import org.ocean.admin.gis.entity.GisVectorDataSetStyle;
import org.ocean.admin.gis.entity.GisVectorGroup;
import org.ocean.admin.gis.entity.GisVectorStyleScheme;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Delete;

import java.util.List;

/** 矢量分类、显示修订和样式配置持久化。 */
@Mapper
public interface GisVectorStyleMapper {
    @Update("""
        UPDATE ocean_gis.gis_vector_dataset_style SET definition=CAST(#{definitionJson} AS jsonb),
            cartography=CAST(#{cartographyJson} AS jsonb),template_checksum=#{templateChecksum},
            template_id=#{templateId},template_version=#{templateVersion},
            data_revision=data_revision+1,display_revision=display_revision+1,style_version=style_version+1,
            update_time=CURRENT_TIMESTAMP WHERE id=#{id}
        """)
    int replaceDataSetRules(GisVectorDataSetStyle snapshot);

    /** 只启用从未人工配置的未分类图层，不覆盖已有样式和主动隐藏的图层。 */
    @Update("""
        UPDATE ocean_gis.gis_vector_layer l SET enabled=true,style_mode='INHERIT_TARGET_GROUP',
            presentation_version=presentation_version+1,update_time=CURRENT_TIMESTAMP
        FROM ocean_gis.gis_vector_group g
        WHERE l.data_set_id=#{dataSetId} AND g.data_set_id=l.data_set_id AND g.id=l.group_id
          AND g.node_type='UNKNOWN_CLASSIFICATION' AND l.deleted=0 AND l.layer_status='READY'
          AND l.style_mode='UNCONFIGURED' AND l.assignment_mode='AUTO' AND l.presentation_version=1
        """)
    int enableUnconfiguredLayers(Long dataSetId);

    @Select("""
        SELECT EXISTS(SELECT 1 FROM ocean_gis.gis_file_meta WHERE data_set_id=#{dataSetId} AND vector_dataset_style_id IS NOT NULL)
            OR EXISTS(SELECT 1 FROM ocean_gis.gis_vector_layer WHERE data_set_id=#{dataSetId})
            OR EXISTS(SELECT 1 FROM ocean_gis.gis_vector_style s JOIN ocean_gis.gis_vector_group g ON g.id=s.group_id WHERE g.data_set_id=#{dataSetId})
        """)
    boolean hasPlacementReferences(Long dataSetId);

    @Delete("DELETE FROM ocean_gis.gis_vector_group WHERE data_set_id=#{dataSetId}")
    int deleteDataSetGroups(Long dataSetId);

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
    GisVectorDataSetStyle dataSetStyle(@Param("dataSetId") Long dataSetId);
    GisVectorDataSetStyle lockDataSetStyle(@Param("dataSetId") Long dataSetId);
    int insertDataSetStyle(@Param("s") GisVectorDataSetStyle style);
    int insertGroups(@Param("groups") List<GisVectorGroup> groups);
    List<GisVectorGroup> groups(@Param("dataSetId") Long dataSetId);
    GisVectorGroup group(@Param("dataSetId") Long dataSetId, @Param("id") Long id);
    int setGroupEnabled(@Param("id") Long id, @Param("enabled") boolean enabled);
    int bumpDataSetRevision(@Param("dataSetId") Long dataSetId, @Param("data") boolean data, @Param("display") boolean display);
    List<GisVectorStyle> styles(@Param("layerId") Long layerId);
    GisVectorStyle style(@Param("layerId") Long layerId, @Param("id") Long id);
    GisVectorStyle defaultStyle(@Param("layerId") Long layerId);
    int insertStyle(@Param("s") GisVectorStyle style);
    int updateStyle(@Param("s") GisVectorStyle style, @Param("expected") long expected);
    int clearDefaultStyle(@Param("layerId") Long layerId);
    int setDefaultStyle(@Param("layerId") Long layerId, @Param("id") Long id, @Param("expected") long expected);
    int deleteStyle(@Param("layerId") Long layerId, @Param("id") Long id, @Param("expected") long expected);
    String groupCartography(@Param("dataSetId") Long dataSetId);
    String layerCartography(@Param("dataSetId") Long dataSetId);
    String featureCartography(@Param("dataSetId") Long dataSetId, @Param("ids") List<Long> ids);
    boolean hasFeatureCartography(@Param("dataSetId") Long dataSetId);
    String featureKind(@Param("dataSetId") Long dataSetId, @Param("featureId") Long featureId);
    int saveCartography(@Param("id") Long id, @Param("scope") String scope, @Param("targetId") Long targetId, @Param("definition") String definition);
    int deleteFeatureCartography(@Param("featureId") Long featureId);
}
