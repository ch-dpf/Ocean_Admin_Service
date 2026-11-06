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

    @org.apache.ibatis.annotations.Select("""
            WITH outcomes AS (
                SELECT CASE
                    WHEN g.parse_status = 'FAILED' THEN 'FAILED'
                    WHEN g.parse_status IN ('QUEUED','RUNNING') THEN 'PENDING'
                    WHEN g.assembly_status = 'DUPLICATE' OR f.ingest_outcome = 'DUPLICATE' THEN 'DUPLICATE'
                    WHEN g.parse_status = 'COMPLETED' THEN 'COMPLETED'
                    WHEN g.id IS NOT NULL THEN 'PARKED'
                    WHEN f.metadata_status = 'FAILED' THEN 'FAILED'
                    ELSE 'PARKED' END AS outcome
                FROM ocean_gis.gis_file_meta f
                LEFT JOIN ocean_gis.gis_file_group g ON g.id = f.file_group_id AND g.deleted = 0
                WHERE f.deleted = 0 AND f.ingest_progress_id = #{progressId}
            )
            SELECT count(*) AS total,
                   count(*) FILTER (WHERE outcome = 'COMPLETED') AS succeeded,
                   count(*) FILTER (WHERE outcome = 'FAILED') AS failed,
                   count(*) FILTER (WHERE outcome IN ('DUPLICATE','PARKED')) AS skipped,
                   count(*) FILTER (WHERE outcome = 'DUPLICATE') AS duplicate,
                   count(*) FILTER (WHERE outcome = 'PARKED') AS parked
            FROM outcomes
            """)
    IngestionCounts ingestionCounts(@Param("progressId") String progressId);

    /** 真实文件按所属组的解析结果计数，配套文件不独立计为解析成功。 */
    record IngestionCounts(long total, long succeeded, long failed, long skipped, long duplicate, long parked) { }

    @org.apache.ibatis.annotations.Select("""
            WITH batch_groups AS (
                SELECT DISTINCT g.id, g.assembly_status, g.parse_status
                FROM ocean_gis.gis_file_meta f
                JOIN ocean_gis.gis_file_group g ON g.id = f.file_group_id AND g.deleted = 0
                WHERE f.deleted = 0 AND f.ingest_progress_id = #{progressId}
            ), outcomes AS (
                SELECT CASE
                    WHEN parse_status = 'FAILED' THEN 'FAILED'
                    WHEN parse_status IN ('QUEUED','RUNNING') THEN 'PENDING'
                    WHEN assembly_status = 'DUPLICATE' THEN 'DUPLICATE'
                    WHEN parse_status = 'COMPLETED' THEN 'COMPLETED'
                    ELSE 'PARKED' END AS outcome
                FROM batch_groups
            )
            SELECT count(*) AS total,
                   count(*) FILTER (WHERE outcome = 'COMPLETED') AS completed,
                   count(*) FILTER (WHERE outcome = 'FAILED') AS failed,
                   count(*) FILTER (WHERE outcome = 'DUPLICATE') AS duplicate,
                   count(*) FILTER (WHERE outcome = 'PARKED') AS parked
            FROM outcomes
            """)
    IngestionGroupCounts ingestionGroupCounts(@Param("progressId") String progressId);

    record IngestionGroupCounts(long total, long completed, long failed, long duplicate, long parked) { }

    @org.apache.ibatis.annotations.Select("""
            SELECT EXISTS (
                SELECT 1 FROM ocean_gis.gis_file_meta f
                JOIN ocean_gis.gis_file_group g ON g.id = f.file_group_id AND g.deleted = 0
                WHERE f.id = #{fileId} AND f.deleted = 0 AND g.entry_file_id = f.id
                    AND g.parse_status = 'RUNNING' AND f.metadata_status = 'RUNNING'
            )
            """)
    boolean isIngestionFileExtracting(@Param("fileId") Long fileId);

    List<GisDataSetVO.FileSummary> selectDataSetFileSummaries(@Param("ids") List<Long> ids);

    Page<GisFileMetaVO> selectFilePage(Page<GisFileMetaVO> page,
            @Param("q") GisFileQuery query);

    /** 使用单条 SQL 批量写入文件元数据。 */
    int insertBatch(@Param("records") List<GisFileMeta> records);

    GisFileMeta selectActiveByIdForUpdate(@Param("id") Long id);

    /** 锁定数据集中的全部文件，包含调整前遗留的逻辑删除记录。 */
    List<GisFileMeta> selectAllByDataSetIdForUpdate(@Param("dataSetId") Long dataSetId);

    int physicallyDeleteActiveById(@Param("id") Long id);

}
