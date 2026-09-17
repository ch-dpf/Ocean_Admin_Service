package org.ocean.admin.gis.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ocean.admin.gis.entity.GisDataSet;
import org.ocean.admin.gis.entity.GisFileMeta;
import org.ocean.admin.gis.mapper.GisDataSetMapper;
import org.ocean.admin.gis.mapper.GisFileMetaMapper;
import org.ocean.admin.gis.vo.GisFileMetaVO;
import org.ocean.admin.kernel.common.PageResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** GIS 文件元数据服务。 */
@Service
@Slf4j
@RequiredArgsConstructor
public class GisFileMetaService {

    private static final Set<String> STORAGE_TYPES = Set.of("LOCAL", "MINIO", "S3");
    private static final Set<String> UPLOAD_STATUSES = Set.of("PENDING", "READY", "FAILED");

    private final GisFileMetaMapper gisFileMetaMapper;
    private final GisDataSetMapper gisDataSetMapper;
    private final GisDataSetService gisDataSetService;

    public PageResult<List<GisFileMetaVO>> getFileMetaPage(
            Integer current,
            Integer size,
            Long dataSetId,
            Long categoryId,
            String originalName,
            String extension,
            String uploadStatus) {
        long currentPage = current == null || current < 1 ? 1L : current;
        long pageSize = size == null || size < 1 ? 10L : Math.min(size, 100);
        String normalizedName = trimToNull(originalName);
        String normalizedExtension = normalizeExtension(extension);
        String normalizedStatus = normalizeUpper(uploadStatus);
        if (normalizedStatus != null && !UPLOAD_STATUSES.contains(normalizedStatus)) {
            throw new IllegalArgumentException("上传状态只能为 PENDING、READY 或 FAILED");
        }

        LambdaQueryWrapper<GisFileMeta> query = new LambdaQueryWrapper<GisFileMeta>()
                .eq(dataSetId != null, GisFileMeta::getDataSetId, dataSetId)
                .apply(categoryId != null,
                        "data_set_id IN (SELECT id FROM ocean_gis.gis_data_set "
                                + "WHERE category_id = {0} AND deleted = 0)",
                        categoryId)
                .like(normalizedName != null, GisFileMeta::getOriginalName, normalizedName)
                .eq(normalizedExtension != null, GisFileMeta::getExtension, normalizedExtension)
                .eq(normalizedStatus != null, GisFileMeta::getUploadStatus, normalizedStatus)
                .orderByDesc(GisFileMeta::getCreateTime);
        Page<GisFileMeta> page = gisFileMetaMapper.selectPage(new Page<>(currentPage, pageSize), query);
        List<GisFileMetaVO> records = page.getRecords().stream().map(this::toVO).toList();
        populateCategoryIds(records);
        return new PageResult<>(page.getCurrent(), page.getSize(), page.getTotal(), records);
    }

    public PageResult<List<GisFileMetaVO>> getDeletedFileMetaPage(
            Integer current,
            Integer size,
            Long dataSetId,
            Long categoryId,
            String originalName,
            String extension,
            String uploadStatus) {
        long currentPage = current == null || current < 1 ? 1L : current;
        long pageSize = size == null || size < 1 ? 10L : Math.min(size, 100);
        String normalizedName = trimToNull(originalName);
        String normalizedExtension = normalizeExtension(extension);
        String normalizedStatus = normalizeUpper(uploadStatus);
        if (normalizedStatus != null && !UPLOAD_STATUSES.contains(normalizedStatus)) {
            throw new IllegalArgumentException("上传状态只能为 PENDING、READY 或 FAILED");
        }

        Page<GisFileMetaVO> page = gisFileMetaMapper.selectDeletedPage(
                new Page<>(currentPage, pageSize),
                dataSetId,
                categoryId,
                normalizedName,
                normalizedExtension,
                normalizedStatus);
        return new PageResult<>(page.getCurrent(), page.getSize(), page.getTotal(), page.getRecords());
    }

