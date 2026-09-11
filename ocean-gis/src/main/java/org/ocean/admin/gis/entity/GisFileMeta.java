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

    /** 所属上传任务 */
    private Long taskId;

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

    /** 上传人 */
    private Long uploadedBy;

    /** PENDING、READY、FAILED */
    private String uploadStatus;

    /** 文件处理错误信息 */
    private String errorMessage;

    /** 上传时间 */
    private LocalDateTime createTime;

    /** 修改或解析完成时间 */
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;

}
