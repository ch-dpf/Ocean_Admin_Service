package org.ocean.admin.gis.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 已暂存、等待正式入库的 GIS 文件。
 * 不保存 MultipartFile，确保可以安全传递给异步线程。
 */
@Data
@Builder
public class GisStagedFile {

    /** 用户上传的原始文件名 */
    private String originalName;

    /** 系统生成的文件名 */
    private String storageName;

    /** 临时存储相对路径，不保存机器绝对路径 */
    private String stagingKey;

    /** 文件扩展名，例如 tif */
    private String extension;

    /** 文件大小 */
    private Long sizeBytes;

    /** 文件内容摘要，可在暂存时计算 */
    private String sha256;

    /** 上传 Content-Type，仅作辅助信息，不能作为类型校验依据 */
    private String contentType;

}
