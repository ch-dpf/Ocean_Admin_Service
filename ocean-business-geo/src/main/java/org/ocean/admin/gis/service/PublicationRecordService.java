package org.ocean.admin.gis.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.entity.GisPublication;
import org.ocean.admin.gis.entity.GisTileSet;
import org.ocean.admin.gis.mapper.GisProcessingInputMapper;
import org.ocean.admin.gis.mapper.GisDataSetMapper;
import org.ocean.admin.gis.mapper.GisPublicationMapper;
import org.ocean.admin.gis.mapper.GisTileSetMapper;
import org.ocean.admin.gis.processing.GisProcessingType;
import org.ocean.admin.kernel.common.PageResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 各类瓦片服务共用的发布记录、状态和瓦片集持久化操作。 */
@Service
@RequiredArgsConstructor
public class PublicationRecordService {
    public static final String PUBLISHED = "PUBLISHED";
    public static final String DISABLED = "DISABLED";
    private static final Set<String> STATUSES = Set.of(PUBLISHED, DISABLED);

    private final GisPublicationMapper publicationMapper;
    private final GisTileSetMapper tileSetMapper;
    private final GisProcessingInputMapper inputMapper;
    private final GisDataSetMapper dataSetMapper;

    @Value("${gis.publication.public-base-url:http://localhost:8090}")
    private String publicBaseUrl;

    public PageResult<List<GisPublication>> getPublicationPage(
            GisProcessingType processingType, Integer current, Integer size, String serviceCode,
            String status, Long dataSetId, Long sourceTaskId,
            LocalDateTime publishTimeStart, LocalDateTime publishTimeEnd) {
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
                .like(normalizedCode != null, GisPublication::getServiceCode, normalizedCode)
                .eq(normalizedStatus != null, GisPublication::getStatus, normalizedStatus)
                .eq(dataSetId != null, GisPublication::getDataSetId, dataSetId)
                .eq(sourceTaskId != null, GisPublication::getSourceTaskId, sourceTaskId)
                .ge(publishTimeStart != null, GisPublication::getPublishTime, publishTimeStart)
                .le(publishTimeEnd != null, GisPublication::getPublishTime, publishTimeEnd)
                .orderByDesc(GisPublication::getPublishTime);
        if (processingType != null) {
            query.eq(GisPublication::getProcessingType, processingType.name());
        }
        Page<GisPublication> page = publicationMapper.selectPage(
                new Page<>(currentPage, pageSize), query);
        return new PageResult<>(page.getCurrent(), page.getSize(), page.getTotal(),
                page.getRecords());
    }

    public GisPublication getRequired(GisProcessingType processingType, String serviceCode) {
        GisPublication publication = publicationMapper.selectOne(
                new LambdaQueryWrapper<GisPublication>()
                        .eq(GisPublication::getServiceCode, serviceCode)
                        .eq(GisPublication::getProcessingType, processingType.name()));
        if (publication == null) {
            throw new IllegalArgumentException(
                    processingType.name() + "发布服务不存在: " + serviceCode);
        }
        return publication;
    }

    public GisPublication findBySourceTaskId(
            GisProcessingType processingType, Long sourceTaskId) {
        GisTileSet tileSet = tileSetMapper.selectByTaskId(sourceTaskId);
        if (tileSet == null || processingType != tileSet.getTileType()) {
            return null;
        }
        return publicationMapper.selectOne(new LambdaQueryWrapper<GisPublication>()
                .eq(GisPublication::getTileSetId, tileSet.getId()));
    }

    /** 统一发布记录的保存入口；同一任务只保留一条有效记录。 */
    @Transactional(rollbackFor = Exception.class)
    public GisPublication saveUnified(GisPublication publication) {
        GisPublication existing = publicationMapper.selectOne(
                new LambdaQueryWrapper<GisPublication>()
                        .eq(GisPublication::getSourceTaskId, publication.getSourceTaskId()));
        if (existing == null) {
            if (publicationMapper.insert(publication) != 1) {
                throw new IllegalStateException("统一发布记录保存失败");
            }
            return publication;
        }
        publication.setId(existing.getId());
        publication.setServiceCode(existing.getServiceCode());
        if (publicationMapper.updateById(publication) != 1) {
            throw new IllegalStateException("统一发布记录更新失败");
        }
        return publication;
    }

    @Transactional(rollbackFor = Exception.class)
    public GisPublication disable(
            GisProcessingType processingType, String serviceCode, String displayName) {
        GisPublication publication = getRequired(processingType, serviceCode);
        if (!DISABLED.equals(publication.getStatus())) {
            publication.setStatus(DISABLED);
            publication.setUpdateTime(LocalDateTime.now());
            if (publicationMapper.update(null, new LambdaUpdateWrapper<GisPublication>()
                    .eq(GisPublication::getId, publication.getId())
                    .set(GisPublication::getStatus, DISABLED)
                    .set(GisPublication::getUpdateTime, publication.getUpdateTime())) != 1) {
                throw new IllegalStateException(displayName + "停用失败: " + serviceCode);
            }
        }
        return publication;
    }

    @Transactional(rollbackFor = Exception.class)
    public GisPublication rename(GisProcessingType processingType, String serviceCode, String serviceName) {
        String normalizedName = normalizeServiceName(serviceName);
        GisPublication publication = getRequired(processingType, serviceCode);
        LocalDateTime now = LocalDateTime.now();
        // 仅更新名称与时间，避免覆盖并发停用操作修改的状态。
        int affected = publicationMapper.update(null, new LambdaUpdateWrapper<GisPublication>()
                .eq(GisPublication::getId, publication.getId())
                .set(GisPublication::getServiceName, normalizedName)
                .set(GisPublication::getUpdateTime, now));
        if (affected != 1) {
            throw new IllegalStateException("服务重命名失败: " + serviceCode);
        }
        publication.setServiceName(normalizedName);
        publication.setUpdateTime(now);
        return publication;
    }

    private String normalizeServiceName(String serviceName) {
        String name = serviceName == null ? "" : serviceName.strip();
        if (name.isBlank() || name.length() > 200) {
            throw new IllegalArgumentException("服务名称不能为空且最多200个字符");
        }
        return name;
    }

    public GisTileSet getRequiredTileSet(Long taskId, GisProcessingType processingType) {
        GisTileSet tileSet = tileSetMapper.selectByTaskId(taskId);
        if (tileSet == null || processingType != tileSet.getTileType()) {
            throw new IllegalArgumentException("任务没有对应类型的瓦片集: " + taskId);
        }
        return tileSet;
    }

    public GisTileSet getPublicationTileSet(GisPublication publication) {
        GisTileSet tileSet = tileSetMapper.selectById(publication.getTileSetId());
        if (tileSet == null) {
            throw new IllegalStateException("发布记录关联的瓦片集不存在");
        }
        return tileSet;
    }

    public String publicBaseUrl() {
        return publicBaseUrl;
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

    public String buildServiceRoot(GisProcessingType processingType, String serviceCode) {
        return buildPublicUrl("/gis/publications/" + processingType.name()
                + "/" + serviceCode + "/");
    }

    private Long commonDataSetId(Long taskId) {
        Long result = null;
        for (var input : inputMapper.selectByTaskId(taskId)) {
            Long dataSetId = input.getSourceDataSetId();
            if (dataSetId == null) return null;
            if (result == null) {
                result = dataSetId;
            } else if (!result.equals(dataSetId)) {
                return null;
            }
        }
        return result != null && dataSetMapper.selectById(result) != null ? result : null;
    }

    private String upperTrimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }
}
