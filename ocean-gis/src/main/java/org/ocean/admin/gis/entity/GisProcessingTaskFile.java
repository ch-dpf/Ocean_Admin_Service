package org.ocean.admin.gis.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 多文件处理任务中的输入及其产物。 */
@Data
@TableName(value = "gis_processing_task_file", schema = "ocean_gis")
public class GisProcessingTaskFile {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long taskId;
    private Integer fileIndex;
    private String originalName;
    private String storageKey;
    private String outputKey;
    private String status;
    private String errorMessage;
}
