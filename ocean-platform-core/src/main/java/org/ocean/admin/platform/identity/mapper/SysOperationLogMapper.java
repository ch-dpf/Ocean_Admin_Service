package org.ocean.admin.platform.identity.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.ocean.admin.platform.identity.entity.SysOperationLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 系统操作日志 Mapper
 *
 * @author DeepSea
 * @since 2026-04-16
 */
@Mapper
public interface SysOperationLogMapper extends BaseMapper<SysOperationLog> {
}
