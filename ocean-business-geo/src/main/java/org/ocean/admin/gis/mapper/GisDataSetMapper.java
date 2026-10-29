package org.ocean.admin.gis.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.ocean.admin.gis.entity.GisDataSet;

/**
 * @author DeepOcean
 * @since 2026-09-11
 */
@Mapper
public interface GisDataSetMapper extends BaseMapper<GisDataSet> {
    @Select("SELECT * FROM ocean_gis.gis_data_set WHERE id = #{id} AND deleted = 0 FOR UPDATE")
    GisDataSet selectActiveByIdForUpdate(@Param("id") Long id);

    @Delete("DELETE FROM ocean_gis.gis_data_set WHERE id = #{id} AND deleted = 0")
    int physicallyDeleteById(@Param("id") Long id);

    @Select("""
            SELECT EXISTS (
                SELECT 1 FROM ocean_gis.gis_file_transfer_record
                WHERE data_set_id = #{dataSetId} AND deleted = 0
                  AND record_status IN ('QUEUED', 'RUNNING')
            )
            """)
    boolean existsActiveTransfersByDataSetId(@Param("dataSetId") Long dataSetId);

    @Update("""
            UPDATE ocean_gis.gis_data_set
            SET file_count = file_count + #{countDelta},
                total_size_bytes = total_size_bytes + #{sizeDelta},
                update_time = CURRENT_TIMESTAMP
            WHERE id = #{id} AND deleted = 0
              AND file_count + #{countDelta} >= 0
              AND total_size_bytes + #{sizeDelta} >= 0
            """)
    int adjustFileStatistics(@Param("id") Long id, @Param("countDelta") int countDelta,
            @Param("sizeDelta") long sizeDelta);
}
