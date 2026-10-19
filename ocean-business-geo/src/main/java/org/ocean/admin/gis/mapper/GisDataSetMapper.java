package org.ocean.admin.gis.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.ocean.admin.gis.entity.GisDataSet;

/**
 * @author DeepOcean
 * @since 2026-09-11
 */
@Mapper
public interface GisDataSetMapper extends BaseMapper<GisDataSet> {
    @Update("""
            UPDATE ocean_gis.gis_data_set
            SET total_size_bytes = total_size_bytes + #{delta}, update_time = CURRENT_TIMESTAMP
            WHERE id = #{id} AND deleted = 0 AND total_size_bytes + #{delta} >= 0
            """)
    int adjustTotalSize(@Param("id") Long id, @Param("delta") long delta);
}
