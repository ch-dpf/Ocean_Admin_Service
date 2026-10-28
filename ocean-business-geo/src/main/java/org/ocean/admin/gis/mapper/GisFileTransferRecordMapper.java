package org.ocean.admin.gis.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.ocean.admin.gis.entity.GisFileTransferRecord;
import org.ocean.admin.gis.dto.TaskStatus;
import org.ocean.admin.gis.vo.GisFileTransferRecordVO;

import java.time.LocalDateTime;

@Mapper
public interface GisFileTransferRecordMapper extends BaseMapper<GisFileTransferRecord> {

    @org.apache.ibatis.annotations.Select(value = "UPDATE ocean_gis.gis_file_transfer_record "
            + "SET progress_epoch = progress_epoch + 1 "
            + "WHERE record_no = #{progressId} AND deleted = 0 AND operation_type = 'IMPORT' RETURNING progress_epoch", affectData = true)
    @org.apache.ibatis.annotations.Options(useCache = false,
            flushCache = org.apache.ibatis.annotations.Options.FlushCachePolicy.TRUE)
    Long nextProgressEpoch(@Param("progressId") String progressId);


    GisFileTransferRecord lockById(@Param("id") Long id);

    Page<GisFileTransferRecordVO> selectRecordPage(
            Page<GisFileTransferRecordVO> page,
            @Param("dataSetId") Long dataSetId,
            @Param("categoryId") Long categoryId,
            @Param("recordNo") String recordNo,
            @Param("operationType") String operationType,
            @Param("recordStatus") TaskStatus recordStatus,
            @Param("originalName") String originalName,
            @Param("extension") String extension,
            @Param("fileStatus") String fileStatus,
            @Param("createTimeStart") LocalDateTime createTimeStart,
            @Param("createTimeEnd") LocalDateTime createTimeEnd);

    GisFileTransferRecordVO selectRecordByNo(@Param("recordNo") String recordNo);
}
