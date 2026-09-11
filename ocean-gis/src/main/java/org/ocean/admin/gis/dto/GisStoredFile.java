package org.ocean.admin.gis.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 类的功能描述
 *
 * @author DeepOcean
 * @since 2026-09-11
 */
@Data
@Builder
public class GisStoredFile {

    private String storageName;
    private String storageKey;
    private String storageType;
    private Long sizeBytes;
    private String sha256;

}
