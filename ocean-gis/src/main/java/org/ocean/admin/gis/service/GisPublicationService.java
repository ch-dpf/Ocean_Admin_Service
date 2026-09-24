package org.ocean.admin.gis.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.entity.GisPublication;
import org.ocean.admin.gis.entity.GisTask;
import org.ocean.admin.gis.mapper.GisPublicationMapper;
import org.ocean.admin.kernel.common.PageResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 各类 GIS 服务共用的发布记录、发布任务和状态管理。 */
@Service
@RequiredArgsConstructor
public class GisPublicationService {

    public static final String PUBLISHED = "PUBLISHED";
    public static final String DISABLED = "DISABLED";
    private static final Set<String> STATUSES = Set.of(PUBLISHED, DISABLED);

    private final GisPublicationMapper publicationMapper;
    private final GisTaskService gisTaskService;

    @Value("${gis.publication.public-base-url:http://localhost:8090}")
    private String publicBaseUrl;

    public PageResult<List<GisPublication>> getPublicationPage(
            String processingType,
            Integer current,
            Integer size,
            String serviceCode,
            String status,
            Long dataSetId,
            LocalDateTime publishTimeStart,
            LocalDateTime publishTimeEnd) {
        long currentPage = current == null || current < 1 ? 1L : current;
        long pageSize = size == null || size < 1 ? 10L : Math.min(size, 100);
        String normalizedCode = upperTrimToNull(serviceCode);
        String normalizedStatus = upperTrimToNull(status);
        if (normalizedStatus != null && !STATUSES.contains(normalizedStatus)) {
            throw new IllegalArgumentException("发布状态只能为 PUBLISHED 或 DISABLED");
        }
        if (publishTimeStart != null && publishTimeEnd != null
                && publishTimeStart.isAfter(publishTimeEnd)) {
            throw new IllegalArgumentException("发布开始时间不能晚于发布结束时间");
        }

        LambdaQueryWrapper<GisPublication> query = new LambdaQueryWrapper<GisPublication>()
                .eq(GisPublication::getProcessingType, processingType)
                .like(normalizedCode != null, GisPublication::getServiceCode, normalizedCode)
                .eq(normalizedStatus != null, GisPublication::getStatus, normalizedStatus)
                .eq(dataSetId != null, GisPublication::getDataSetId, dataSetId)
                .ge(publishTimeStart != null, GisPublication::getPublishTime, publishTimeStart)
                .le(publishTimeEnd != null, GisPublication::getPublishTime, publishTimeEnd)
                .orderByDesc(GisPublication::getPublishTime);
        Page<GisPublication> page = publicationMapper.selectPage(
                new Page<>(currentPage, pageSize), query);
        return new PageResult<>(page.getCurrent(), page.getSize(), page.getTotal(),
                page.getRecords());
    }

    public GisPublication getRequired(String processingType, String serviceCode) {
        GisPublication publication = publicationMapper.selectOne(
                new LambdaQueryWrapper<GisPublication>()
                        .eq(GisPublication::getProcessingType, processingType)
                        .eq(GisPublication::getServiceCode, serviceCode));
        if (publication == null) {
            throw new IllegalArgumentException(processingType + "发布服务不存在: " + serviceCode);
        }
        return publication;
    }

    public GisPublication findBySourceTaskId(String processingType, Long sourceTaskId) {
        return publicationMapper.selectOne(new LambdaQueryWrapper<GisPublication>()
                .eq(GisPublication::getProcessingType, processingType)
                .eq(GisPublication::getSourceTaskId, sourceTaskId));
    }

