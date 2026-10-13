package org.ocean.admin.gis.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Gis 数据集实体类
 *
 * @author DeepOcean
 * @since 2026-09-11
 */
@Data
@TableName(value = "gis_data_set",schema = "ocean_gis")
public class GisDataSet {
    /**
     * 主键ID
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 数据集名称
     */
    private String dataSetName;

    /**
     * 数据类别:0-影像数据、1-地形数据、2-矢量数据
     */
    private Long categoryId;

    /**
     * 数据集编码
     */
    private String dataSetCode;

    /**
     * 数据集描述
     */
    private String description;

    /**
     * 文件总数
     */
    private Integer fileCount;

    /**
     * 数据集创建时间
     */
    private LocalDateTime createTime;

    /**
     * 数据集更新时间
     */
    private LocalDateTime updateTime;

    /**
     * 逻辑删除：0-未删除，1-已删除
     */
    @TableLogic
    private Integer deleted;


}
