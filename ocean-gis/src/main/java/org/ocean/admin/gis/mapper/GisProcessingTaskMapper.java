package org.ocean.admin.gis.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.ocean.admin.gis.entity.GisProcessingTask;

@Mapper
public interface GisProcessingTaskMapper extends BaseMapper<GisProcessingTask> {

    @Insert("INSERT INTO ocean_gis.gis_processing_task "
            + "(id, task_no, task_name, processing_type, source_type, parameters, "
            + "parameter_schema_version, request_fingerprint, priority, total_count, "
            + "completed_count, failed_count, task_status, current_stage, error_message, "
            + "start_time, finish_time, create_time, update_time, deleted) VALUES "
            + "(#{task.id}, #{task.taskNo}, #{task.taskName}, #{task.processingType}, "
            + "#{task.sourceType}, CAST(#{task.parametersJson} AS jsonb), "
            + "#{task.parameterSchemaVersion}, #{task.requestFingerprint}, #{task.priority}, "
            + "#{task.totalCount}, #{task.completedCount}, #{task.failedCount}, "
            + "#{task.taskStatus}, #{task.currentStage}, #{task.errorMessage}, "
            + "#{task.startTime}, #{task.finishTime}, #{task.createTime}, "
            + "#{task.updateTime}, #{task.deleted})")
    int insertTask(@Param("task") GisProcessingTask task);

    @Select("SELECT id, task_no, task_name, processing_type, source_type, "
            + "parameters::text AS parameters_json, parameter_schema_version, "
            + "request_fingerprint, priority, total_count, completed_count, failed_count, "
            + "task_status, current_stage, error_message, start_time, finish_time, "
            + "create_time, update_time, deleted FROM ocean_gis.gis_processing_task "
            + "WHERE id = #{taskId} AND deleted = 0")
    GisProcessingTask selectTaskById(@Param("taskId") Long taskId);
}
