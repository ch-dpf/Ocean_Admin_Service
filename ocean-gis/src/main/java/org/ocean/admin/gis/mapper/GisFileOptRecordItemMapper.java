package org.ocean.admin.gis.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.ocean.admin.gis.entity.GisFileMeta;
import org.ocean.admin.gis.entity.GisFileOptRecordItem;

import java.util.List;

@Mapper
public interface GisFileOptRecordItemMapper extends BaseMapper<GisFileOptRecordItem> {

    int insertBatch(@Param("records") List<GisFileOptRecordItem> records);

    List<GisFileMeta> selectFileMetasByRecordId(@Param("recordId") Long recordId);
}
