package org.ocean.admin.gis.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.ocean.admin.gis.entity.GisFileMeta;
import org.ocean.admin.gis.entity.GisFileSpatialMetadata;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface GisFileSpatialMetadataMapper {
    List<Long> selectCandidates(@Param("limit") int limit);
    GisFileMeta claim(@Param("id") Long id, @Param("leaseExpiresAt") LocalDateTime leaseExpiresAt);
    GisFileMeta lockClaim(@Param("id") Long id, @Param("version") Long version);
    int renewLease(@Param("id") Long id, @Param("version") Long version, @Param("leaseExpiresAt") LocalDateTime leaseExpiresAt);
    int deleteByFileId(@Param("id") Long id);
    int invalidateDataSetRasters(@Param("dataSetId") Long dataSetId);
    int insert(@Param("item") GisFileSpatialMetadata item);
    List<GisFileSpatialMetadata> selectByFileIds(@Param("ids") List<Long> ids, @Param("includeGeometry") boolean includeGeometry);
}
