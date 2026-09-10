package org.ocean.admin.platform.workbench.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 实时系统指标
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SystemMetricsDTO {
    private double cpuUsage;
    private double gpuUsage;
    private double memoryUsage;
    private double totalMemoryGb;
    private double usedMemoryGb;
    private double freeMemoryGb;
    private long timestamp;
}

