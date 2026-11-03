package org.ocean.admin.gis.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.ocean.admin.gis.entity.GisFileGroup;
import org.ocean.admin.gis.entity.GisFileMeta;

import java.util.List;

/** 文件组及其成员的一致性查询和更新。 */
@Mapper
public interface GisFileGroupMapper extends BaseMapper<GisFileGroup> {
    GisFileGroup lock(@Param("id") Long id);
    List<GisFileGroup> lockByDataSet(@Param("dataSetId") Long dataSetId);
    List<GisFileMeta> ingestionFiles(@Param("progressId") String progressId);
    List<GisFileMeta> members(@Param("groupId") Long groupId);
    GisFileGroup canonical(@Param("dataSetId") Long dataSetId, @Param("fingerprint") String fingerprint);
    int attach(@Param("id") Long id, @Param("groupId") Long groupId, @Param("path") String path,
               @Param("outcome") String outcome);
    int outcome(@Param("groupId") Long groupId, @Param("progressId") String progressId, @Param("outcome") String outcome);
    int park(@Param("id") Long id, @Param("code") String code, @Param("message") String message);
    int queueEntry(@Param("id") Long id);
    int setSha256(@Param("id") Long id, @Param("sha256") String sha256);
    int updateState(@Param("group") GisFileGroup group);
    int clearEntryMetadata(@Param("id") Long id);
    int physicallyDelete(@Param("id") Long id);
}
