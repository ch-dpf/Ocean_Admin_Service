package org.ocean.admin.gis.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.ocean.admin.gis.entity.GisFileTransferRecordItem;

import java.util.List;

@Mapper
public interface GisFileTransferRecordItemMapper extends BaseMapper<GisFileTransferRecordItem> {

    int insertBatch(@Param("records") List<GisFileTransferRecordItem> records);

    List<GisFileTransferRecordItem> selectByRecordId(@Param("recordId") Long recordId);

    /** 彻底删除文件元数据前解除引用，历史快照字段保持不变。 */
    int clearFileMetaReference(@Param("fileMetaId") Long fileMetaId);
}
