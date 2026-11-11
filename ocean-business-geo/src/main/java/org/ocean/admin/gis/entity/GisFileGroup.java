package org.ocean.admin.gis.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 文件组是逻辑数据源，和矢量业务分类树互不依赖。 */
@Data
@TableName(value = "gis_file_group", schema = "ocean_gis")
public class GisFileGroup {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long dataSetId;
    private String name;
    private String format;
    private Long entryFileId;
    private String assemblyStatus;
    private String parseStatus;
    private Long inputRevision;
    /** 仅完整且非重复的组占用内容指纹。 */
    private String fingerprint;
    private Long duplicateOfGroupId;
    private Long matchedRevision;
    private String readOptionsJson;
    private String errorCode;
    private String errorMessage;
    /** 从当前解析元数据读取，不增加文件组存储字段。 */
    @TableField(exist = false)
    private tools.jackson.databind.JsonNode geometryQuality;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    @TableLogic
    private Integer deleted;
}
