package org.ocean.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Gis 任务实体类
 *
 * @author DeepOcean
 * @since 2026-09-11
 */
@Data
@TableName(value = "gis_task",schema = "ocean_gis")
public class GisTask {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 对外暴露的稳定任务编号，例如 GIS_20260911_xxx */
    private String taskNo;

    /** 任务名称 */
    private String taskName;

    /**
     * 任务大类 1-上传、2-切片、3-发布、4-导出/下载
     */
    private Long taskType;

    /** 优先级，数值越大优先级越高 */
    private Integer priority;

    /** 工作项总数 */
    private Long totalCount;

    /** 成功工作项数量 */
    private Long completedCount;

    /** 失败工作项数量 */
    private Long failedCount;

    /** 关联数据集 */
    private Long dataSetId;

    /** 父任务，用于上传→处理→切片任务链 */
    private Long parentTaskId;

    /** 整条任务链的根任务 */
    private Long rootTaskId;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;



}
