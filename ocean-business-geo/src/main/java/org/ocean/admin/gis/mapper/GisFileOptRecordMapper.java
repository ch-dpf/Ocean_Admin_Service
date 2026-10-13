package org.ocean.admin.gis.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.ocean.admin.gis.entity.GisFileOptRecord;
import org.ocean.admin.gis.vo.GisFileOptRecordVO;

import java.time.LocalDateTime;

@Mapper
public interface GisFileOptRecordMapper extends BaseMapper<GisFileOptRecord> {

    Page<GisFileOptRecordVO> selectImportRecordPage(
            Page<GisFileOptRecordVO> page,
            @Param("dataSetId") Long dataSetId,
            @Param("categoryId") Long categoryId,
            @Param("recordNo") String recordNo,
            @Param("recordStatus") String recordStatus,
            @Param("originalName") String originalName,
            @Param("extension") String extension,
            @Param("uploadStatus") String uploadStatus,
            @Param("createTimeStart") LocalDateTime createTimeStart,
            @Param("createTimeEnd") LocalDateTime createTimeEnd);

    GisFileOptRecordVO selectImportRecordByNo(@Param("recordNo") String recordNo);
}
