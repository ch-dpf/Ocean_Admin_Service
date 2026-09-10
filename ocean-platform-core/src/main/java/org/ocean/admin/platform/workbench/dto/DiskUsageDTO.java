package org.ocean.admin.platform.workbench.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 磁盘使用情况
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DiskUsageDTO {
    private String name;
    private double totalGb;
    private double usedGb;
    private double freeGb;
}

