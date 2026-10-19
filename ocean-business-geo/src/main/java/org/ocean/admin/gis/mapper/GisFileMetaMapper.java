package org.ocean.admin.gis.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.ocean.admin.gis.entity.GisFileMeta;
import org.ocean.admin.gis.vo.GisFileMetaVO;
import org.ocean.admin.gis.vo.GisDataSetVO;
import org.ocean.admin.gis.dto.GisFileQuery;

import java.time.LocalDateTime;

import java.util.List;

/**
 * @author DeepOcean
 * @since 2026-09-11
 */
@Mapper
public interface GisFileMetaMapper extends BaseMapper<GisFileMeta> {

    List<GisDataSetVO.FileSummary> selectDataSetFileSummaries(@Param("ids") List<Long> ids);

    List<GisDataSetVO.FilePoint> selectDataSetFilePoints(@Param("ids") List<Long> ids);

    Page<GisFileMetaVO> selectFilePage(Page<GisFileMetaVO> page,
            @Param("q") GisFileQuery query, @Param("deleted") int deleted);

    /** 使用单条 SQL 批量写入文件元数据。 */
    int insertBatch(@Param("records") List<GisFileMeta> records);

    /** 绕过逻辑删除拦截读取回收站中的单条记录。 */
    GisFileMeta selectDeletedById(@Param("id") Long id);

    /** 将回收站记录恢复为正常记录。 */
    int restoreDeletedById(
            @Param("id") Long id,
            @Param("updateTime") LocalDateTime updateTime);

    /** 物理删除回收站中的元数据。 */
    int permanentlyDeleteById(@Param("id") Long id);

    /** 统计数据集中的全部文件元数据，包含回收站记录。 */
    long countAllByDataSetId(@Param("dataSetId") Long dataSetId);
}
