package org.ocean.admin.gis.dto;

/** 上传任务中的单个文件工作项。 */
public record GisUploadFileItem(Long fileMetaId, GisStagedFile stagedFile) {
}
