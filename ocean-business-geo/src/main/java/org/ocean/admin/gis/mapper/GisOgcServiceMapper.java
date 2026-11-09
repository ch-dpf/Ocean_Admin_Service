package org.ocean.admin.gis.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.ocean.admin.gis.entity.GisOgcService;
import org.ocean.admin.gis.entity.GisTileSet;

import java.util.List;

/** WMTS、WMS、TMS 独立发布服务的持久化操作。 */
@Mapper
public interface GisOgcServiceMapper extends BaseMapper<GisOgcService> {

    /** 能力文档按协议列出所有已启用服务；不分页，发布量由管理接口控制。 */
    @Select("""
            SELECT * FROM ocean_gis.gis_ogc_service
            WHERE protocol = #{protocol} AND status = 'PUBLISHED'
            ORDER BY publish_time DESC, id
            """)
    List<GisOgcService> selectPublishedByProtocol(@Param("protocol") String protocol);

    /** 按服务编码定位已启用服务；能力文档与取瓦片请求共用。 */
    @Select("""
            SELECT * FROM ocean_gis.gis_ogc_service
            WHERE service_code = #{serviceCode} AND protocol = #{protocol}
              AND status = 'PUBLISHED'
            """)
    GisOgcService selectPublishedByCode(@Param("serviceCode") String serviceCode,
            @Param("protocol") String protocol);

    /** 能力文档与公开资源解析按服务编码定位瓦片集。 */
    @Select("""
            SELECT ts.* FROM ocean_gis.gis_ogc_service s
            JOIN ocean_gis.gis_tile_set ts ON ts.id = s.tile_set_id
            WHERE s.service_code = #{serviceCode} AND s.protocol = #{protocol}
              AND s.status = 'PUBLISHED'
            """)
    GisTileSet selectPublishedTileSet(@Param("serviceCode") String serviceCode,
            @Param("protocol") String protocol);

    /** 同一任务的同一协议只允许有一条发布记录，停用后通过启用接口恢复。 */
    @Select("""
            SELECT COUNT(*) FROM ocean_gis.gis_ogc_service
            WHERE source_task_id = #{sourceTaskId} AND protocol = #{protocol}
            """)
    long countByTaskAndProtocol(@Param("sourceTaskId") Long sourceTaskId,
            @Param("protocol") String protocol);
}
