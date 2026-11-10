package org.ocean.admin.gis.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.ocean.admin.gis.entity.GisPublication;

@Mapper
public interface GisPublicationMapper extends BaseMapper<GisPublication> {
    @org.apache.ibatis.annotations.Insert("INSERT INTO ocean_gis.gis_vector_publication_revision(publication_id,release_version,definition) VALUES(#{id},#{version},CAST(#{definition} AS jsonb))")
    int insertVectorRevision(@Param("id") Long id,@Param("version") String version,@Param("definition") String definition);

    @org.apache.ibatis.annotations.Select("SELECT definition::text FROM ocean_gis.gis_vector_publication_revision WHERE publication_id=#{id} AND release_version=#{version}")
    String vectorRevision(@Param("id") Long id,@Param("version") String version);
    @Delete("DELETE FROM ocean_gis.gis_publication WHERE source_task_id = #{taskId}")
    int deleteByTaskPermanently(@Param("taskId") Long taskId);
}
