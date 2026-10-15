package org.ocean.admin.gis.service;

import org.ocean.admin.gis.processing.GisProcessingType;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/** 统一生成和校验 GIS 业务任务及操作记录编号。 */
public final class TaskNoGenerator {
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final Pattern UPLOAD_TASK_NO =
            Pattern.compile("upload_[0-9]{14}_[a-f0-9]{12}");

    private TaskNoGenerator() {
    }

    public static String generateUploadTaskNo() {
        return generate("upload", LocalDateTime.now());
    }

    public static String generateDownloadTaskNo() {
        return generate("download", LocalDateTime.now());
    }

    public static String generateArchiveTaskNo() {
        return generate("archive", LocalDateTime.now());
    }

    public static String generateProcessingTaskNo(GisProcessingType type) {
        if (type == null) {
            throw new IllegalArgumentException("GIS 处理类型不能为空");
        }
        return generate(type.name().toLowerCase(Locale.ROOT), LocalDateTime.now());
    }

    public static String requireUploadTaskNo(String taskNo) {
        if (taskNo == null || taskNo.isBlank()) {
            throw new IllegalArgumentException("导入任务ID不能为空");
        }
        String normalized = taskNo.trim();
        if (!UPLOAD_TASK_NO.matcher(normalized).matches()) {
            throw new IllegalArgumentException("导入任务ID不合法");
        }
        return normalized;
    }

    private static String generate(String prefix, LocalDateTime timestamp) {
        return prefix + "_" + timestamp.format(TIMESTAMP) + "_"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
