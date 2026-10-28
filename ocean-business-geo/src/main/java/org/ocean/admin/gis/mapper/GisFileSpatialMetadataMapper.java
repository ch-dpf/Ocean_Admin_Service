package org.ocean.admin.gis.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.ocean.admin.gis.entity.GisFileMeta;
import org.ocean.admin.gis.entity.GisFileSpatialMetadata;
import org.ocean.admin.gis.vo.GisDataSetVO;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface GisFileSpatialMetadataMapper {
    GisFileMeta selectRepresentativePoint(@Param("id") Long fileId);
    List<GisDataSetVO.FootprintRow> selectDataSetFootprints(@Param("ids") List<Long> ids,
            @Param("tolerance") double tolerance);
    List<Long> selectCandidates(@Param("limit") int limit);
    List<Long> releaseUploadBatch(@Param("recordId") Long recordId);
    List<Long> releaseWorkspaceFiles(@Param("ids") List<Long> ids);
    int renewWorkspaceWaiting(@Param("ids") List<Long> ids, @Param("leaseExpiresAt") LocalDateTime leaseExpiresAt);
    List<Long> expireWorkspaceWaiting();
    int failWaitingUpload(@Param("recordId") Long recordId, @Param("message") String message);
    GisFileMeta claim(@Param("id") Long id, @Param("leaseExpiresAt") LocalDateTime leaseExpiresAt);
    GisFileMeta lockClaim(@Param("id") Long id, @Param("version") Long version);
    int renewLease(@Param("id") Long id, @Param("version") Long version, @Param("leaseExpiresAt") LocalDateTime leaseExpiresAt);
    int deleteByFileId(@Param("id") Long id);
    int invalidateDataSetRasters(@Param("dataSetId") Long dataSetId);
    int insert(@Param("item") GisFileSpatialMetadata item);
    List<GisFileSpatialMetadata> selectByFileIds(@Param("ids") List<Long> ids, @Param("includeGeometry") boolean includeGeometry);
}
