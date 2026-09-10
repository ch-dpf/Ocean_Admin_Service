package org.ocean.admin.platform.workbench.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 通用趋势统计项
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TrendFrequencyDTO {
    private String label;
    private long count;
}

