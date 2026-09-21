package org.ocean.admin.gis.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.ocean.admin.gis.entity.GisFileMeta;
import org.ocean.admin.gis.vo.GisFileMetaVO;

import java.time.LocalDateTime;

import java.util.List;

/**
 * @author DeepOcean
 * @since 2026-09-11
 */
@Mapper
public interface GisFileMetaMapper extends BaseMapper<GisFileMeta> {

    /** 使用单条 SQL 批量写入文件元数据。 */
    int insertBatch(@Param("records") List<GisFileMeta> records);

    /**
     * 分页查询已逻辑删除的文件元数据。
     */
    Page<GisFileMetaVO> selectDeletedPage(
            Page<GisFileMetaVO> page,
            @Param("dataSetId") Long dataSetId,
            @Param("categoryId") Long categoryId,
            @Param("originalName") String originalName,
            @Param("extension") String extension,
            @Param("uploadStatus") String uploadStatus);

    /** 绕过逻辑删除拦截读取回收站中的单条记录。 */
    GisFileMeta selectDeletedById(@Param("id") Long id);

    /** 将回收站记录恢复为正常记录。 */
    int restoreDeletedById(
            @Param("id") Long id,
            @Param("updateTime") LocalDateTime updateTime);

    /** 物理删除回收站中的元数据。 */
    int permanentlyDeleteById(@Param("id") Long id);
}