    @Transactional(rollbackFor = Exception.class)
    public GisPublication publish(GisTask sourceTask, String initialServiceCode,
            Integer minZoom, Integer maxZoom, String displayName) {
        GisPublication publication = findBySourceTaskId(
                sourceTask.getProcessingType(), sourceTask.getId());
        if (publication != null && PUBLISHED.equals(publication.getStatus())) {
            return publication;
        }

        LocalDateTime now = LocalDateTime.now();
        GisTask publishTask = createCompletedPublishTask(sourceTask, displayName, now);
        gisTaskService.insert(publishTask);

        if (publication == null) {
            publication = new GisPublication();
            publication.setServiceCode(initialServiceCode);
            publication.setProcessingType(sourceTask.getProcessingType());
            publication.setSourceTaskId(sourceTask.getId());
            publication.setDataSetId(sourceTask.getDataSetId());
            publication.setOutputKey(sourceTask.getOutputKey());
            publication.setDeleted(0);
        }
        publication.setPublishTaskId(publishTask.getId());
        publication.setTargetCrs(sourceTask.getTargetCrs());
        publication.setTileProfile(sourceTask.getTileProfile());
        publication.setOutputFormat(sourceTask.getOutputFormat());
        publication.setMinZoom(minZoom);
        publication.setMaxZoom(maxZoom);
        publication.setStatus(PUBLISHED);
        publication.setPublishTime(now);
        publication.setUpdateTime(now);

        int affected = publication.getId() == null
                ? publicationMapper.insert(publication)
                : publicationMapper.updateById(publication);
        if (affected != 1) {
            throw new IllegalStateException(displayName + "发布记录保存失败");
        }
        return publication;
    }

    @Transactional(rollbackFor = Exception.class)
    public GisPublication disable(String processingType, String serviceCode, String displayName) {
        GisPublication publication = getRequired(processingType, serviceCode);
        if (!DISABLED.equals(publication.getStatus())) {
            publication.setStatus(DISABLED);
            publication.setUpdateTime(LocalDateTime.now());
            if (publicationMapper.updateById(publication) != 1) {
                throw new IllegalStateException(displayName + "停用失败: " + serviceCode);
            }
        }
        return publication;
    }

    public String buildPublicUrl(String relativePath) {
        String base = publicBaseUrl == null ? "" : publicBaseUrl.trim();
        URI uri;
        try {
            uri = URI.create(base);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("gis.publication.public-base-url 配置无效", ex);
        }
        if (!("http".equalsIgnoreCase(uri.getScheme())
                || "https".equalsIgnoreCase(uri.getScheme())) || uri.getHost() == null) {
            throw new IllegalStateException("gis.publication.public-base-url 必须是 HTTP(S) 地址");
        }
        String normalizedBase = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        String normalizedPath = relativePath.startsWith("/") ? relativePath : "/" + relativePath;
        return normalizedBase + normalizedPath;
    }

    private GisTask createCompletedPublishTask(
            GisTask sourceTask, String displayName, LocalDateTime now) {
        GisTask task = new GisTask();
        task.setTaskNo(GisTaskFactory.generateTaskNo(
                "GIS_" + sourceTask.getProcessingType() + "_PUBLISH", now));
        task.setTaskName("发布" + displayName + "：" + sourceTask.getTaskName());
        task.setTaskType(3L);
        task.setPriority(0);
        task.setTotalCount(1L);
        task.setCompletedCount(1L);
        task.setFailedCount(0L);
        task.setTaskStatus("COMPLETED");
        task.setCurrentStage("FINISHED");
        task.setDataSetId(sourceTask.getDataSetId());
        task.setProcessingType(sourceTask.getProcessingType());
        task.setTargetCrs(sourceTask.getTargetCrs());
        task.setTileProfile(sourceTask.getTileProfile());
        task.setOutputFormat(sourceTask.getOutputFormat());
        task.setSourceFileMetaId(sourceTask.getSourceFileMetaId());
        task.setOutputKey(sourceTask.getOutputKey());
        task.setParentTaskId(sourceTask.getId());
        task.setRootTaskId(sourceTask.getRootTaskId() == null
                ? sourceTask.getId() : sourceTask.getRootTaskId());
        task.setStartTime(now);
        task.setFinishTime(now);
        task.setCreateTime(now);
        task.setUpdateTime(now);
        task.setDeleted(0);
        return task;
    }

    private String upperTrimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }
}
