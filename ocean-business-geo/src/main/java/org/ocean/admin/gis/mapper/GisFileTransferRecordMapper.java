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

    Page<GisFileTransferRecordVO> selectRecordPage(
            Page<GisFileTransferRecordVO> page,
            @Param("dataSetId") Long dataSetId,
            @Param("categoryId") Long categoryId,
            @Param("recordNo") String recordNo,
            @Param("operationType") String operationType,
            @Param("recordStatus") TaskStatus recordStatus,
            @Param("originalName") String originalName,
            @Param("extension") String extension,
            @Param("uploadStatus") String uploadStatus,
            @Param("createTimeStart") LocalDateTime createTimeStart,
            @Param("createTimeEnd") LocalDateTime createTimeEnd);

    GisFileTransferRecordVO selectRecordByNo(@Param("recordNo") String recordNo);
}