    @Transactional(rollbackFor = Exception.class)
    public GisFileMetaVO createFileMeta(GisFileMetaVO reqVO) {
        validateRequest(reqVO, false);
        ensureDataSetExists(reqVO.getDataSetId());

        String storageType = normalizeUpper(reqVO.getStorageType());
        if (storageType == null) {
            storageType = "LOCAL";
        }
        String storageKey = reqVO.getStorageKey().trim();
        ensureStorageKeyAvailable(storageType, storageKey, null);

        LocalDateTime now = LocalDateTime.now();
        GisFileMeta meta = new GisFileMeta();
        applyEditableFields(meta, reqVO, storageType, storageKey);
        meta.setUploadStatus(defaultStatus(reqVO.getUploadStatus()));
        meta.setCleanupStatus("NOT_REQUIRED");
        meta.setCreateTime(now);
        meta.setUpdateTime(now);
        meta.setDeleted(0);
        if (gisFileMetaMapper.insert(meta) != 1) {
            throw new IllegalStateException("文件元数据创建失败");
        }
        if ("READY".equals(meta.getUploadStatus())) {
            gisDataSetService.incrementFileCount(meta.getDataSetId(), 1);
        }
        log.info("创建 GIS 文件元数据成功: id={}, dataSetId={}, storageKey={}",
                meta.getId(), meta.getDataSetId(), meta.getStorageKey());
        return toVOWithCategory(meta);
    }

    @Transactional(rollbackFor = Exception.class)
    public GisFileMetaVO updateFileMeta(GisFileMetaVO reqVO) {
        validateRequest(reqVO, true);
        GisFileMeta existing = getRequired(reqVO.getId());
        ensureDataSetExists(reqVO.getDataSetId());

        String storageType = normalizeUpper(reqVO.getStorageType());
        if (storageType == null) {
            storageType = "LOCAL";
        }
        String storageKey = reqVO.getStorageKey().trim();
        ensureStorageKeyAvailable(storageType, storageKey, reqVO.getId());

        GisFileMeta updatedMeta = new GisFileMeta();
        updatedMeta.setId(existing.getId());
        applyEditableFields(updatedMeta, reqVO, storageType, storageKey);
        updatedMeta.setImportExportRecordId(existing.getImportExportRecordId());
        updatedMeta.setCleanupStatus(existing.getCleanupStatus());
        String requestedStatus = normalizeUpper(reqVO.getUploadStatus());
        updatedMeta.setUploadStatus(requestedStatus == null ? existing.getUploadStatus() : requestedStatus);
        updatedMeta.setUpdateTime(LocalDateTime.now());
        int updated = gisFileMetaMapper.update(null,
                new LambdaUpdateWrapper<GisFileMeta>()
                        .eq(GisFileMeta::getId, existing.getId())
                        .set(GisFileMeta::getDataSetId, updatedMeta.getDataSetId())
                        .set(GisFileMeta::getOriginalName, updatedMeta.getOriginalName())
                        .set(GisFileMeta::getStorageName, updatedMeta.getStorageName())
                        .set(GisFileMeta::getStorageKey, updatedMeta.getStorageKey())
                        .set(GisFileMeta::getStorageType, updatedMeta.getStorageType())
                        .set(GisFileMeta::getExtension, updatedMeta.getExtension())
                        .set(GisFileMeta::getSizeBytes, updatedMeta.getSizeBytes())
                        .set(GisFileMeta::getSha256, updatedMeta.getSha256())
                        .set(GisFileMeta::getUploadedBy, updatedMeta.getUploadedBy())
                        .set(GisFileMeta::getUploadStatus, updatedMeta.getUploadStatus())
                        .set(GisFileMeta::getErrorMessage, updatedMeta.getErrorMessage())
                        .set(GisFileMeta::getUpdateTime, updatedMeta.getUpdateTime()));
        if (updated != 1) {
            throw new IllegalStateException("文件元数据更新失败: " + reqVO.getId());
        }
        updateReadyFileCount(existing, updatedMeta);
        updatedMeta.setCreateTime(existing.getCreateTime());
        updatedMeta.setDeleted(existing.getDeleted());
        log.info("更新 GIS 文件元数据成功: id={}", reqVO.getId());
        return toVOWithCategory(updatedMeta);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteFileMeta(Long id) {
        GisFileMeta existing = getRequired(id);
        if (gisFileMetaMapper.deleteById(id) != 1) {
            throw new IllegalStateException("文件元数据删除失败: " + id);
        }
        if ("READY".equals(existing.getUploadStatus())) {
            gisDataSetService.decrementFileCount(existing.getDataSetId(), 1);
        }
        log.info("删除 GIS 文件元数据成功: id={}", id);
    }

    public GisFileMetaVO getFileMetaDetail(Long id) {
        return toVOWithCategory(getRequired(id));
    }

    /** 供文件处理编排读取完整的已入库元数据。 */
    public GisFileMeta getRequiredEntity(Long id) {
        return getRequired(id);
    }

    private GisFileMeta getRequired(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("文件元数据ID不能为空");
        }
        GisFileMeta meta = gisFileMetaMapper.selectById(id);
        if (meta == null) {
            throw new IllegalArgumentException("文件元数据不存在或已删除: " + id);
        }
        return meta;
    }

