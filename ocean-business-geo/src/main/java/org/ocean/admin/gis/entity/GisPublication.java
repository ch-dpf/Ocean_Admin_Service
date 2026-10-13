package org.ocean.admin.gis.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 地形、影像和矢量服务共用的发布记录。 */
@Data
@TableName(value = "gis_publication", schema = "ocean_gis")
public class GisPublication {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String serviceCode;
    private Long tileSetId;
    private Long dataSetId;
    private String status;
    private LocalDateTime publishTime;
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
