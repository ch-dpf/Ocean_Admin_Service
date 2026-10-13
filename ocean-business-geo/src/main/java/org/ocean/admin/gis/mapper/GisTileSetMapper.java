package org.ocean.admin.gis.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.ocean.admin.gis.entity.GisTileSet;

@Mapper
public interface GisTileSetMapper extends BaseMapper<GisTileSet> {
    @Select("SELECT * FROM ocean_gis.gis_tile_set WHERE task_id = #{taskId}")
    GisTileSet selectByTaskId(@Param("taskId") Long taskId);
}