    private void validateRequest(GisFileMetaVO reqVO, boolean requireId) {
        if (reqVO == null) {
            throw new IllegalArgumentException("文件元数据请求不能为空");
        }
        if (requireId && reqVO.getId() == null) {
            throw new IllegalArgumentException("文件元数据ID不能为空");
        }
        if (reqVO.getDataSetId() == null) {
            throw new IllegalArgumentException("数据集ID不能为空");
        }
        requireText(reqVO.getOriginalName(), "原始文件名不能为空");
        requireText(reqVO.getStorageName(), "存储文件名不能为空");
        requireText(reqVO.getStorageKey(), "存储 Key 不能为空");
        if (reqVO.getSizeBytes() == null || reqVO.getSizeBytes() < 0) {
            throw new IllegalArgumentException("文件大小不能为空且不能小于0");
        }

        String storageType = normalizeUpper(reqVO.getStorageType());
        if (storageType != null && !STORAGE_TYPES.contains(storageType)) {
            throw new IllegalArgumentException("存储类型只能为 LOCAL、MINIO 或 S3");
        }
        String uploadStatus = normalizeUpper(reqVO.getUploadStatus());
        if (uploadStatus != null && !UPLOAD_STATUSES.contains(uploadStatus)) {
            throw new IllegalArgumentException("上传状态只能为 PENDING、READY 或 FAILED");
        }
        String sha256 = trimToNull(reqVO.getSha256());
        if (sha256 != null && !sha256.matches("(?i)[0-9a-f]{64}")) {
            throw new IllegalArgumentException("SHA-256 必须为64位十六进制字符串");
        }
    }

