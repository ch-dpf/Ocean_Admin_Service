package org.ocean.admin.platform.identity.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ocean.admin.kernel.common.PageResult;
import org.ocean.admin.platform.identity.entity.SysLoginLog;
import org.ocean.admin.platform.identity.mapper.SysLoginLogMapper;
import org.ocean.admin.platform.identity.utils.LogQuerySupport;
import org.ocean.admin.platform.identity.vo.LogQueryVO;
import org.ocean.admin.platform.identity.vo.SysLoginLogVO;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 系统登录日志服务
 *
 * @author DeepSea
 * @since 2026-04-16
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SysLoginLogService {

    private final SysLoginLogMapper loginLogMapper;

    /**
     * 分页查询登录日志
     */
    public PageResult<List<SysLoginLogVO>> queryLoginLogs(LogQueryVO query) {
        Page<SysLoginLog> page = new Page<>(query.getCurrent(), query.getSize());
        LambdaQueryWrapper<SysLoginLog> wrapper = LogQuerySupport.buildCommonWrapper(
            query,
            SysLoginLog::getDeleted,
            SysLoginLog::getUsername,
            SysLoginLog::getStatus,
            SysLoginLog::getLoginTime
        );

        Page<SysLoginLog> resultPage = loginLogMapper.selectPage(page, wrapper);
        return LogQuerySupport.toPageResult(resultPage, this::convertToVO);
    }

    /**
     * 根据ID获取登录日志详情
     */
    public SysLoginLogVO getLoginLogById(Long id) {
        SysLoginLog entity = loginLogMapper.selectById(id);
        if (entity == null || entity.getDeleted() == 1) {
            return null;
        }
        return convertToVO(entity);
    }

    /**
     * 删除登录日志
     */
    @Transactional
    public boolean deleteLoginLog(Long id) {
        return loginLogMapper.deleteById(id) > 0;
    }

    /**
     * 批量删除登录日志
     */
    @Transactional
    public boolean batchDeleteLoginLogs(List<Long> ids) {
        return loginLogMapper.deleteBatchIds(ids) > 0;
    }

    /**
     * 清空登录日志
     */
    @Transactional
    public boolean clearLoginLogs() {
        LambdaQueryWrapper<SysLoginLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysLoginLog::getDeleted, 0);
        return loginLogMapper.delete(wrapper) >= 0;
    }

    /**
     * 记录登录日志
     */
    @Transactional
    public void recordLoginLog(SysLoginLog logEntity) {
        loginLogMapper.insert(logEntity);
    }

    /**
     * 更新登出时间
     */
    @Transactional
    public void updateLogoutTime(String sessionId, LocalDateTime logoutTime) {
        LambdaQueryWrapper<SysLoginLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysLoginLog::getSessionId, sessionId);
        wrapper.eq(SysLoginLog::getDeleted, 0);
        
        SysLoginLog logEntity = loginLogMapper.selectOne(wrapper);
        if (logEntity != null) {
            logEntity.setLogoutTime(logoutTime);
            loginLogMapper.updateById(logEntity);
        }
    }

    public Map<String, SysLoginLog> getLatestLoginLogsBySessionIds(List<String> sessionIds) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            return Map.of();
        }

        LambdaQueryWrapper<SysLoginLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(SysLoginLog::getSessionId, sessionIds);
        wrapper.eq(SysLoginLog::getDeleted, 0);
        wrapper.eq(SysLoginLog::getStatus, 1);
        wrapper.orderByDesc(SysLoginLog::getLoginTime);

        List<SysLoginLog> logs = loginLogMapper.selectList(wrapper);
        Map<String, SysLoginLog> result = new LinkedHashMap<>();
        for (SysLoginLog logEntity : logs) {
            if (logEntity.getSessionId() != null && !logEntity.getSessionId().isBlank()) {
                result.putIfAbsent(logEntity.getSessionId(), logEntity);
            }
        }
        return result;
    }

    /**
     * 实体转VO
     */
    private SysLoginLogVO convertToVO(SysLoginLog entity) {
        SysLoginLogVO vo = new SysLoginLogVO();
        BeanUtils.copyProperties(entity, vo);
        return vo;
    }
}
