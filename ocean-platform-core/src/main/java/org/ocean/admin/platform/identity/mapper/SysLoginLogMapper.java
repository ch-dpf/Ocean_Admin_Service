package org.ocean.admin.platform.identity.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.ocean.admin.platform.identity.entity.SysLoginLog;

/**
 * 系统登录日志 Mapper
 *
 * @author DeepSea
 * @since 2026-04-16
 */
@Mapper
public interface SysLoginLogMapper extends BaseMapper<SysLoginLog> {
}
