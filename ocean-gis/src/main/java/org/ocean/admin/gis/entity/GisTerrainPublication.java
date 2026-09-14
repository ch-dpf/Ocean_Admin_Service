package org.ocean.admin.gis.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Cesium quantized-mesh 地形发布记录。 */
@Data
@TableName(value = "gis_terrain_publication", schema = "ocean_gis")
public class GisTerrainPublication {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String serviceCode;
    private Long sourceTaskId;
    private Long publishTaskId;
    private Long dataSetId;
    private String outputKey;
    private String status;
    private LocalDateTime publishTime;
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
