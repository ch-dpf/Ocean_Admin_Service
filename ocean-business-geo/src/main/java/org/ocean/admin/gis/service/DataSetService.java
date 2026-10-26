package org.ocean.admin.gis.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ocean.admin.gis.entity.GisDataSet;
import org.ocean.admin.gis.entity.GisFileMeta;
import org.ocean.admin.gis.mapper.GisFileMetaMapper;
import org.ocean.admin.gis.mapper.GisVectorMapper;
import org.ocean.admin.gis.mapper.GisFileSpatialMetadataMapper;
import org.ocean.admin.gis.mapper.GisProcessingInputMapper;
import org.ocean.admin.gis.util.FileUploadUtil;
import org.ocean.admin.kernel.common.PageResult;
import org.ocean.admin.gis.mapper.GisDataSetMapper;
import org.ocean.admin.gis.vo.GisDataSetVO;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * gis数据集服务
 *
 * @author DeepOcean
 * @since 2026-09-11
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DataSetService {

    private final GisVectorMapper vectorMapper;
    private final VectorDataSetStyleService vectorDataSetStyles;

    private final GisDataSetMapper gisDataSetMapper;
    private final GisFileMetaMapper gisFileMetaMapper;
    private final GisProcessingInputMapper processingInputMapper;
    private final GisFileSpatialMetadataMapper spatialMetadataMapper;
    private final FileUploadUtil fileUploadUtil;
    private final ObjectMapper objectMapper;

    /** 调用方必须在文件写入事务内提供变更前后快照；文件数与大小均只统计未删除的 READY 文件。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void updateFileStatistics(GisFileMeta before, GisFileMeta after) {
        Map<Long, FileStatisticsDelta> deltas = new TreeMap<>();
        if (before != null && contributesToStatistics(before)) {
            deltas.merge(before.getDataSetId(), new FileStatisticsDelta(-1, -before.getSizeBytes()), FileStatisticsDelta::add);
        }
        if (after != null && contributesToStatistics(after)) {
            deltas.merge(after.getDataSetId(), new FileStatisticsDelta(1, after.getSizeBytes()), FileStatisticsDelta::add);
        }
        // 涉及多个数据集时按 ID 顺序更新，避免相反方向的迁移产生锁顺序冲突。
        for (var entry : deltas.entrySet()) {
            FileStatisticsDelta delta = entry.getValue();
            if ((delta.count() != 0 || delta.sizeBytes() != 0)
                    && gisDataSetMapper.adjustFileStatistics(entry.getKey(), delta.count(), delta.sizeBytes()) != 1) {
                throw new IllegalStateException("数据集文件统计更新失败或统计值异常: " + entry.getKey());
            }
        }
    }

    private boolean contributesToStatistics(GisFileMeta file) {
        return Integer.valueOf(0).equals(file.getDeleted()) && "READY".equals(file.getUploadStatus());
    }

    private record FileStatisticsDelta(int count, long sizeBytes) {
        private FileStatisticsDelta add(FileStatisticsDelta other) {
            return new FileStatisticsDelta(Math.addExact(count, other.count), Math.addExact(sizeBytes, other.sizeBytes));
        }
    }


    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<List<GisDataSetVO>> getDataSetPage(
            Integer current, Integer size, Long categoryId, String dataSetName) {
        long currentPage = current == null || current < 1 ? 1L : current;
        long pageSize = size == null || size < 1 ? 10L : Math.min(size, 100);
        String normalizedDataSetName = dataSetName == null ? "" : dataSetName.trim();

        LambdaQueryWrapper<GisDataSet> queryWrapper = new LambdaQueryWrapper<GisDataSet>()
                .eq(categoryId != null, GisDataSet::getCategoryId, categoryId)
                .like(!normalizedDataSetName.isEmpty() , GisDataSet::getDataSetName, normalizedDataSetName)
                .orderByDesc(GisDataSet::getCreateTime)
                .orderByDesc(GisDataSet::getId);

        Page<GisDataSet> page = gisDataSetMapper.selectPage(
                new Page<>(currentPage, pageSize),
                queryWrapper
        );
        List<GisDataSetVO> records = page.getRecords().stream().map(this::toVO).toList();
        populateFileInformation(records, false, 0);
        populateFilePoints(records);
        return new PageResult<>(page.getCurrent(), page.getSize(), page.getTotal(), records);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<GisDataSetVO> getDataSets(Long categoryId, String dataSetName) {
        String normalizedDataSetName = dataSetName == null ? "" : dataSetName.trim();
        LambdaQueryWrapper<GisDataSet> queryWrapper = new LambdaQueryWrapper<GisDataSet>()
                .eq(categoryId != null, GisDataSet::getCategoryId, categoryId)
                .like(!normalizedDataSetName.isEmpty() , GisDataSet::getDataSetName, normalizedDataSetName)
                .orderByDesc(GisDataSet::getCreateTime)
                .orderByDesc(GisDataSet::getId);
        List<GisDataSetVO> records = gisDataSetMapper.selectList(queryWrapper).stream().map(this::toVO).toList();
        populateFileInformation(records, false, 0);
        return records;
    }

    @Transactional(rollbackFor = Exception.class)
    public GisDataSetVO createDataset(GisDataSetVO reqVO) {
        // 校验数据集是否存在，不存在则新增，存在则返回错误原因提示
        validateDataSetRequest(reqVO, false);
        String dataSetName = reqVO.getDataSetName().trim();
        ensureDataSetNameAvailable(dataSetName, null);

        LocalDateTime now = LocalDateTime.now();
        GisDataSet dataSet = new GisDataSet();
        dataSet.setDataSetName(dataSetName);
        dataSet.setCategoryId(reqVO.getCategoryId());
        dataSet.setDataSetCode(generateDataSetCode());
        dataSet.setDescription(reqVO.getDescription());
        dataSet.setFileCount(0);
        dataSet.setTotalSizeBytes(0L);
        dataSet.setCreateTime(now);
        dataSet.setUpdateTime(now);
        dataSet.setDeleted(0);

        if (gisDataSetMapper.insert(dataSet) != 1) {
            throw new IllegalStateException("数据集创建失败");
        }
        if (Long.valueOf(2).equals(dataSet.getCategoryId())) vectorDataSetStyles.initialize(dataSet.getId());

        log.info("创建 GIS 数据集成功: id={}, code={}, name={}",
                dataSet.getId(), dataSet.getDataSetCode(), dataSet.getDataSetName());
        return toVO(dataSet);
    }

    private String generateDataSetCode() {
        return "GIS_" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateDataset(GisDataSetVO reqVO) {
        validateDataSetRequest(reqVO, true);

        GisDataSet existing = gisDataSetMapper.selectById(reqVO.getId());
        if (existing == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "数据集不存在或已删除: " + reqVO.getId());
        }

        String dataSetName = reqVO.getDataSetName().trim();
        ensureDataSetNameAvailable(dataSetName, reqVO.getId());

        if (!existing.getCategoryId().equals(reqVO.getCategoryId())) {
            if (Long.valueOf(2).equals(existing.getCategoryId()) || Long.valueOf(2).equals(reqVO.getCategoryId())) {
                throw new IllegalArgumentException("矢量数据集的类别不可直接切换，请新建目标类别数据集");
            }
            // 推荐层级依赖分类，分类变化后旧结果与正在提取的版本均失效。
            spatialMetadataMapper.invalidateDataSetRasters(reqVO.getId());
        }

        int updated = gisDataSetMapper.update(
                null,
                new LambdaUpdateWrapper<GisDataSet>()
                        .eq(GisDataSet::getId, reqVO.getId())
                        .set(GisDataSet::getDataSetName, dataSetName)
                        .set(GisDataSet::getCategoryId, reqVO.getCategoryId())
                        .set(GisDataSet::getDescription, reqVO.getDescription())
                        .set(GisDataSet::getUpdateTime, LocalDateTime.now())
        );
        if (updated != 1) {
            throw new IllegalStateException("数据集更新失败: " + reqVO.getId());
        }
        log.info("更新 GIS 数据集成功: id={}, name={}", reqVO.getId(), dataSetName);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteDataset(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("数据集ID不能为空");
        }
        GisDataSet dataSet = gisDataSetMapper.selectActiveByIdForUpdate(id);
        if (dataSet == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "数据集不存在或已删除: " + id);
        }
        if (gisDataSetMapper.countActiveTransfersByDataSetId(id) > 0) {
            throw new IllegalStateException("数据集存在正在上传或下载的文件，请等待传输结束后重试");
        }
        List<GisProcessingInputMapper.ActiveTaskReference> activeTasks =
                processingInputMapper.selectActiveByDataSetId(id);
        if (!activeTasks.isEmpty()) {
            var task = activeTasks.getFirst();
            throw new IllegalStateException("数据集文件正在被处理任务使用，请等待任务结束后重试: "
                    + task.taskName() + " (" + task.taskNo() + ", " + task.taskStatus() + ")");
        }

        List<GisFileMeta> files = gisFileMetaMapper.selectAllByDataSetIdForUpdate(id);
        if (files.stream().anyMatch(file -> "RUNNING".equals(file.getMetadataStatus()))) {
            throw new IllegalStateException("数据集仍有文件正在提取空间元数据，请等待处理结束后重试");
        }
        List<GisFileMeta> unsupportedStorage = files.stream()
                .filter(file -> file.getStorageKey() != null && !file.getStorageKey().isBlank())
                .filter(file -> !"LOCAL".equals(file.getStorageType()))
                .toList();
        if (!unsupportedStorage.isEmpty()) {
            throw new IllegalStateException("暂不支持物理删除 "
                    + unsupportedStorage.getFirst().getStorageType() + " 存储中的数据集文件");
        }

        fileUploadUtil.stageStoredForTransactionalDeletion(files.stream()
                .map(GisFileMeta::getStorageKey)
                .filter(java.util.Objects::nonNull)
                .filter(key -> !key.isBlank())
                .toList());
        processingInputMapper.detachTerminalByDataSetId(id);
        if (gisDataSetMapper.physicallyDeleteById(id) != 1) {
            throw new IllegalStateException("数据集物理删除失败: " + id);
        }
        fileUploadUtil.deleteEmptyDataSetDirectory(id);
        log.info("永久删除 GIS 数据集成功: id={}, name={}, fileCount={}",
                id, dataSet.getDataSetName(), files.size());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public GisDataSetVO getDatasetDetail(Long id) {
        return loadDatasetInformation(id, false, 0);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public GisDataSetVO getDatasetFootprints(Long id, double simplifyTolerance) {
        validateSimplifyTolerance(simplifyTolerance);
        return loadDatasetInformation(id, true, simplifyTolerance);
    }

    private void validateSimplifyTolerance(double tolerance) {
        if (!Double.isFinite(tolerance) || tolerance < 0 || tolerance > 1) {
            throw new IllegalArgumentException("轮廓简化容差必须为0至1之间的有限数值，单位为度");
        }
    }

    private GisDataSetVO loadDatasetInformation(Long id, boolean includeFootprints, double tolerance) {
        if (id == null) {
            throw new IllegalArgumentException("数据集ID不能为空");
        }
        GisDataSet dataSet = gisDataSetMapper.selectById(id);
        if (dataSet == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "数据集不存在或已删除: " + id);
        }
        GisDataSetVO result = toVO(dataSet);
        if (Long.valueOf(2).equals(dataSet.getCategoryId())) {
            var s = vectorMapper.dataSetStyle(id);
            if (s != null) result.setVectorStyle(new GisDataSetVO.VectorStyleSummary(s.getId().toString(), s.getTemplateId(),
                    s.getTemplateVersion(), s.getStyleVersion(), s.getDataRevision(), s.getDisplayRevision()));
        }
        populateFileInformation(List.of(result), includeFootprints, tolerance);
        return result;
    }

    private void populateFileInformation(List<GisDataSetVO> records, boolean includeFootprints, double tolerance) {
        if (records.isEmpty()) return;
        List<Long> ids = records.stream().map(GisDataSetVO::getId).toList();
        Map<Long, GisDataSetVO.FileSummary> summaries = gisFileMetaMapper.selectDataSetFileSummaries(ids).stream()
                .collect(Collectors.toMap(GisDataSetVO.FileSummary::getDataSetId, item -> item));
        Map<Long, List<GisDataSetVO.FootprintRow>> footprints = includeFootprints
                ? spatialMetadataMapper.selectDataSetFootprints(ids, tolerance).stream()
                    .collect(Collectors.groupingBy(GisDataSetVO.FootprintRow::getDataSetId))
                : Map.of();
        for (GisDataSetVO record : records) {
            GisDataSetVO.FileSummary summary = summaries.get(record.getId());
            if (summary == null) {
                summary = new GisDataSetVO.FileSummary();
                summary.setDataSetId(record.getId());
            }
            record.setFileSummary(summary);
            if (includeFootprints) {
                List<GisDataSetVO.FootprintFeature> features = footprints.getOrDefault(record.getId(), List.of()).stream()
                        .map(row -> new GisDataSetVO.FootprintFeature("Feature", row.getId().toString(),
                                objectMapper.readTree(row.getGeoJson()),
                                new GisDataSetVO.FootprintProperties(row.getFileId(), row.getFileName(), row.getLayerKey(),
                                        row.getExtentKind(), tolerance > 0, tolerance)))
                        .toList();
                record.setFootprints(new GisDataSetVO.FootprintCollection("FeatureCollection", features));
            }
        }
    }

    private void populateFilePoints(List<GisDataSetVO> records) {
        if (records.isEmpty()) return;
        Map<Long, List<GisDataSetVO.FilePoint>> points = gisFileMetaMapper.selectDataSetFilePoints(
                        records.stream().map(GisDataSetVO::getId).toList()).stream()
                .collect(Collectors.groupingBy(GisDataSetVO.FilePoint::getDataSetId));
        for (GisDataSetVO record : records) {
            record.setFilePoints(points.getOrDefault(record.getId(), List.of()));
        }
    }

    private void validateDataSetRequest(GisDataSetVO reqVO, boolean requireId) {
        if (reqVO == null) {
            throw new IllegalArgumentException("数据集请求不能为空");
        }
        if (requireId && reqVO.getId() == null) {
            throw new IllegalArgumentException("数据集ID不能为空");
        }
        if (reqVO.getDataSetName() == null || reqVO.getDataSetName().isBlank()) {
            throw new IllegalArgumentException("数据集名称不能为空");
        }
        if (reqVO.getCategoryId() == null) {
            throw new IllegalArgumentException("数据类别不能为空");
        }
        if (reqVO.getCategoryId() < 0 || reqVO.getCategoryId() > 2) {
            throw new IllegalArgumentException("数据类别只能为0-影像数据、1-地形数据、2-矢量数据");
        }
    }

    private void ensureDataSetNameAvailable(String dataSetName, Long excludedId) {
        LambdaQueryWrapper<GisDataSet> query = new LambdaQueryWrapper<GisDataSet>()
                .eq(GisDataSet::getDataSetName, dataSetName)
                .ne(excludedId != null, GisDataSet::getId, excludedId);
        if (gisDataSetMapper.exists(query)) {
            throw new IllegalArgumentException("数据集名称已存在: " + dataSetName);
        }
    }

    private GisDataSetVO toVO(GisDataSet dataSet) {
        GisDataSetVO result = new GisDataSetVO();
        result.setId(dataSet.getId());
        result.setDataSetName(dataSet.getDataSetName());
        result.setCategoryId(dataSet.getCategoryId());
        result.setDescription(dataSet.getDescription());
        result.setDataSetCode(dataSet.getDataSetCode());
        result.setFileCount(dataSet.getFileCount());
        result.setTotalSizeBytes(dataSet.getTotalSizeBytes());
        result.setCreateTime(dataSet.getCreateTime());
        result.setUpdateTime(dataSet.getUpdateTime());
        return result;
    }
}
