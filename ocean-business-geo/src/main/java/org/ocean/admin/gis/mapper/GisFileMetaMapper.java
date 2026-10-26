package org.ocean.admin.gis.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.ocean.admin.gis.entity.GisFileMeta;
import org.ocean.admin.gis.vo.GisFileMetaVO;
import org.ocean.admin.gis.vo.GisDataSetVO;
import org.ocean.admin.gis.dto.GisFileQuery;

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
            @Param("q") GisFileQuery query);

    /** 使用单条 SQL 批量写入文件元数据。 */
    int insertBatch(@Param("records") List<GisFileMeta> records);

    GisFileMeta selectActiveByIdForUpdate(@Param("id") Long id);

    /** 锁定数据集中的全部文件，包含调整前遗留的逻辑删除记录。 */
    List<GisFileMeta> selectAllByDataSetIdForUpdate(@Param("dataSetId") Long dataSetId);

    int physicallyDeleteActiveById(@Param("id") Long id);

}
