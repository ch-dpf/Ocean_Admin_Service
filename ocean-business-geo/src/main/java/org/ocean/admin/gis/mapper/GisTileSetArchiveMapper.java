package org.ocean.admin.gis.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.ocean.admin.gis.dto.TaskStatus;
import org.ocean.admin.gis.entity.GisTileSetArchive;
import org.ocean.admin.gis.processing.GisProcessingType;
import org.ocean.admin.gis.vo.GisTileSetArchiveVO;

import java.time.LocalDateTime;

@Mapper
public interface GisTileSetArchiveMapper extends BaseMapper<GisTileSetArchive> {
    Page<GisTileSetArchiveVO> selectArchivePage(
            Page<GisTileSetArchiveVO> page,
            @Param("archiveNo") String archiveNo,
            @Param("taskNo") String taskNo,
            @Param("taskName") String taskName,
            @Param("processingType") GisProcessingType processingType,
            @Param("archiveStatus") TaskStatus archiveStatus,
            @Param("createTimeStart") LocalDateTime createTimeStart,
            @Param("createTimeEnd") LocalDateTime createTimeEnd);

    GisTileSetArchiveVO selectArchiveByNo(@Param("archiveNo") String archiveNo);
}
