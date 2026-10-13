package org.ocean.admin.gis.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.entity.GisPublication;
import org.ocean.admin.gis.entity.GisTileSet;
import org.ocean.admin.gis.mapper.GisPublicationMapper;
import org.ocean.admin.gis.mapper.GisFileMetaMapper;
import org.ocean.admin.gis.mapper.GisProcessingInputMapper;
import org.ocean.admin.gis.mapper.GisTileSetMapper;
import org.ocean.admin.gis.processing.GisProcessingType;
import org.ocean.admin.gis.vo.GisPublicationVO;
import org.ocean.admin.kernel.common.PageResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 各类瓦片集共用的发布记录和状态管理。 */
@Service
@RequiredArgsConstructor
public class GisPublicationService {
    public static final String PUBLISHED = "PUBLISHED";
    public static final String DISABLED = "DISABLED";
    private static final Set<String> STATUSES = Set.of(PUBLISHED, DISABLED);

    private final GisPublicationMapper publicationMapper;
    private final GisTileSetMapper tileSetMapper;
    private final GisProcessingInputMapper inputMapper;
    private final GisFileMetaMapper fileMetaMapper;

    @Value("${gis.publication.public-base-url:http://localhost:8090}")
    private String publicBaseUrl;

    public PageResult<List<GisPublication>> getPublicationPage(
            String processingType, Integer current, Integer size, String serviceCode,
            String status, Long dataSetId, LocalDateTime publishTimeStart,
            LocalDateTime publishTimeEnd) {
        String type = normalizeProcessingType(processingType, true);
        return queryPublicationPage(type, current, size, serviceCode, status, dataSetId,
                publishTimeStart, publishTimeEnd);
    }

    public PageResult<List<GisPublicationVO>> getAllPublicationPage(
            Integer current, Integer size, String processingType, String serviceCode,
            String status, Long dataSetId, LocalDateTime publishTimeStart,
            LocalDateTime publishTimeEnd) {
        String type = normalizeProcessingType(processingType, false);
        PageResult<List<GisPublication>> page = queryPublicationPage(type, current, size,
                serviceCode, status, dataSetId, publishTimeStart, publishTimeEnd);
        return new PageResult<>(page.getCurrent(), page.getSize(), page.getTotal(),
                page.getRecords().stream().map(this::toPublicationVO).toList());
    }

    private PageResult<List<GisPublication>> queryPublicationPage(
            String processingType, Integer current, Integer size, String serviceCode,
            String status, Long dataSetId, LocalDateTime publishTimeStart,
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
                .like(normalizedCode != null, GisPublication::getServiceCode, normalizedCode)
                .eq(normalizedStatus != null, GisPublication::getStatus, normalizedStatus)
                .eq(dataSetId != null, GisPublication::getDataSetId, dataSetId)
                .ge(publishTimeStart != null, GisPublication::getPublishTime, publishTimeStart)
                .le(publishTimeEnd != null, GisPublication::getPublishTime, publishTimeEnd)
                .orderByDesc(GisPublication::getPublishTime);
        if (processingType != null) {
            query.inSql(GisPublication::getTileSetId,
                    "SELECT id FROM ocean_gis.gis_tile_set WHERE tile_type = '"
                            + processingType + "'");
        }
        Page<GisPublication> page = publicationMapper.selectPage(
                new Page<>(currentPage, pageSize), query);
        return new PageResult<>(page.getCurrent(), page.getSize(), page.getTotal(),
                page.getRecords());
    }

    public GisPublication getRequired(String processingType, String serviceCode) {
        String type = GisProcessingType.valueOf(processingType).name();
        GisPublication publication = publicationMapper.selectOne(
                new LambdaQueryWrapper<GisPublication>()
                        .eq(GisPublication::getServiceCode, serviceCode)
                        .inSql(GisPublication::getTileSetId,
                                "SELECT id FROM ocean_gis.gis_tile_set WHERE tile_type = '"
                                        + type + "'"));
        if (publication == null) {
            throw new IllegalArgumentException(type + "发布服务不存在: " + serviceCode);
        }
        return publication;
    }

