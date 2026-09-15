package org.ocean.admin.gis.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.ocean.admin.gis.entity.GisProcessingTaskFile;

import java.util.List;

@Mapper
public interface GisProcessingTaskFileMapper extends BaseMapper<GisProcessingTaskFile> {
    @Update("UPDATE ocean_gis.gis_processing_task_file SET status = #{status}, "
            + "error_message = #{error} WHERE id = #{id}")
    int updateStatus(@Param("id") Long id, @Param("status") String status,
            @Param("error") String error);

    @Select("SELECT id, task_id, file_index, original_name, storage_key, output_key, "
            + "status, error_message FROM ocean_gis.gis_processing_task_file "
            + "WHERE task_id = #{taskId} ORDER BY file_index")
    List<GisProcessingTaskFile> selectByTaskId(@Param("taskId") Long taskId);
}
