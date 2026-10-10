package org.ocean.admin.gis.dto;

/** 任务结果或当前阶段工作量的计量单位。 */
public enum TaskProgressUnit {
    FILE, // 文件
    INPUT, // 输入
    BYTE, // 字节
    BLOCK, // 块
    TILE, // 瓦片
    OVERVIEW_LEVEL // 覆盖等级
}
