package org.ocean.admin.platform.workbench.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * API 每日调用频率统计
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ApiDailyFrequencyDTO {
    private String date;
    private long count;
}