    private void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }

    private void ensureDataSetExists(Long dataSetId) {
        GisDataSet dataSet = gisDataSetMapper.selectById(dataSetId);
        if (dataSet == null) {
            throw new IllegalArgumentException("数据集不存在或已删除: " + dataSetId);
        }
    }

    private void ensureStorageKeyAvailable(String storageType, String storageKey, Long excludedId) {
        LambdaQueryWrapper<GisFileMeta> query = new LambdaQueryWrapper<GisFileMeta>()
                .eq(GisFileMeta::getStorageType, storageType)
                .eq(GisFileMeta::getStorageKey, storageKey)
                .ne(excludedId != null, GisFileMeta::getId, excludedId);
        if (gisFileMetaMapper.exists(query)) {
            throw new IllegalArgumentException("存储位置已存在元数据: " + storageType + ":" + storageKey);
        }
    }

    private void applyEditableFields(
            GisFileMeta target, GisFileMetaVO source, String storageType, String storageKey) {
        target.setDataSetId(source.getDataSetId());
        target.setOriginalName(source.getOriginalName().trim());
        target.setStorageName(source.getStorageName().trim());
        target.setStorageKey(storageKey);
        target.setStorageType(storageType);
        target.setExtension(normalizeExtension(source.getExtension()));
        target.setSizeBytes(source.getSizeBytes());
        target.setSha256(normalizeLower(source.getSha256()));
        target.setUploadedBy(source.getUploadedBy());
        target.setErrorMessage(trimToNull(source.getErrorMessage()));
    }

    private void updateReadyFileCount(GisFileMeta existing, GisFileMeta updated) {
        boolean wasReady = "READY".equals(existing.getUploadStatus());
        boolean isReady = "READY".equals(updated.getUploadStatus());
        boolean moved = !existing.getDataSetId().equals(updated.getDataSetId());
        if (wasReady && (!isReady || moved)) {
            gisDataSetService.decrementFileCount(existing.getDataSetId(), 1);
        }
        if (isReady && (!wasReady || moved)) {
            gisDataSetService.incrementFileCount(updated.getDataSetId(), 1);
        }
    }

    private GisFileMetaVO toVO(GisFileMeta meta) {
        GisFileMetaVO result = new GisFileMetaVO();
        result.setId(meta.getId());
        result.setDataSetId(meta.getDataSetId());
        result.setImportExportRecordId(meta.getImportExportRecordId());
        result.setOriginalName(meta.getOriginalName());
        result.setStorageName(meta.getStorageName());
        result.setStorageKey(meta.getStorageKey());
        result.setStorageType(meta.getStorageType());
        result.setExtension(meta.getExtension());
        result.setSizeBytes(meta.getSizeBytes());
        result.setSha256(meta.getSha256());
        result.setUploadedBy(meta.getUploadedBy());
        result.setUploadStatus(meta.getUploadStatus());
        result.setCleanupStatus(meta.getCleanupStatus());
        result.setErrorMessage(meta.getErrorMessage());
        result.setCreateTime(meta.getCreateTime());
        result.setUpdateTime(meta.getUpdateTime());
        return result;
    }

    private GisFileMetaVO toVOWithCategory(GisFileMeta meta) {
        GisFileMetaVO result = toVO(meta);
        populateCategoryIds(List.of(result));
        return result;
    }

    private void populateCategoryIds(List<GisFileMetaVO> records) {
        Set<Long> dataSetIds = records.stream()
                .map(GisFileMetaVO::getDataSetId)
                .collect(Collectors.toSet());
        if (dataSetIds.isEmpty()) {
            return;
        }
        Map<Long, GisDataSet> dataSetsById = gisDataSetMapper.selectList(
                        new LambdaQueryWrapper<GisDataSet>().in(GisDataSet::getId, dataSetIds))
                .stream()
                .collect(Collectors.toMap(GisDataSet::getId, Function.identity()));
        records.forEach(record -> {
            GisDataSet dataSet = dataSetsById.get(record.getDataSetId());
            if (dataSet != null) {
                record.setCategoryId(dataSet.getCategoryId());
            }
        });
    }

    private String defaultStatus(String value) {
        String normalized = normalizeUpper(value);
        return normalized == null ? "READY" : normalized;
    }

    private String normalizeExtension(String value) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            return null;
        }
        while (normalized.startsWith(".")) {
            normalized = normalized.substring(1);
        }
        return trimToNull(normalized) == null ? null : normalized.toLowerCase(Locale.ROOT);
    }

    private String normalizeUpper(String value) {
        String normalized = trimToNull(value);
        return normalized == null ? null : normalized.toUpperCase(Locale.ROOT);
    }

    private String normalizeLower(String value) {
        String normalized = trimToNull(value);
        return normalized == null ? null : normalized.toLowerCase(Locale.ROOT);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