    public GisPublication findBySourceTaskId(String processingType, Long sourceTaskId) {
        GisTileSet tileSet = tileSetMapper.selectByTaskId(sourceTaskId);
        if (tileSet == null || !processingType.equals(tileSet.getTileType())) {
            return null;
        }
        return publicationMapper.selectOne(new LambdaQueryWrapper<GisPublication>()
                .eq(GisPublication::getTileSetId, tileSet.getId()));
    }

    @Transactional(rollbackFor = Exception.class)
    public GisPublication publish(GisTileSet tileSet, String initialServiceCode,
            String displayName) {
        GisPublication publication = publicationMapper.selectOne(
                new LambdaQueryWrapper<GisPublication>()
                        .eq(GisPublication::getTileSetId, tileSet.getId()));
        if (publication != null && PUBLISHED.equals(publication.getStatus())) {
            return publication;
        }
        LocalDateTime now = LocalDateTime.now();
        if (publication == null) {
            publication = new GisPublication();
            publication.setServiceCode(initialServiceCode);
            publication.setTileSetId(tileSet.getId());
            publication.setDataSetId(commonDataSetId(tileSet.getTaskId()));
            publication.setDeleted(0);
        }
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

    public GisTileSet getRequiredTileSet(Long taskId, String processingType) {
        GisTileSet tileSet = tileSetMapper.selectByTaskId(taskId);
        if (tileSet == null || !processingType.equals(tileSet.getTileType())) {
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

    private Long commonDataSetId(Long taskId) {
        Long result = null;
        for (var input : inputMapper.selectByTaskId(taskId)) {
            if (input.getFileMetaId() == null) {
                return null;
            }
            var fileMeta = fileMetaMapper.selectById(input.getFileMetaId());
            if (fileMeta == null) {
                return null;
            }
            if (result == null) {
                result = fileMeta.getDataSetId();
            } else if (!result.equals(fileMeta.getDataSetId())) {
                return null;
            }
        }
        return result;
    }

    private GisPublicationVO toPublicationVO(GisPublication publication) {
        GisTileSet tileSet = getPublicationTileSet(publication);
        return GisPublicationVO.builder()
                .id(publication.getId())
                .serviceCode(publication.getServiceCode())
                .processingType(tileSet.getTileType())
                .tileSetId(tileSet.getId())
                .sourceTaskId(tileSet.getTaskId())
                .dataSetId(publication.getDataSetId())
                .status(publication.getStatus())
                .outputKey(tileSet.getOutputKey())
                .targetCrs(tileSet.getTargetCrs())
                .tileProfile(tileSet.getTileProfile())
                .outputFormat(tileSet.getOutputFormat())
                .minZoom(tileSet.getMinZoom())
                .maxZoom(tileSet.getMaxZoom())
                .serviceUrl(buildPublicUrl(servicePath(tileSet.getTileType(),
                        publication.getServiceCode())))
                .publishTime(publication.getPublishTime())
                .updateTime(publication.getUpdateTime())
                .build();
    }

    private String servicePath(String processingType, String serviceCode) {
        return switch (GisProcessingType.valueOf(processingType)) {
            case TERRAIN -> "/terrain/" + serviceCode + "/";
            case IMAGERY -> "/imagery/" + serviceCode + "/";
            case VECTOR -> "/vector/" + serviceCode + "/";
        };
    }

    private String normalizeProcessingType(String value, boolean required) {
        String normalized = upperTrimToNull(value);
        if (normalized == null) {
            if (required) {
                throw new IllegalArgumentException("发布类型不能为空");
            }
            return null;
        }
        try {
            return GisProcessingType.valueOf(normalized).name();
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "发布类型只能为 TERRAIN、IMAGERY 或 VECTOR", ex);
        }
    }

    private String upperTrimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }
}
