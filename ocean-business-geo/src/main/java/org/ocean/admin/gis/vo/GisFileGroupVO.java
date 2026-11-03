package org.ocean.admin.gis.vo;

import jakarta.validation.constraints.Size;
import org.ocean.admin.gis.entity.GisFileGroup;
import org.ocean.admin.gis.entity.GisFileMeta;

import java.util.List;

/** 组详情携带物理成员，解析配置仅作用于该逻辑数据源。 */
public record GisFileGroupVO(GisFileGroup group, Long effectiveGroupId, List<GisFileMeta> members) {
    public record Query(Long dataSetId, String name, String format, String assemblyStatus,
                        String parseStatus, Boolean includeDuplicates, Long current, Long size) { }
    public record Options(@Size(max = 10000) String sourceCrs,
                          @Size(max = 255) String xField,
                          @Size(max = 255) String yField) { }
}
