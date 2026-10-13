package org.ocean.admin.gis.vo;

import lombok.Builder;
import lombok.Data;
import org.ocean.admin.gis.dto.GisProcessingParameters;

import java.time.LocalDateTime;
import java.util.List;

/** 处理任务、不可变输入和唯一瓦片集组成的聚合详情。 */
@Data
@Builder
public class GisProcessingTaskDetailVO {
    private GisProcessingTaskSummaryVO task;
    private Integer parameterSchemaVersion;
    private String requestFingerprint;
    private GisProcessingParameters parameters;
    private List<InputVO> inputs;
    private TileSetVO tileSet;

    public record InputVO(
            Long id,
            Integer sequenceNo,
            String inputKind,
            String inputStatus,
            Long fileMetaId,
            String workspaceCode,
            String relativePath,
            String storageKey,
            String originalName,
            String extension,
            Long sizeBytes,
            String sha256,
            String errorMessage,
            LocalDateTime createTime,
            LocalDateTime updateTime) {
    }

    public record TileSetVO(
            Long id,
            String tileType,
            String tileSetStatus,
            String outputKey,
            String targetCrs,
            String tileProfile,
            String outputFormat,
            Integer minZoom,
            Integer maxZoom,
            String manifestKey,
            String errorMessage,
            LocalDateTime createTime,
            LocalDateTime updateTime) {
    }
}
