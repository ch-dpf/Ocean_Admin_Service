package org.ocean.admin.gis.vo;

import jakarta.validation.constraints.Size;
import org.ocean.admin.gis.entity.GisFileGroup;
import org.ocean.admin.gis.entity.GisFileMeta;

import java.util.List;
import java.time.LocalDateTime;

/** 组详情携带物理成员，解析配置仅作用于该逻辑数据源。 */
public record GisFileGroupVO(Group group, Long effectiveGroupId, List<GisFileMeta> members) {
    /** 后端统一维护的格式能力，运行环境不可用时禁止新接收。 */
    public record FormatCapability(String format, List<Integer> categoryIds,
            List<String> entryExtensions, List<String> requiredExtensions, List<String> optionalExtensions,
            boolean available, String unavailableReason,
            List<org.ocean.admin.gis.entity.GisFileFormat.ReadOption> readOptions, List<String> notices) { }

    /** 文件组公开字段，持久化注解与后续表结构变更不会直接改变接口。 */
    public record Group(Long id,
            Long dataSetId,
            String name,
            String format,
            Long entryFileId,
            String assemblyStatus,
            String parseStatus,
            Long inputRevision,
            String fingerprint,
            Long duplicateOfGroupId,
            Long matchedRevision,
            String readOptionsJson,
            String errorCode,
            String errorMessage,
            LocalDateTime createTime,
            LocalDateTime updateTime,
            Integer deleted) {
        public static Group from(GisFileGroup group) {
            return new Group(group.getId(), group.getDataSetId(), group.getName(), group.getFormat(), group.getEntryFileId(), group.getAssemblyStatus(), group.getParseStatus(), group.getInputRevision(), group.getFingerprint(), group.getDuplicateOfGroupId(), group.getMatchedRevision(), group.getReadOptionsJson(), group.getErrorCode(), group.getErrorMessage(), group.getCreateTime(), group.getUpdateTime(), group.getDeleted());
        }
    }

    public record Query(Long dataSetId, String name, String format, String assemblyStatus,
                        String parseStatus, Boolean includeDuplicates, Long current, Long size) { }
    public record Options(@Size(max = 10000) String sourceCrs,
                          @Size(max = 255) String xField,
                          @Size(max = 255) String yField) { }
}
