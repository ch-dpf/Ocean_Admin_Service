package org.ocean.admin.gis.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.ocean.admin.gis.entity.GisProcessingInput;

import java.util.List;

@Mapper
public interface GisProcessingInputMapper extends BaseMapper<GisProcessingInput> {
    @Select("SELECT * FROM ocean_gis.gis_processing_input "
            + "WHERE task_id = #{taskId} ORDER BY sequence_no")
    List<GisProcessingInput> selectByTaskId(@Param("taskId") Long taskId);

    @Update("UPDATE ocean_gis.gis_processing_input SET input_status = #{status}, "
            + "error_message = #{error}, update_time = CURRENT_TIMESTAMP "
            + "WHERE task_id = #{taskId}")
    int updateTaskStatus(@Param("taskId") Long taskId, @Param("status") String status,
            @Param("error") String error);
}
