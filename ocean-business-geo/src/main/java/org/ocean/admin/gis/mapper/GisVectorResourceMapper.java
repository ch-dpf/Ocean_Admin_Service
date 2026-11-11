package org.ocean.admin.gis.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.ocean.admin.gis.entity.GisVectorResource;
import org.ocean.admin.gis.entity.GisVectorSourceLayer;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** 矢量存储资源、来源快照与引用关系持久化。 */
@Mapper
public interface GisVectorResourceMapper {

    @Update("UPDATE ocean_gis.gis_vector_resource SET data_version=data_version+1,checksum=NULL,size_bytes=#{size} WHERE id=#{id} AND resource_kind='WORKING'")
    int touchWorkingResource(@Param("id") Long id,@Param("size") long size);
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
    long countSourceReferences(@Param("fileId") Long fileId);
    long countDataSetReferences(@Param("dataSetId") Long dataSetId);
}
