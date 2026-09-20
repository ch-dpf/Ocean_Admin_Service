package org.ocean.admin.gis.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ocean.admin.gis.config.FileUploadConfig;
import org.ocean.admin.gis.dto.GisStoredFile;
import org.ocean.admin.gis.entity.GisDataSet;
import org.ocean.admin.gis.entity.GisFileMeta;
import org.ocean.admin.gis.entity.GisFileOptRecord;
import org.ocean.admin.gis.mapper.GisDataSetMapper;
import org.ocean.admin.gis.util.FileUploadUtil;
import org.ocean.admin.kernel.audit.CurrentOperator;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 文件上传服务。 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FileUploadService {

    private static final String UPLOAD_SESSION_KEY_PREFIX = "ocean-admin:gis-upload-session:";
    private static final String UPLOAD_SESSION_CLAIM_KEY_PREFIX =
            "ocean-admin:gis-upload-session-claim:";
    private static final DefaultRedisScript<Long> RELEASE_SESSION_CLAIM_SCRIPT =
            new DefaultRedisScript<>(
                    "if redis.call('get', KEYS[1]) == ARGV[1] "
                            + "then return redis.call('del', KEYS[1]) else return 0 end",
                    Long.class);
    private static final int MAX_FILES_PER_REQUEST = 100;
    private static final long MAX_TOTAL_SIZE = 5L * 1024 * 1024 * 1024;
    private static final int STORAGE_PROGRESS_END = 99;
    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
            "tif", "tiff", "img", "jp2", "png", "jpg", "jpeg", "tfw", "prj", "aux", "xml");
    private static final Set<String> TERRAIN_EXTENSIONS = Set.of(
            "tif", "tiff", "dem", "hgt", "terrain", "asc", "prj", "xml");
    private static final Set<String> VECTOR_EXTENSIONS = Set.of(
            "shp", "shx", "dbf", "prj", "cpg", "sbn", "sbx", "geojson", "json", "kml", "kmz", "gpkg");

    private final GisDataSetMapper gisDataSetMapper;
    private final FileUploadUtil fileUploadUtil;
    private final AsyncTaskService asyncTaskService;
    private final GisFileOptRecordService gisFileOptRecordService;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final FileUploadConfig uploadConfig;

    public Map<String, Object> importTaskRegistry(Long dataSetId, Integer totalCount) {
        if (dataSetId == null) {
            throw new IllegalArgumentException("数据集ID不能为空");
        }
        if (totalCount == null || totalCount < 1 || totalCount > MAX_FILES_PER_REQUEST) {
            throw new IllegalArgumentException(
                    "预计导入文件数量必须在1到" + MAX_FILES_PER_REQUEST + "之间");
        }
        GisDataSet dataSet = gisDataSetMapper.selectById(dataSetId);
        if (dataSet == null) {
            throw new IllegalArgumentException("数据集不存在或已删除: " + dataSetId);
        }
        if (dataSet.getCategoryId() == null
                || dataSet.getCategoryId() < 0
                || dataSet.getCategoryId() > 2) {
            throw new IllegalArgumentException("未知GIS数据类别: " + dataSet.getCategoryId());
        }

        String taskId = "GIS_IMPORT_" + IdWorker.getIdStr();
        LocalDateTime now = LocalDateTime.now();
        if (uploadConfig.getSessionTtl() == null
                || uploadConfig.getSessionTtl().isZero()
                || uploadConfig.getSessionTtl().isNegative()) {
            throw new IllegalStateException("GIS上传会话超时配置必须大于0");
        }
        LocalDateTime expiresAt = now.plus(uploadConfig.getSessionTtl());
        CurrentOperator operator = currentOperator();

        Map<String, Object> session = new LinkedHashMap<>();
        session.put("taskId", taskId);
        session.put("dataSetId", dataSetId);
        session.put("totalCount", totalCount);
        session.put("operatorId", operator == null ? null : operator.userId());
        session.put("username", operator == null ? null : operator.username());
        session.put("status", "WAITING_UPLOAD");
        session.put("stage", "waiting_upload");
        session.put("createdAt", now.toString());
        session.put("expiresAt", expiresAt.toString());

        String sessionKey = UPLOAD_SESSION_KEY_PREFIX + taskId;
        boolean sessionCreated = false;
        try {
            String sessionJson = objectMapper.writeValueAsString(session);
            Boolean created = redisTemplate.opsForValue().setIfAbsent(
                    sessionKey, sessionJson, uploadConfig.getSessionTtl());
            if (!Boolean.TRUE.equals(created)) {
                throw new IllegalStateException("上传任务编号冲突，请重试");
            }
            sessionCreated = true;
            asyncTaskService.registerTask(
                    taskId, "Gis文件批量导入", totalCount, "GIS_IMPORT");
            asyncTaskService.updateProgress(
                    taskId, 0, "waiting_upload", "等待上传文件");
        } catch (RuntimeException ex) {
            if (sessionCreated) {
                try {
                    redisTemplate.delete(sessionKey);
                } catch (RuntimeException cleanupEx) {
                    ex.addSuppressed(cleanupEx);
                }
            }
            throw ex;
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taskId", taskId);
        data.put("dataSetId", dataSetId);
        data.put("totalCount", totalCount);
        data.put("status", "WAITING_UPLOAD");
        data.put("stage", "waiting_upload");
        data.put("expiresAt", expiresAt);
        return data;
    }

    private CurrentOperator currentOperator() {
        if (!(RequestContextHolder.getRequestAttributes()
                instanceof ServletRequestAttributes attributes)) {
            return null;
        }
        HttpServletRequest request = attributes.getRequest();
        Object value = request.getAttribute(CurrentOperator.REQUEST_ATTRIBUTE);
        return value instanceof CurrentOperator operator ? operator : null;
    }

    public Map<String, Object> importBatch(String taskId, List<MultipartFile> files) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("导入任务ID不能为空");
        }
        taskId = taskId.trim().toUpperCase(Locale.ROOT);
        if (!taskId.matches("GIS_IMPORT_[0-9]{1,32}")) {
            throw new IllegalArgumentException("导入任务ID不合法");
        }

        ClaimedUploadSession claimedSession = claimUploadSession(taskId);
        UploadSession session = claimedSession.session();
        GisDataSet dataSet;
        GisFileOptRecord record;
        try {
            if (files == null || files.size() != session.totalCount()) {
                throw new IllegalArgumentException(
                        "实际文件数量必须与创建任务时的预计数量一致: " + session.totalCount());
            }
            validateSessionOwner(session);
            dataSet = validateRequest(session.dataSetId(), files);
            record = gisFileOptRecordService.createImportRecord(
                    taskId, session.dataSetId(), session.totalCount(), session.operatorId());
        } catch (RuntimeException ex) {
            releaseUploadSessionClaim(claimedSession);
            throw ex;
        }
        consumeUploadSession(claimedSession);

        List<GisFileMeta> fileMetas = new ArrayList<>(files.size());
        List<GisStoredFile> storedFiles = new ArrayList<>(files.size());
        int completedCount = 0;
        int failedCount = 0;
        long totalBytes = files.stream()
                .filter(file -> file != null)
                .mapToLong(file -> Math.max(0L, file.getSize()))
                .sum();
        long processedBytes = 0L;
        int[] lastStorageProgress = {0};
        asyncTaskService.updateProgress(
                taskId, 0, "storing", "开始存储文件");

        for (int index = 0; index < files.size(); index++) {
            MultipartFile file = files.get(index);
            GisFileMeta meta = newBaseMeta(record.getDataSetId(), file, index + 1);
            meta.setImportExportRecordId(record.getId());
            long completedBytesBeforeFile = processedBytes;
            GisStoredFile storedFile = null;
            try {
                validateFileCategory(dataSet, file);
                String currentFileName = meta.getOriginalName();
                storedFile = fileUploadUtil.store(
                        dataSet.getDataSetCode(), taskId, file, copiedBytes ->
                        reportStorageProgress(
                                record.getRecordNo(),
                                completedBytesBeforeFile + copiedBytes,
                                totalBytes,
                                currentFileName,
                                lastStorageProgress));

                meta.setStorageName(storedFile.getStorageName());
                meta.setStorageKey(storedFile.getStorageKey());
                meta.setStorageType(storedFile.getStorageType());
                meta.setExtension(FileUploadUtil.extensionOf(file.getOriginalFilename()));
                meta.setSizeBytes(storedFile.getSizeBytes());
                meta.setSha256(storedFile.getSha256());
                meta.setUploadStatus("READY");
                meta.setCleanupStatus("NOT_REQUIRED");
                storedFiles.add(storedFile);
                completedCount++;
            } catch (Exception ex) {
                failedCount++;
                boolean cleaned = true;
                String residualStorageName = null;
                String residualStorageKey = null;
                try {
                    if (storedFile != null) {
                        residualStorageName = storedFile.getStorageName();
                        residualStorageKey = storedFile.getStorageKey();
                        fileUploadUtil.deleteStored(residualStorageKey);
                    }
                } catch (Exception cleanupEx) {
                    cleaned = false;
                    ex.addSuppressed(cleanupEx);
                    log.warn("清理失败文件异常: taskId={}, file={}",
                            taskId, meta.getOriginalName(), cleanupEx);
                }
                meta.setStorageName(cleaned ? null : residualStorageName);
                meta.setStorageKey(cleaned ? null : residualStorageKey);
                meta.setUploadStatus("FAILED");
                meta.setCleanupStatus(cleaned ? "COMPLETED" : "FAILED");
                meta.setErrorMessage(abbreviate(ex.getMessage(), 1000));
                log.warn("GIS文件存储失败: taskId={}, file={}, error={}",
                        taskId, meta.getOriginalName(), ex.getMessage());
            }
            fileMetas.add(meta);
            processedBytes += file == null ? 0L : Math.max(0L, file.getSize());
            reportStorageProgress(
                    taskId, processedBytes, totalBytes, meta.getOriginalName(), lastStorageProgress);
        }

        try {
            gisFileOptRecordService.saveImportResult(
                    record, fileMetas, completedCount, failedCount);
        } catch (Exception ex) {
            for (GisStoredFile storedFile : storedFiles) {
                try {
                    fileUploadUtil.deleteStored(storedFile.getStorageKey());
                } catch (Exception cleanupEx) {
                    ex.addSuppressed(cleanupEx);
                    log.warn("回滚已存储文件失败: taskId={}, key={}",
                            taskId, storedFile.getStorageKey(), cleanupEx);
                }
            }
            try {
                gisFileOptRecordService.markPreparationFailed(record, ex.getMessage());
                asyncTaskService.finalizeTaskFailure(
                        taskId, "文件元数据批量入库失败: " + ex.getMessage());
            } catch (Exception finalizeEx) {
                ex.addSuppressed(finalizeEx);
            }
            throw ex;
        }

        asyncTaskService.updateProgressCounts(
                taskId, completedCount, failedCount, STORAGE_PROGRESS_END,
                "stored", "文件存储和元数据建档完成");
        asyncTaskService.finalizeTaskResult(
                taskId,
                failedCount == 0
                        ? "全部文件上传完成"
                        : "上传结束，成功" + completedCount + "个，失败" + failedCount + "个");

        String status = failedCount == 0
                ? "COMPLETED"
                : completedCount == 0 ? "FAILED" : "PARTIAL_FAILED";
        return buildResult(
                taskId, record.getDataSetId(), files.size(), completedCount, failedCount, status);
    }

    private ClaimedUploadSession claimUploadSession(String taskId) {
        String sessionKey = UPLOAD_SESSION_KEY_PREFIX + taskId;
        String claimKey = UPLOAD_SESSION_CLAIM_KEY_PREFIX + taskId;
        String claimToken = IdWorker.getIdStr();
        Boolean claimed = redisTemplate.opsForValue().setIfAbsent(
                claimKey, claimToken, uploadConfig.getSessionTtl());
        if (!Boolean.TRUE.equals(claimed)) {
            throw new IllegalStateException("上传任务正在处理，请勿重复提交: " + taskId);
        }

        try {
            String sessionJson = redisTemplate.opsForValue().get(sessionKey);
            if (sessionJson == null || sessionJson.isBlank()) {
                throw new IllegalArgumentException("上传任务不存在或已过期: " + taskId);
            }
            JsonNode root = objectMapper.readTree(sessionJson);
            String sessionTaskId = requiredText(root, "taskId");
            String status = requiredText(root, "status");
            if (!taskId.equals(sessionTaskId) || !"WAITING_UPLOAD".equals(status)) {
                throw new IllegalStateException("当前上传任务状态不允许提交: " + taskId);
            }
            UploadSession session = new UploadSession(
                    sessionTaskId,
                    requiredLong(root, "dataSetId"),
                    requiredInt(root, "totalCount"),
                    nullableLong(root, "operatorId"));
            return new ClaimedUploadSession(session, sessionKey, claimKey, claimToken);
        } catch (RuntimeException ex) {
            releaseUploadSessionClaim(
                    new ClaimedUploadSession(null, sessionKey, claimKey, claimToken));
            throw ex;
        }
    }

    private void validateSessionOwner(UploadSession session) {
        if (session.operatorId() == null) {
            return;
        }
        CurrentOperator operator = currentOperator();
        if (operator == null || !session.operatorId().equals(operator.userId())) {
            throw new IllegalStateException("当前用户无权提交该上传任务");
        }
    }

    private void consumeUploadSession(ClaimedUploadSession claimedSession) {
        try {
            Boolean deleted = redisTemplate.delete(claimedSession.sessionKey());
            if (!Boolean.TRUE.equals(deleted)) {
                log.warn("上传会话在正式记录创建后未能删除: taskId={}",
                        claimedSession.session().taskId());
            }
        } catch (RuntimeException ex) {
            log.warn("上传会话清理失败，将由TTL自动回收: taskId={}",
                    claimedSession.session().taskId(), ex);
        } finally {
            releaseUploadSessionClaim(claimedSession);
        }
    }

    private void releaseUploadSessionClaim(ClaimedUploadSession claimedSession) {
        try {
            redisTemplate.execute(
                    RELEASE_SESSION_CLAIM_SCRIPT,
                    List.of(claimedSession.claimKey()),
                    claimedSession.claimToken());
        } catch (RuntimeException ex) {
            log.warn("释放上传会话占用失败，将由TTL自动回收: key={}",
                    claimedSession.claimKey(), ex);
        }
    }

    private String requiredText(JsonNode root, String fieldName) {
        JsonNode value = root.get(fieldName);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalStateException("上传会话数据不完整: " + fieldName);
        }
        return value.asText();
    }

    private long requiredLong(JsonNode root, String fieldName) {
        JsonNode value = root.get(fieldName);
        return parseLong(value, fieldName);
    }

    private int requiredInt(JsonNode root, String fieldName) {
        JsonNode value = root.get(fieldName);
        long parsed = parseLong(value, fieldName);
        if (parsed < 1 || parsed > MAX_FILES_PER_REQUEST) {
            throw new IllegalStateException("上传会话文件数量不合法");
        }
        return (int) parsed;
    }

    private Long nullableLong(JsonNode root, String fieldName) {
        JsonNode value = root.get(fieldName);
        if (value == null || value.isNull()) {
            return null;
        }
        return parseLong(value, fieldName);
    }

    private long parseLong(JsonNode value, String fieldName) {
        if (value != null && value.isIntegralNumber() && value.canConvertToLong()) {
            return value.asLong();
        }
        if (value != null && value.isTextual()) {
            try {
                return Long.parseLong(value.asText().trim());
            } catch (NumberFormatException ignored) {
                // 统一由下方转换为上传会话数据异常。
            }
        }
        throw new IllegalStateException("上传会话数据不合法: " + fieldName);
    }

    private record UploadSession(
            String taskId,
            Long dataSetId,
            int totalCount,
            Long operatorId) {
    }

    private record ClaimedUploadSession(
            UploadSession session,
            String sessionKey,
            String claimKey,
            String claimToken) {
    }

    private GisFileMeta newBaseMeta(Long dataSetId, MultipartFile file, int index) {
        LocalDateTime now = LocalDateTime.now();
        GisFileMeta meta = new GisFileMeta();
        meta.setId(IdWorker.getId());
        meta.setDataSetId(dataSetId);
        meta.setOriginalName(displayFileName(file, index));
        meta.setExtension(extensionOrNull(meta.getOriginalName()));
        meta.setStorageType("LOCAL");
        meta.setSizeBytes(file == null ? 0L : Math.max(0L, file.getSize()));
        meta.setCleanupStatus("NOT_REQUIRED");
        meta.setCreateTime(now);
        meta.setUpdateTime(now);
        meta.setDeleted(0);
        return meta;
    }

    private void reportStorageProgress(
            String taskId,
            long stagedBytes,
            long totalBytes,
            String fileName,
            int[] lastProgress) {
        int progress = totalBytes <= 0
                ? STORAGE_PROGRESS_END
                : (int) Math.min(
                        STORAGE_PROGRESS_END,
                        stagedBytes * STORAGE_PROGRESS_END / totalBytes);
        if (progress <= lastProgress[0]) {
            return;
        }
        lastProgress[0] = progress;
        asyncTaskService.updateProgress(
                taskId,
                progress,
                "storing",
                "正在存储文件：" + fileName + "（"
                        + formatBytes(Math.min(stagedBytes, totalBytes)) + "/"
                        + formatBytes(totalBytes) + "）");
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0d);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0d * 1024));
        }
        return String.format(Locale.ROOT, "%.2f GB", bytes / (1024.0d * 1024 * 1024));
    }

    private void validateFileCategory(GisDataSet dataSet, MultipartFile file) {
        FileUploadUtil.validateMultipartFile(file);
        String extension = FileUploadUtil.extensionOf(file.getOriginalFilename());
        Set<String> allowed = switch (dataSet.getCategoryId().intValue()) {
            case 0 -> IMAGE_EXTENSIONS;
            case 1 -> TERRAIN_EXTENSIONS;
            case 2 -> VECTOR_EXTENSIONS;
            default -> throw new IllegalArgumentException(
                    "未知GIS数据类别: " + dataSet.getCategoryId());
        };
        if (!allowed.contains(extension)) {
            throw new IllegalArgumentException(
                    "文件类型与数据集类别不匹配: " + file.getOriginalFilename());
        }
    }

    private Map<String, Object> buildResult(
            String taskId,
            Long dataSetId,
            int totalCount,
            int acceptedCount,
            int failedCount,
            String status) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taskId", taskId);
        data.put("dataSetId", dataSetId);
        data.put("totalCount", totalCount);
        data.put("acceptedCount", acceptedCount);
        data.put("failedCount", failedCount);
        data.put("status", status);
        data.put("message", "文件存储和元数据建档已完成");
        return data;
    }

    private GisDataSet validateRequest(Long dataSetId, List<MultipartFile> files) {
        if (dataSetId == null) {
            throw new IllegalArgumentException("数据集ID不能为空");
        }
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("至少需要上传一个文件");
        }
        if (files.size() > MAX_FILES_PER_REQUEST) {
            throw new IllegalArgumentException("单次上传文件数量不能超过" + MAX_FILES_PER_REQUEST);
        }

        long totalSize = 0;
        for (MultipartFile file : files) {
            long size = file == null ? 0 : Math.max(0, file.getSize());
            if (size > MAX_TOTAL_SIZE - totalSize) {
                throw new IllegalArgumentException("单次上传文件总大小不能超过5GB");
            }
            totalSize += size;
        }

        GisDataSet dataSet = gisDataSetMapper.selectById(dataSetId);
        if (dataSet == null) {
            throw new IllegalArgumentException("数据集不存在或已删除: " + dataSetId);
        }
        if (dataSet.getCategoryId() == null
                || dataSet.getCategoryId() < 0
                || dataSet.getCategoryId() > 2) {
            throw new IllegalArgumentException("未知GIS数据类别: " + dataSet.getCategoryId());
        }
        return dataSet;
    }

    private String displayFileName(MultipartFile file, int index) {
        if (file == null || file.getOriginalFilename() == null
                || file.getOriginalFilename().isBlank()) {
            return "__unnamed_file_" + index;
        }
        try {
            String name = FileUploadUtil.safeOriginalName(file.getOriginalFilename());
            return name.length() <= 255 ? name : name.substring(0, 255);
        } catch (Exception ex) {
            return "__invalid_file_" + index;
        }
    }

    private String extensionOrNull(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 1 || dot == fileName.length() - 1) {
            return null;
        }
        String extension = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        return extension.matches("[a-z0-9]{1,16}") ? extension : null;
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
