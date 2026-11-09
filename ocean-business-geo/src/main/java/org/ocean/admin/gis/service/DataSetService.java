package org.ocean.admin.gis.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ocean.admin.gis.entity.GisDataSet;
import org.ocean.admin.gis.entity.GisDataSetCategory;
import org.ocean.admin.gis.entity.GisFileMeta;
import org.ocean.admin.gis.mapper.GisDataSetMapper;
import org.ocean.admin.gis.mapper.GisFileMetaMapper;
import org.ocean.admin.gis.mapper.GisFileGroupMapper;
import org.ocean.admin.gis.entity.GisFileGroup;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.ocean.admin.gis.mapper.GisFileSpatialMetadataMapper;
import org.ocean.admin.gis.mapper.GisProcessingInputMapper;
import org.ocean.admin.gis.util.FileUploadUtil;
import org.ocean.admin.gis.vo.GisDataSetVO;
import org.ocean.admin.kernel.common.PageResult;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
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

    private final VectorDataSetStyleService vectorDataSetStyles;
    private final org.ocean.admin.gis.mapper.GisVectorMapper vectorMapper;
    private final GisDataSetMapper gisDataSetMapper;
    private final GisFileMetaMapper gisFileMetaMapper;
    private final GisFileGroupMapper fileGroupMapper;
    private final GisProcessingInputMapper processingInputMapper;
    private final GisFileSpatialMetadataMapper spatialMetadataMapper;
    private final FileUploadUtil fileUploadUtil;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher events;

    /** 按固定分类返回全部未删除数据集数量，空分类补零。 */
    @Transactional(readOnly = true)
    public GisDataSetVO.CategorySummary getDataSetCategories() {
        Map<Long, Long> counts = gisDataSetMapper.selectCategoryCounts().stream()
                .collect(Collectors.toMap(GisDataSetMapper.CategoryCount::categoryId,
                        GisDataSetMapper.CategoryCount::dataSetCount));
        List<GisDataSetVO.CategoryStatistics> categories = new ArrayList<>();
        long totalCount = 0;
        for (GisDataSetCategory category : GisDataSetCategory.values()) {
            long dataSetCount = counts.getOrDefault(category.categoryId(), 0L);
            categories.add(new GisDataSetVO.CategoryStatistics(
                    category.categoryId(), category.categoryName(), dataSetCount));
            totalCount += dataSetCount;
        }
        return new GisDataSetVO.CategorySummary(totalCount, categories);
    }

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
        return Integer.valueOf(0).equals(file.getDeleted()) && "READY".equals(file.getFileStatus()) && "RETAINED".equals(file.getRawRetention());
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
        populateFileGroupCounts(records);
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
        // 矢量数据集初始化样式
        if (Long.valueOf(2).equals(dataSet.getCategoryId())) {
            vectorDataSetStyles.initialize(dataSet.getId());
        }
        log.info("创建数据集成功: id={}, code={}, name={}",
                dataSet.getId(), dataSet.getDataSetCode(), dataSet.getDataSetName());
        return toVO(dataSet);
    }

    private String generateDataSetCode() {
        return "GEO_" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateDataset(GisDataSetVO reqVO) {
        validateDataSetRequest(reqVO, true);
        GisDataSet existing = gisDataSetMapper.selectById(reqVO.getId());
        if (existing == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "数据集不存在或已删除: " + reqVO.getId());
        }
        if (!existing.getCategoryId().equals(reqVO.getCategoryId())) {
            throw new IllegalArgumentException("不允许更改数据集分类，请新建目标分类数据集");
        }
        String dataSetName = reqVO.getDataSetName().trim();
        ensureDataSetNameAvailable(dataSetName, reqVO.getId());

        int updated = gisDataSetMapper.update(
                null,
                new LambdaUpdateWrapper<GisDataSet>()
                        .eq(GisDataSet::getId, reqVO.getId())
                        .set(GisDataSet::getDataSetName, dataSetName)
                        .set(GisDataSet::getDescription, reqVO.getDescription())
                        .set(GisDataSet::getUpdateTime, LocalDateTime.now())
        );
        if (updated != 1) {
            throw new IllegalStateException("数据集更新失败: " + reqVO.getId());
        }
        log.info("更新数据集成功: id={}, name={}", reqVO.getId(), dataSetName);
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
        GisProcessingInputMapper.ActiveTaskReference task =
                processingInputMapper.selectFirstActiveByDataSetId(id);
        if (task != null) {
            throw new IllegalStateException("数据集文件正在被处理任务使用，请等待任务结束后重试: "
                    + task.taskName() + " (" + task.taskNo() + ", " + task.taskStatus() + ")");
        }
        List<GisFileGroup> groups = fileGroupMapper.lockByDataSet(id);
        if (groups.stream().anyMatch(group -> java.util.Set.of("QUEUED", "RUNNING").contains(group.getParseStatus())))
            throw new IllegalStateException("数据集仍有文件组正在解析，请等待结束");
        List<GisFileMeta> files = gisFileMetaMapper.selectAllByDataSetIdForUpdate(id);
        if (files.stream().anyMatch(file -> java.util.Set.of("WAITING", "QUEUED", "RUNNING").contains(file.getMetadataStatus()))) {
            throw new IllegalStateException("数据集仍有文件正在提取空间元数据，请等待处理结束后重试");
        }
        List<GisFileMeta> unsupportedStorage = files.stream()
                .filter(file -> "UPLOAD".equals(file.getSourceType()))
                .filter(file -> file.getStorageKey() != null && !file.getStorageKey().isBlank())
                .filter(file -> !"LOCAL".equals(file.getStorageType()))
                .toList();
        if (!unsupportedStorage.isEmpty()) {
            throw new IllegalStateException("暂不支持删除 "
                    + unsupportedStorage.getFirst().getStorageType() + " 存储中的数据集文件");
        }

        fileUploadUtil.stageStoredForTransactionalDeletion(files.stream()
                .filter(file -> "UPLOAD".equals(file.getSourceType()))
                .map(GisFileMeta::getStorageKey)
                .filter(java.util.Objects::nonNull)
                .filter(key -> !key.isBlank())
                .toList());
        processingInputMapper.detachTerminalByDataSetId(id);
        // 发布快照独立保留，其余文件资源先登记回收意图，提交后由清理任务释放。
        vectorMapper.retireDataSetResources(id);
        if (gisDataSetMapper.physicallyDeleteById(id) != 1) {
            throw new IllegalStateException("数据集删除失败: " + id);
        }
        fileUploadUtil.deleteEmptyDataSetDirectory(id);
        events.publishEvent(new FileSpatialMetadataService.MetadataProgress(files.stream().map(GisFileMeta::getId).toList(), false));
        log.info("删除数据集成功: id={}, name={}, fileCount={}",
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
        populateFileInformation(List.of(result), includeFootprints, tolerance);
        return result;
    }

    /**
     * 填充数据集文件信息
     * @param records   数据集记录
     * @param includeFootprints 是否包含脚印信息
     * @param tolerance 简化容差
     */
    private void populateFileInformation(List<GisDataSetVO> records, boolean includeFootprints, double tolerance) {
        if (records.isEmpty()) return;
        populateFileGroupCounts(records);
        List<Long> ids = records.stream().map(GisDataSetVO::getId).toList();
        Map<Long, GisDataSetVO.FileSummary> summaries = gisFileMetaMapper.selectDataSetFileSummaries(ids).stream()
                .collect(Collectors.toMap(GisDataSetVO.FileSummary::getDataSetId, item -> item));
        Map<Long, GisDataSetVO.FootprintRow> footprints = includeFootprints
                ? spatialMetadataMapper.selectDataSetFootprints(ids, tolerance).stream()
                    .collect(Collectors.toMap(GisDataSetVO.FootprintRow::getDataSetId, item -> item))
                : Map.of();
        for (GisDataSetVO record : records) {
            GisDataSetVO.FileSummary summary = summaries.get(record.getId());
            if (summary == null) {
                summary = new GisDataSetVO.FileSummary();
                summary.setDataSetId(record.getId());
            }
            record.setFileSummary(summary);
            if (includeFootprints) {
                GisDataSetVO.FootprintRow row = footprints.get(record.getId());
                List<GisDataSetVO.FootprintFeature> features = row == null ? List.of() : List.of(
                        new GisDataSetVO.FootprintFeature("Feature", "dataset-" + record.getId(),
                                objectMapper.readTree(row.getGeoJson()),
                                new GisDataSetVO.FootprintProperties(record.getId(), row.getSourceFileCount(),
                                        row.getSourceLayerCount(), row.getExtentKind(), tolerance > 0, tolerance)));
                record.setFootprints(new GisDataSetVO.FootprintCollection("FeatureCollection", features));
            }
        }
    }

    /** 列表仅补充文件组数量，文件数和总大小直接使用数据集已维护的统计值。 */
    private void populateFileGroupCounts(List<GisDataSetVO> records) {
        if (records.isEmpty()) return;
        List<Long> ids = records.stream().map(GisDataSetVO::getId).toList();
        Map<Long, Long> groupCounts = new TreeMap<>();
        for (Map<String, Object> row : fileGroupMapper.selectMaps(new QueryWrapper<GisFileGroup>()
                .select("data_set_id", "COUNT(*) AS group_count").in("data_set_id", ids)
                .ne("assembly_status", "DUPLICATE").groupBy("data_set_id"))) {
            groupCounts.put(((Number) row.get("data_set_id")).longValue(), ((Number) row.get("group_count")).longValue());
        }
        for (GisDataSetVO record : records) {
            record.setFileGroupCount(groupCounts.getOrDefault(record.getId(), 0L));
        }
    }

    /**
     * 验证数据集请求
     * @param reqVO 数据集请求
     * @param requireId 是否需要ID
     */
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
        if (!GisDataSetCategory.isValid(reqVO.getCategoryId())) {
            throw new IllegalArgumentException("数据类别只能为0-影像数据、1-地形数据、2-矢量数据");
        }
    }

    /**
     * 确保数据集名称可用
      * @param dataSetName 数据集名称
     * @param excludedId 排除的ID
     */
    private void ensureDataSetNameAvailable(String dataSetName, Long excludedId) {
        LambdaQueryWrapper<GisDataSet> query = new LambdaQueryWrapper<GisDataSet>()
                .eq(GisDataSet::getDataSetName, dataSetName)
                .ne(excludedId != null, GisDataSet::getId, excludedId);
        if (gisDataSetMapper.exists(query)) {
            throw new IllegalArgumentException("数据集名称已存在: " + dataSetName);
        }
    }

    /**
     * 将数据集实体转换为数据集VO
     * @param dataSet 数据集实体
     * @return 数据集VO
     */
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
