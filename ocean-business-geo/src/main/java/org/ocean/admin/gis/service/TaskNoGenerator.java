package org.ocean.admin.gis.service;

import org.ocean.admin.gis.processing.GisProcessingType;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

/** 统一生成 GIS 处理、归档及工作区进度编号。 */
public final class TaskNoGenerator {
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private TaskNoGenerator() {
    }

    public static String generateArchiveTaskNo() {
        return generate("archive", LocalDateTime.now());
    }

    /** 工作区接入仅生成实时进度编号，不创建gis_processing_task记录。 */
    public static String generateWorkspaceImportTaskNo() {
        return generate("workspace", LocalDateTime.now());
    }

    public static String generateProcessingTaskNo(GisProcessingType type) {
        if (type == null) {
            throw new IllegalArgumentException("GIS 处理类型不能为空");
        }
        return generate(type.name().toLowerCase(Locale.ROOT), LocalDateTime.now());
    }

    private static String generate(String prefix, LocalDateTime timestamp) {
        return prefix + "_" + timestamp.format(TIMESTAMP) + "_"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
