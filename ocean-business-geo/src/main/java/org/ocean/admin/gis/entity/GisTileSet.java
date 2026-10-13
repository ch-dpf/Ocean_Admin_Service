package org.ocean.admin.gis.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 一次处理任务唯一生成的静态瓦片集。 */
@Data
@TableName(value = "gis_tile_set", schema = "ocean_gis")
public class GisTileSet {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long taskId;
    private String tileType;
    private String tileSetStatus;
    private String outputKey;
    private String targetCrs;
    private String tileProfile;
    private String outputFormat;
    private Integer minZoom;
    private Integer maxZoom;
    private String manifestKey;
    private String errorMessage;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
