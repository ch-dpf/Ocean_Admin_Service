package org.ocean.admin.gis.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.dto.GisStagedFile;
import org.ocean.admin.gis.dto.GisStoredFile;
import org.ocean.admin.gis.dto.GisUploadFileItem;
import org.ocean.admin.gis.entity.GisFileMeta;
import org.ocean.admin.gis.mapper.GisFileMetaMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** GIS 文件元数据服务。 */
@Service
@RequiredArgsConstructor
public class GisFileMetaService {

    private final GisFileMetaMapper gisFileMetaMapper;

    public List<GisUploadFileItem> createPendingFiles(
            Long taskId,
            Long dataSetId,
            List<GisStagedFile> stagedFiles) {
        LocalDateTime now = LocalDateTime.now();
        List<GisUploadFileItem> items = new ArrayList<>(stagedFiles.size());

        for (GisStagedFile staged : stagedFiles) {
            GisFileMeta meta = new GisFileMeta();
            meta.setTaskId(taskId);
            meta.setDataSetId(dataSetId);
            meta.setOriginalName(staged.getOriginalName());
            meta.setStorageName(staged.getStorageName());
            meta.setStorageKey(staged.getStagingKey());
            meta.setStorageType("LOCAL");
            meta.setExtension(staged.getExtension());
            meta.setSizeBytes(staged.getSizeBytes());
            meta.setSha256(staged.getSha256());
            meta.setUploadStatus("PENDING");
            meta.setCreateTime(now);
            meta.setUpdateTime(now);
            meta.setDeleted(0);
            if (gisFileMetaMapper.insert(meta) != 1) {
                throw new IllegalStateException("文件元数据创建失败: " + staged.getOriginalName());
            }
            items.add(new GisUploadFileItem(meta.getId(), staged));
        }
        return items;
    }

    public void markReady(Long fileMetaId, GisStoredFile storedFile) {
        int updated = gisFileMetaMapper.update(null,
                new LambdaUpdateWrapper<GisFileMeta>()
                        .eq(GisFileMeta::getId, fileMetaId)
                        .eq(GisFileMeta::getUploadStatus, "PENDING")
                        .set(GisFileMeta::getStorageName, storedFile.getStorageName())
                        .set(GisFileMeta::getStorageKey, storedFile.getStorageKey())
                        .set(GisFileMeta::getStorageType, storedFile.getStorageType())
                        .set(GisFileMeta::getSizeBytes, storedFile.getSizeBytes())
                        .set(GisFileMeta::getSha256, storedFile.getSha256())
                        .set(GisFileMeta::getUploadStatus, "READY")
                        .set(GisFileMeta::getErrorMessage, null)
                        .set(GisFileMeta::getUpdateTime, LocalDateTime.now()));
        if (updated != 1) {
            throw new IllegalStateException("文件元数据转正失败: " + fileMetaId);
        }
    }

    public void markFailed(Long fileMetaId, String errorMessage) {
        gisFileMetaMapper.update(null,
                new LambdaUpdateWrapper<GisFileMeta>()
                        .eq(GisFileMeta::getId, fileMetaId)
                        .eq(GisFileMeta::getUploadStatus, "PENDING")
                        .set(GisFileMeta::getUploadStatus, "FAILED")
                        .set(GisFileMeta::getErrorMessage, abbreviate(errorMessage, 1000))
                        .set(GisFileMeta::getUpdateTime, LocalDateTime.now()));
    }

    public void markTaskPendingFilesFailed(Long taskId, String errorMessage) {
        gisFileMetaMapper.update(null,
                new LambdaUpdateWrapper<GisFileMeta>()
                        .eq(GisFileMeta::getTaskId, taskId)
                        .eq(GisFileMeta::getUploadStatus, "PENDING")
                        .set(GisFileMeta::getUploadStatus, "FAILED")
                        .set(GisFileMeta::getErrorMessage, abbreviate(errorMessage, 1000))
                        .set(GisFileMeta::getUpdateTime, LocalDateTime.now()));
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
