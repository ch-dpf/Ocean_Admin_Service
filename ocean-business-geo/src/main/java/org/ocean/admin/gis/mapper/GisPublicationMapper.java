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

    /** 物理删除已停用记录，释放瓦片集唯一约束；样式版本由外键级联清理。 */
    @Delete("DELETE FROM ocean_gis.gis_publication "
            + "WHERE id = #{id} AND status = 'DISABLED' AND deleted = 0")
    int deleteDisabledPermanently(@Param("id") Long id);

    /** 同时修改样式时锁定发布记录，样式修订不能覆盖停用操作。 */
    default GisPublication lockVectorPublication(String serviceCode) {
        return selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<GisPublication>()
                .eq(GisPublication::getServiceCode, serviceCode)
                .eq(GisPublication::getProcessingType, "VECTOR")
                .last("FOR UPDATE"));
    }

    @org.apache.ibatis.annotations.Update("""
        UPDATE ocean_gis.gis_publication SET vector_definition=#{definition},style_id=#{styleId},
            style_name=#{styleName},update_time=CURRENT_TIMESTAMP
        WHERE id=#{id} AND vector_definition=#{expected} AND status='PUBLISHED' AND deleted=0
            AND source_task_id IS NULL AND service_protocol='WMS'
        """)
    int updateDataSetStyles(@Param("id") Long id, @Param("expected") String expected,
            @Param("definition") String definition, @Param("styleId") String styleId, @Param("styleName") String styleName);
}
