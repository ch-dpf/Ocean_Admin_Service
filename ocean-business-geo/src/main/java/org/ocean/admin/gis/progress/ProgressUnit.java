package org.ocean.admin.gis.progress;

/** 任务结果或当前阶段工作量的计量单位。 */
public enum ProgressUnit {
    ENTRY, // 扫描条目（文件与目录）
    FILE, // 文件
    INPUT, // 输入
    BYTE, // 字节
    BLOCK, // 块
    TILE, // 瓦片
    OVERVIEW_LEVEL // 覆盖等级
}
