package org.ocean.admin.gis.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Gis 文件元数据实体类
 *
 * @author DeepOcean
 * @since 2026-09-11
 */
@Data
@TableName(value = "gis_file_meta",schema = "ocean_gis")
public class GisFileMeta {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属数据集 */
    private Long dataSetId;
    /** UPLOAD 使用托管存储，WORKSPACE 原位引用受控服务器文件。 */
    private String sourceType = "UPLOAD";
    private String workspaceCode;
    private String relativePath;
    /** 登记时的源文件修改时间，Unix 毫秒。 */
    private Long sourceModifiedAt;
    private String vectorPlacement;
    private Long vectorDatasetStyleId;

    /** 用户上传时的文件名 */
    private String originalName;

    /** 系统生成的存储文件名 */
    private String storageName;

    /** 相对路径或对象存储 Key，不保存机器绝对路径 */
    private String storageKey;

    /** LOCAL、MINIO、S3 */
    private String storageType;

    /** 文件扩展名，例如 tif */
    private String extension;

    /** 字节数 */
    private Long sizeBytes;

    /** 文件内容 SHA-256，用于完整性校验和去重 */
    private String sha256;

    /** 登记人 */
    private Long createdBy;

    /** PENDING、READY、FAILED */
    private String fileStatus;

    /** NOT_REQUIRED、PENDING、COMPLETED、FAILED */
    private String cleanupStatus;

    /** 文件处理错误信息 */
    private String errorMessage;

    /** WAITING（前置阶段未结束）、QUEUED、RUNNING、COMPLETED、FAILED、NOT_APPLICABLE。 */
    private String metadataStatus;
    private Long metadataVersion;
    private LocalDateTime metadataLeaseExpiresAt;
    private String metadataErrorCode;
    private String metadataErrorMessage;
    private LocalDateTime metadataExtractedAt;

    /** 元数据完成时保存的文件代表点（EPSG:4326），失效时与来源信息一并清空。 */
    private Double representativeLongitude;
    private Double representativeLatitude;
    private String representativeLayerKey;
    private String representativeExtentKind;

    /** 登记时间 */
    private LocalDateTime createTime;

    /** 修改或解析完成时间 */
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;

}
