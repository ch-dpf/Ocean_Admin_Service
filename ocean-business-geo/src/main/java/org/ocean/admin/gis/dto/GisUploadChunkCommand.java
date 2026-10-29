package org.ocean.admin.gis.dto;

/** 一次 GIS 文件分块上传请求的不可变元数据。 */
public record GisUploadChunkCommand(
        String requestId,
        Long dataSetId,
        int fileIndex,
        int fileCount,
        String fileName,
        long fileSize,
        long totalSize,
        long offset,
        long chunkSize,
        String vectorPlacement) {

    public GisUploadChunkCommand {
        if (vectorPlacement == null || !java.util.Set.of("AUTO", "UNKNOWN").contains(vectorPlacement)) throw new IllegalArgumentException("矢量归组方式须为AUTO或UNKNOWN");
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("上传请求标识不能为空");
        }
        if (dataSetId == null || dataSetId <= 0) {
            throw new IllegalArgumentException("数据集ID必须大于0");
        }
        if (fileIndex < 0) {
            throw new IllegalArgumentException("文件序号不能为负数");
        }
        if (fileCount < 1 || fileIndex >= fileCount) {
            throw new IllegalArgumentException("文件序号超出批次范围");
        }
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("上传文件名不能为空");
        }
        if (fileSize < 1) {
            throw new IllegalArgumentException("上传文件不能为空");
        }
        if (totalSize < fileSize) {
            throw new IllegalArgumentException("批次总大小不能小于当前文件大小");
        }
        if (offset < 0 || offset >= fileSize) {
            throw new IllegalArgumentException("分块偏移量超出文件范围");
        }
        if (chunkSize < 1 || chunkSize > fileSize - offset) {
            throw new IllegalArgumentException("分块大小超出文件剩余范围");
        }
    }

    public boolean lastChunk() {
        return offset + chunkSize == fileSize;
    }

    public boolean lastFile() {
        return fileIndex + 1 == fileCount;
    }
}
