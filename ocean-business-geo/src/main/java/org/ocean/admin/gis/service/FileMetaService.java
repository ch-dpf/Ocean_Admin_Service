package org.ocean.admin.gis.service;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;

import org.ocean.admin.gis.mapper.GisVectorResourceMapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ocean.admin.gis.entity.GisDataSet;
import org.ocean.admin.gis.entity.GisFileMeta;
import org.ocean.admin.gis.entity.GisFileGroup;
import org.ocean.admin.gis.mapper.GisFileGroupMapper;
import org.ocean.admin.gis.dto.GisFileQuery;
import org.ocean.admin.gis.mapper.GisDataSetMapper;
import org.ocean.admin.gis.mapper.GisFileMetaMapper;
import org.ocean.admin.gis.mapper.GisProcessingInputMapper;
import org.ocean.admin.gis.util.FileUploadUtil;
import org.ocean.admin.gis.processing.input.ControlledWorkspaceResolver;
import org.ocean.admin.gis.vo.GisFileMetaVO;
import org.ocean.admin.kernel.common.PageResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import java.time.LocalDateTime;
import java.nio.file.Path;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;

/** GIS 文件元数据服务。 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FileMetaService {

    private final GisDictionaryService dictionaries;
    private final GisFileMetaMapper gisFileMetaMapper;
    private final GisFileGroupMapper fileGroupMapper;
    private final FileGroupService fileGroups;
    private final GisProcessingInputMapper processingInputMapper;
    private final GisDataSetMapper gisDataSetMapper;
    private final DataSetService dataSetService;
    private final FileUploadUtil fileUploadUtil;
    private final FileSpatialMetadataService spatialMetadataService;
    private final GisVectorResourceMapper gisVectorResourceMapper;
    private final VectorResourceService vectorResources;
    private final ControlledWorkspaceResolver workspaceResolver;
    private final ApplicationEventPublisher events;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<List<GisFileMetaVO>> getFileMetaPage(GisFileQuery query) {
        query.validate();
        dictionaries.validateOptional("metadataStatus", query.getMetadataStatus());
        dictionaries.validateOptional("fileStatus", query.getFileStatus());
        Page<GisFileMetaVO> page = gisFileMetaMapper.selectFilePage(
                new Page<>(query.getCurrent(), query.getSize()), query);
        spatialMetadataService.populate(page.getRecords(), query.isIncludeGeometry());
        return new PageResult<>(page.getCurrent(), page.getSize(), page.getTotal(), page.getRecords());
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteFileMeta(Long id) {
        GisFileMeta located = getRequired(id);
        if (gisDataSetMapper.selectActiveByIdForUpdate(located.getDataSetId()) == null)
            throw new IllegalArgumentException("数据集不存在或已删除");
        located = getRequired(id);
        GisFileGroup group = located.getFileGroupId() == null ? null : fileGroupMapper.lock(located.getFileGroupId());
        if (group != null && Set.of("QUEUED", "RUNNING").contains(group.getParseStatus()))
            throw new IllegalStateException("文件组正在解析，不能删除成员");
        if (group != null && group.getEntryFileId() != null) {
            ensureVectorSourceUnreferenced(group.getEntryFileId());
            ensureNoActiveProcessingTask(group.getEntryFileId());
        }
        GisFileMeta existing = getRequiredForUpdate(id);
        ensureMutable(existing);
        ensureVectorSourceUnreferenced(id);
        ensureNoActiveProcessingTask(id);
        validatePermanentDeleteStorage(existing);
        String storageKey = trimToNull(existing.getStorageKey());
        if ("UPLOAD".equals(existing.getSourceType()) && storageKey != null) {
            fileUploadUtil.stageStoredForTransactionalDeletion(List.of(storageKey));
        }
        processingInputMapper.detachTerminalByFileMetaId(id);
        if (gisFileMetaMapper.physicallyDeleteActiveById(id) != 1) {
            throw new IllegalStateException("文件物理删除失败: " + id);
        }
        if (existing.getFileGroupId() != null) fileGroups.invalidateMember(existing.getFileGroupId(), id);
        dataSetService.updateFileStatistics(existing, null);
        fileUploadUtil.deleteEmptyDataSetDirectory(existing.getDataSetId());
        events.publishEvent(new FileSpatialMetadataService.MetadataProgress(List.of(id), false));
        log.info("永久删除 GIS 文件成功: id={}, storageKey={}", id, storageKey);
    }

    /** 显式生成原始集合快照，不重新导入或覆盖已编辑的工作图层。 */
    @Transactional(rollbackFor = Exception.class)
    public List<org.ocean.admin.gis.entity.GisVectorSourceLayer> snapshotSource(Long id) throws IOException {
        GisFileMeta file = getRequired(id);
        if (gisDataSetMapper.selectActiveByIdForUpdate(file.getDataSetId()) == null)
            throw new IllegalArgumentException("数据集不存在");
        GisFileGroup group = file.getFileGroupId() == null ? null : fileGroupMapper.lock(file.getFileGroupId());
        if (group == null || group.getEntryFileId() == null) throw new IllegalStateException("请先完成文件组组装");
        if (Set.of("QUEUED", "RUNNING").contains(group.getParseStatus())) throw new IllegalStateException("文件组仍在解析");
        GisFileMeta entry = getRequired(group.getEntryFileId());
        if (gisVectorResourceMapper.sourceResource(entry.getId()) != null) return vectorResources.sourceLayers(entry.getId());
        var members = fileGroups.members(group.getId());
        for (var member : members) {
            if (!"RETAINED".equals(member.getRawRetention())) throw new IllegalStateException("原始文件已释放");
            ensureNoActiveProcessingTask(member.getId());
        }
        vectorResources.capture(entry, group, members, () -> { }, () -> { }, stage -> { });
        return vectorResources.sourceLayers(entry.getId());
    }

    public List<org.ocean.admin.gis.entity.GisVectorSourceLayer> sourceLayers(Long id) {
        var file = getRequired(id);
        var group = file.getFileGroupId() == null ? null : fileGroupMapper.selectById(file.getFileGroupId());
        return vectorResources.sourceLayers(group == null ? id : group.getEntryFileId());
    }

    /** 返回完整文件组的释放影响，来源关系不作为物理文件依赖。 */
    public Map<String, Object> releasePreview(Long id) {
        GisFileMeta file = getRequired(id);
        GisFileGroup group = file.getFileGroupId() == null ? null : fileGroupMapper.selectById(file.getFileGroupId());
        Long entryId = group == null ? id : group.getEntryFileId();
        var snapshot = entryId == null ? null : gisVectorResourceMapper.sourceResource(entryId);
        List<GisFileMeta> members = group == null ? List.of(file) : fileGroups.members(group.getId());
        var blockers = new ArrayList<String>();
        if (snapshot == null) blockers.add("尚无完整的原始要素快照");
        if (group != null && Set.of("QUEUED", "RUNNING").contains(group.getParseStatus())) blockers.add("文件组仍在解析");
        for (var member : members) {
            if (Set.of("WAITING", "QUEUED", "RUNNING").contains(member.getMetadataStatus())) blockers.add("文件仍在导入: " + member.getOriginalName());
            if (!processingInputMapper.selectActiveByFileMetaId(member.getId()).isEmpty()) blockers.add("处理任务仍在使用: " + member.getOriginalName());
        }
        return Map.of("fileIds", members.stream().map(member -> member.getId().toString()).toList(),
                "files", members.stream().map(member -> member.getOriginalName()).toList(),
                "reclaimBytes", members.stream().filter(member -> "UPLOAD".equals(member.getSourceType()) && "RETAINED".equals(member.getRawRetention()))
                        .mapToLong(GisFileMeta::getSizeBytes).sum(), "blockers", blockers,
                "sourceLayerCount", snapshot == null ? 0 : gisVectorResourceMapper.sourceLayers(snapshot.getId()).size(),
                "preservesImportedData", true, "workspaceFilesDeleted", false);
    }

    /** 先持久化释放意图，再由可重试清理任务删除文件，进程中断不会丢失清理记录。 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> releaseRawFiles(Long id) {
        GisFileMeta located = getRequired(id);
        if (gisDataSetMapper.selectActiveByIdForUpdate(located.getDataSetId()) == null) throw new IllegalArgumentException("数据集不存在");
        var group = located.getFileGroupId() == null ? null : fileGroupMapper.lock(located.getFileGroupId());
        Map<String, Object> preview = releasePreview(id);
        if (!((List<?>) preview.get("blockers")).isEmpty()) throw new IllegalStateException(preview.get("blockers").toString());
        try {vectorResources.verifySourceSnapshot(group==null?id:group.getEntryFileId());}
        catch(IOException ex) {throw new IllegalStateException("原始快照不可读取，禁止释放原文件",ex);}
        for (Object fileId : (List<?>) preview.get("fileIds")) {
            GisFileMeta file = getRequiredForUpdate(Long.valueOf(fileId.toString()));
            if (!"RETAINED".equals(file.getRawRetention())) continue;
            ensureNoActiveProcessingTask(file.getId()); validatePermanentDeleteStorage(file);
            // 工作区文件只解除物理接入，外部文件不归平台清理。
            String state = "WORKSPACE".equals(file.getSourceType()) ? "RELEASED" : "RELEASE_PENDING";
            gisFileMetaMapper.update(null, new LambdaUpdateWrapper<GisFileMeta>().eq(GisFileMeta::getId, file.getId())
                    .set(GisFileMeta::getRawRetention, state).set(GisFileMeta::getUpdateTime, LocalDateTime.now()));
            GisFileMeta after = new GisFileMeta();
            org.springframework.beans.BeanUtils.copyProperties(file, after); after.setRawRetention(state);
            dataSetService.updateFileStatistics(file, after);
        }
        events.publishEvent(new RawFilesReleased());
        return preview;
    }

    public record RawFilesReleased() { }

    public void cleanupReleasedRawFiles() {
        var files = gisFileMetaMapper.selectList(new LambdaQueryWrapper<GisFileMeta>()
                .eq(GisFileMeta::getRawRetention, "RELEASE_PENDING").last("LIMIT 100"));
        for (var file : files) {
            try {
                fileUploadUtil.deleteStored(file.getStorageKey());
                gisFileMetaMapper.update(null, new LambdaUpdateWrapper<GisFileMeta>().eq(GisFileMeta::getId, file.getId())
                        .eq(GisFileMeta::getRawRetention, "RELEASE_PENDING").set(GisFileMeta::getRawRetention, "RELEASED"));
            } catch (RuntimeException ex) { log.warn("原文件释放待重试: id={}", file.getId(), ex); }
        }
    }

    private void ensureNoActiveProcessingTask(Long id) {
        List<GisProcessingInputMapper.ActiveTaskReference> active =
                processingInputMapper.selectActiveByFileMetaId(id);
        if (!active.isEmpty()) {
            var task = active.getFirst();
            throw new IllegalStateException("文件正在被处理任务使用，请等待任务结束后重试: "
                    + task.taskName() + " (" + task.taskNo() + ", " + task.taskStatus() + ")");
        }
    }

    private void ensureVectorSourceUnreferenced(Long id) {
        if (gisVectorResourceMapper.countSourceReferences(id) > 0) {
            throw new IllegalStateException("文件仍被矢量图层或导入任务引用，请先删除相关图层并等待导入结束");
        }
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public GisFileMetaVO getFileMetaDetail(Long id) {
        return toVOWithCategory(getRequired(id));
    }
    /** 校验下载业务状态并返回文件描述，HTTP 传输由控制层负责。 */
    public DownloadFile prepareDownload(Long id) {
        GisFileMeta meta = getRequired(id);
        ensureDownloadable(meta);
        return new DownloadFile(resolveSourcePath(meta), meta.getOriginalName());
    }

    public record DownloadFile(Path path, String originalName) { }


    /** 下载及已管理文件处理统一验证工作区快照，发现变化后立即退出空间检索。 */
    public Path resolveSourcePath(GisFileMeta meta) {
        if (!"RETAINED".equals(meta.getRawRetention())) throw new IllegalArgumentException("原始文件已释放，请使用导入快照");
        try {
            return workspaceResolver.resolveFile(meta);
        } catch (ControlledWorkspaceResolver.SourceException ex) {
            spatialMetadataService.markSourceUnavailable(meta.getId(), ex.code(), ex.getMessage());
            throw ex;
        }
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

    private GisFileMeta getRequiredForUpdate(Long id) {
        if (id == null) throw new IllegalArgumentException("文件元数据ID不能为空");
        GisFileMeta meta = gisFileMetaMapper.selectActiveByIdForUpdate(id);
        if (meta == null) throw new IllegalArgumentException("文件元数据不存在或已删除: " + id);
        return meta;
    }

    private void ensureMutable(GisFileMeta meta) {
        if (Set.of("WAITING", "QUEUED", "RUNNING").contains(meta.getMetadataStatus()))
            throw new IllegalStateException("文件接入或空间解析尚未结束，暂不支持删除");
    }

    private void validatePermanentDeleteStorage(GisFileMeta meta) {
        if ("WORKSPACE".equals(meta.getSourceType())) return;
        if (trimToNull(meta.getStorageKey()) == null) {
            return;
        }
        String storageType = normalizeUpper(meta.getStorageType());
        if (!"LOCAL".equals(storageType)) {
            throw new IllegalStateException(
                    "暂不支持彻底删除 " + storageType + " 存储文件: " + meta.getId());
        }
    }

    /** 只有已入库且落在本地存储的文件才能直接下载。 */
    private void ensureDownloadable(GisFileMeta meta) {
        if (!"RETAINED".equals(meta.getRawRetention())) throw new IllegalArgumentException("原始文件已释放，不能下载原文件");
        if (!"READY".equals(normalizeUpper(meta.getFileStatus()))) {
            throw new IllegalArgumentException("文件尚未入库，不能下载: " + meta.getId());
        }
        if (!"WORKSPACE".equals(meta.getSourceType()) && !"LOCAL".equals(normalizeUpper(meta.getStorageType()))) {
            throw new IllegalArgumentException(
                    "暂不支持下载 " + meta.getStorageType() + " 存储的文件: " + meta.getId());
        }
    }

    private GisFileMetaVO toVO(GisFileMeta meta) {
        GisFileMetaVO result = new GisFileMetaVO();
        result.setId(meta.getId());
        result.setDataSetId(meta.getDataSetId());
        result.setSourceType(meta.getSourceType());
        result.setWorkspaceCode(meta.getWorkspaceCode());
        result.setRelativePath(meta.getRelativePath());
        result.setSourceModifiedAt(meta.getSourceModifiedAt());
        result.setVectorDatasetStyleId(meta.getVectorDatasetStyleId());
        result.setVectorPlacement(meta.getVectorPlacement());
        result.setOriginalName(meta.getOriginalName());
        result.setStorageName(meta.getStorageName());
        result.setStorageKey(meta.getStorageKey());
        result.setStorageType(meta.getStorageType());
        result.setExtension(meta.getExtension());
        result.setSizeBytes(meta.getSizeBytes());
        result.setSha256(meta.getSha256());
        result.setCreatedBy(meta.getCreatedBy());
        result.setFileStatus(meta.getFileStatus());
        result.setRawRetention(meta.getRawRetention());
        result.setCleanupStatus(meta.getCleanupStatus());
        result.setErrorMessage(meta.getErrorMessage());
        result.setMetadataStatus(meta.getMetadataStatus());
        result.setFileGroupId(meta.getFileGroupId());
        result.setMemberPath(meta.getMemberPath());
        result.setIngestOutcome(meta.getIngestOutcome());
        result.setMetadataVersion(meta.getMetadataVersion());
        result.setMetadataErrorCode(meta.getMetadataErrorCode());
        result.setMetadataErrorMessage(meta.getMetadataErrorMessage());
        result.setMetadataExtractedAt(meta.getMetadataExtractedAt());
        result.setCreateTime(meta.getCreateTime());
        result.setUpdateTime(meta.getUpdateTime());
        return result;
    }

    private GisFileMetaVO toVOWithCategory(GisFileMeta meta) {
        GisFileMetaVO result = toVO(meta);
        populateCategoryIds(List.of(result));
        spatialMetadataService.populate(List.of(result), true);
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

    private String normalizeUpper(String value) {
        String normalized = trimToNull(value);
        return normalized == null ? null : normalized.toUpperCase(Locale.ROOT);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
