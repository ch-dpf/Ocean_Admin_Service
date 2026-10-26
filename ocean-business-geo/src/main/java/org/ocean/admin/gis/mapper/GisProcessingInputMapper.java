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

    @Select("""
            SELECT t.id AS task_id, t.task_no, t.task_name, t.task_status
            FROM ocean_gis.gis_processing_input i
            JOIN ocean_gis.gis_processing_task t ON t.id = i.task_id
            WHERE i.file_meta_id = #{fileMetaId}
              AND t.deleted = 0
              AND t.task_status IN ('QUEUED', 'RUNNING')
            ORDER BY t.create_time, t.id
            """)
    List<ActiveTaskReference> selectActiveByFileMetaId(@Param("fileMetaId") Long fileMetaId);

    @Select("""
            SELECT DISTINCT t.id AS task_id, t.task_no, t.task_name, t.task_status
            FROM ocean_gis.gis_processing_input i
            JOIN ocean_gis.gis_processing_task t ON t.id = i.task_id
            LEFT JOIN ocean_gis.gis_file_meta f ON f.id = i.file_meta_id
            WHERE COALESCE(i.source_data_set_id, f.data_set_id) = #{dataSetId}
              AND t.deleted = 0
              AND t.task_status IN ('QUEUED', 'RUNNING')
            ORDER BY t.id
            """)
    List<ActiveTaskReference> selectActiveByDataSetId(@Param("dataSetId") Long dataSetId);

    @Update("""
            UPDATE ocean_gis.gis_processing_input i
            SET file_meta_id = NULL, update_time = CURRENT_TIMESTAMP
            FROM ocean_gis.gis_processing_task t
            WHERE t.id = i.task_id
              AND i.file_meta_id = #{fileMetaId}
              AND t.task_status IN ('COMPLETED', 'PARTIAL_FAILED', 'FAILED')
            """)
    int detachTerminalByFileMetaId(@Param("fileMetaId") Long fileMetaId);

    @Update("""
            UPDATE ocean_gis.gis_processing_input i
            SET file_meta_id = NULL, update_time = CURRENT_TIMESTAMP
            FROM ocean_gis.gis_processing_task t
            WHERE t.id = i.task_id
              AND COALESCE(i.source_data_set_id, (
                    SELECT f.data_set_id FROM ocean_gis.gis_file_meta f
                    WHERE f.id = i.file_meta_id
                  )) = #{dataSetId}
              AND t.task_status IN ('COMPLETED', 'PARTIAL_FAILED', 'FAILED')
            """)
    int detachTerminalByDataSetId(@Param("dataSetId") Long dataSetId);

    @Update("""
            UPDATE ocean_gis.gis_processing_input
            SET file_meta_id = NULL, update_time = CURRENT_TIMESTAMP
            WHERE task_id = #{taskId}
            """)
    int detachTaskFileReferences(@Param("taskId") Long taskId);

    record ActiveTaskReference(Long taskId, String taskNo, String taskName, String taskStatus) { }
}
