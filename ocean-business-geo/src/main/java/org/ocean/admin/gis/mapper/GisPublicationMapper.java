package org.ocean.admin.gis.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.ocean.admin.gis.entity.GisPublication;

@Mapper
public interface GisPublicationMapper extends BaseMapper<GisPublication> {
    @Delete("DELETE FROM ocean_gis.gis_publication WHERE tile_set_id = #{tileSetId}")
    int deleteByTileSetPermanently(@Param("tileSetId") Long tileSetId);
}
