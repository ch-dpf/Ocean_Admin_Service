package org.ocean.admin.gis.progress;

/** 任务结果或当前阶段工作量的计量单位。 */
public enum ProgressUnit {
    ENTRY, // 扫描条目（文件与目录）
    FILE, // 文件
    INPUT, // 输入
    BYTE, // 字节
    BLOCK, // 块
    PIXEL, // 已扫描像素
    SEGMENT, // 源边界段
    FEATURE, // 源要素或几何部分
    OPERATION, // 不可拆分的操作
    TILE, // 瓦片
    OVERVIEW_LEVEL // 覆盖等级
}
